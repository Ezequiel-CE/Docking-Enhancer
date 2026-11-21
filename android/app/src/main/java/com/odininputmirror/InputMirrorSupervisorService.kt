package com.odininputmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.odininputmirror.data.InputMirrorGraph
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.ControllerMapping
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.model.MirrorStatus
import com.odininputmirror.domain.usecase.AutoMirrorDecision

class InputMirrorSupervisorService : Service() {
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private lateinit var graph: InputMirrorGraph
    private lateinit var notificationManager: NotificationManager
    private var supervisorState = SupervisorState.WaitingForDock

    override fun onCreate() {
        super.onCreate()
        graph = InputMirrorGraph.of(this, forceDockMode = BuildConfig.FORCE_DOCK_MODE_FOR_DEV)
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
        // Guarded because this is a hard crash otherwise: a foreground start the framework refuses
        // (ForegroundServiceStartNotAllowedException) throws right here, and the runCatching around
        // startForegroundService in MainApplication is on the caller's side of the binder — it never
        // sees this. Taking the process down over a notification we could not post is the worse
        // outcome of the two, since everything the supervisor does still works without it.
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(supervisorState)) }
            .onFailure { Log.w(TAG, "Could not enter the foreground", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            worker = Thread({ superviseMirror() }, "input-mirror-supervisor").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun superviseMirror() {
        try {
            superviseUntilStopped()
        } finally {
            // However the worker leaves — the unsupported return below, an interrupt, an unexpected
            // throw — the flag must stop claiming there is a supervisor behind it. Left true, the
            // service would sit in the foreground forever with onStartCommand refusing to start a
            // replacement because it believed one was already running.
            running = false
        }
    }

    private fun superviseUntilStopped() {
        // Without the PServerBinder service there is nothing to drive. Established HERE, on the
        // worker, because the probe blocks on a binder transact — the startup paths used to ask on
        // the main thread before starting us at all.
        if (!graph.isSupportedDevice) {
            // Stand down completely rather than idling. A stopped worker behind a live foreground
            // service would pin an ongoing notification the user cannot swipe away, saying only that
            // their device will never work. The screen surfaces that verdict on its own.
            Log.i(TAG, "PServerBinder is unavailable; the supervisor has nothing to drive")
            stopSelf()
            return
        }

        var nextRestartAllowedAt = 0L
        var nextStartRetryAllowedAt = 0L
        var sleepMs = SUPERVISOR_INTERVAL_MS

        while (running) {
            try {
                val now = System.currentTimeMillis()
                val settings = graph.settingsRepository.getSettings()
                val dockActive = graph.dockStateRepository.isDockActive()
                // Enumerate controllers only when the result is actually consumed: docked (the
                // auto-mirror decision below reads the list) or the UI is on-screen (it renders the
                // list for setup). Undocked with the UI hidden, this is the one privileged PServer
                // call per tick that nobody reads — skip it so a pocketed handheld isn't spawning a
                // subprocess every idle tick. The undocked decision uses an empty list regardless.
                val allDevices = if (dockActive || MirrorStateStore.uiVisible) {
                    graph.inputDeviceRepository.getConnectedControllers()
                } else {
                    emptyList()
                }
                val mirrorRunning = graph.processRepository.isRunning()
                // While nothing is running, heal any node a crashed daemon left hidden so a stuck,
                // invisible external controller can recover on its own (no-op unless there's a
                // hidden-state record to restore).
                if (!mirrorRunning) {
                    runCatching { graph.processRepository.healOrphanedHideNodes() }
                }
                publishSnapshot(settings, dockActive, allDevices, mirrorRunning)

                if (!settings.autoMirrorEnabled) {
                    if (mirrorRunning || settings.expectedRunning) {
                        runCatching { graph.stopMirror() }
                    }
                    updateSupervisorState(SupervisorState.Disabled)
                    sleepMs = SUPERVISOR_IDLE_INTERVAL_MS
                    MirrorStateStore.awaitNextTick(sleepMs)
                    continue
                }
                val devices = if (dockActive) allDevices else emptyList()
                val decision = graph.resolveAutoMirrorDecision(
                    dockActive = dockActive,
                    devices = devices,
                    settings = settings,
                    mirrorRunning = mirrorRunning,
                    restartAllowed = now >= nextRestartAllowedAt && now >= nextStartRetryAllowedAt,
                    appliedGeneration = if (mirrorRunning) {
                        graph.processRepository.appliedConfigGeneration()
                    } else {
                        null
                    },
                )

                when (decision) {
                    AutoMirrorDecision.StopForDock -> {
                        if (mirrorRunning || settings.expectedRunning) {
                            runCatching { graph.stopMirror() }
                        }
                        updateSupervisorState(SupervisorState.WaitingForDock)
                        sleepMs = SUPERVISOR_IDLE_INTERVAL_MS
                    }
                    AutoMirrorDecision.WaitingForInternalController -> {
                        updateSupervisorState(SupervisorState.WaitingForInternalController)
                        sleepMs = SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS
                    }
                    AutoMirrorDecision.WaitingForExternalController -> {
                        updateSupervisorState(SupervisorState.WaitingForExternalController)
                        sleepMs = SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS
                    }
                    AutoMirrorDecision.StopForSourceChange -> {
                        runCatching { graph.stopMirror() }
                        // Stopped, so the next tick sees no mirror and simply waits for the pick.
                        updateSupervisorState(SupervisorState.WaitingForExternalController)
                        sleepMs = SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS
                    }
                    AutoMirrorDecision.WaitingForRestartThrottle -> {
                        if (supervisorState != SupervisorState.MirrorStartFailed) {
                            updateSupervisorState(SupervisorState.Restarting)
                        }
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                    AutoMirrorDecision.Running -> {
                        updateSupervisorState(SupervisorState.Active)
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                    AutoMirrorDecision.ApplyLiveSettings -> {
                        val delivered = runCatching {
                            graph.processRepository.applyLiveSettings(
                                settings,
                                mappingFor(devices, settings.source),
                            )
                        }.getOrDefault(false)
                        if (!delivered) {
                            // The daemon is alive but deaf on its control channel. Stopping it hands
                            // the next tick a clean start carrying the new options — the restart
                            // path is still here, it is just the fallback now rather than the
                            // mechanism.
                            Log.w(TAG, "Could not deliver settings to the daemon; restarting it")
                            runCatching { graph.stopMirror() }
                        }
                        updateSupervisorState(SupervisorState.Active)
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                    is AutoMirrorDecision.Start -> {
                        val request = decision.request.copy(mapping = mappingFor(devices, decision.request.source))
                        updateSupervisorState(SupervisorState.Starting)
                        if (startMirror(request)) {
                            updateSupervisorState(SupervisorState.Active)
                        } else {
                            nextStartRetryAllowedAt = now + START_RETRY_BACKOFF_MS
                        }
                        nextRestartAllowedAt = now + RESTART_THROTTLE_MS
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                    is AutoMirrorDecision.Restart -> {
                        val request = decision.request.copy(mapping = mappingFor(devices, decision.request.source))
                        updateSupervisorState(SupervisorState.Restarting)
                        runCatching { graph.stopMirror() }
                        if (startMirror(request)) {
                            updateSupervisorState(SupervisorState.Active)
                        } else {
                            nextStartRetryAllowedAt = now + START_RETRY_BACKOFF_MS
                        }
                        nextRestartAllowedAt = now + RESTART_THROTTLE_MS
                        sleepMs = SUPERVISOR_INTERVAL_MS
                    }
                }
            } catch (interrupted: InterruptedException) {
                // onDestroy interrupted us mid-tick (e.g. during shell IO); exit without publishing
                // a stale Error notification for a service that is already gone.
                break
            } catch (failure: Exception) {
                // Keep the supervisor alive; transient root/device failures are expected during reconnects.
                Log.w(TAG, "Supervisor tick failed", failure)
                updateSupervisorState(SupervisorState.Error)
                sleepMs = SUPERVISOR_ERROR_INTERVAL_MS
            }

            try {
                MirrorStateStore.awaitNextTick(sleepMs)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun publishSnapshot(
        settings: MirrorSettings,
        dockActive: Boolean,
        devices: List<ControllerDevice>,
        mirrorRunning: Boolean,
    ) {
        MirrorStateStore.publish(
            devices = devices,
            status = MirrorStatus(
                running = mirrorRunning,
                expectedRunning = settings.expectedRunning,
                source = settings.source,
                target = settings.target,
                sourceGuid = settings.sourceGuid,
                targetGuid = settings.targetGuid,
                homeAsBack = settings.homeAsBack,
                comboHoldKillApp = settings.comboHoldKillApp,
                virtualMouse = settings.virtualMouse,
                autoMirrorEnabled = settings.autoMirrorEnabled,
                docked = dockActive,
                manualInternalGuid = settings.manualInternalGuid,
                manualExternalGuid = settings.manualExternalGuid,
                manualExternalName = settings.manualExternalName,
            ),
        )
    }

    /**
     * The mapping saved for whichever controller is about to be mirrored.
     *
     * Resolved here, from the device list, because the mapping belongs to the REAL controller and
     * only the device list knows which real device is behind the source path — the source itself is
     * the firmware's twin, whose identity every external pad shares.
     */
    private fun mappingFor(devices: List<ControllerDevice>, sourcePath: String?): ControllerMapping {
        val key = devices.firstOrNull { it.path == sourcePath }?.mappingKey
            ?: return ControllerMapping()
        // A pad the bundled database knows starts life already mapped; a pad the user has touched is
        // left exactly as they saved it.
        graph.seedDefaultMapping(key)
        return graph.mappingRepository.get(key)
    }

    private fun startMirror(request: MirrorStartRequest): Boolean {
        return runCatching {
            graph.startMirror(request)
            true
        }.getOrElse { failure ->
            Log.w(TAG, "Mirror start failed for ${request.source} -> ${request.target}", failure)
            updateSupervisorState(SupervisorState.MirrorStartFailed)
            false
        }
    }

    private fun updateSupervisorState(state: SupervisorState) {
        // An interrupt does not abort a binder transact already in flight, so a tick that was mid-call
        // when onDestroy ran finishes and arrives here afterwards. Posting then re-creates an ongoing
        // notification for a service the framework has already torn down — and ongoing means the user
        // cannot swipe it away.
        if (!running || supervisorState == state) {
            return
        }
        supervisorState = state
        notificationManager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Mirror supervisor",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(state: SupervisorState): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingIntentFlags)

        return builder
            .setContentTitle("Docking Enhancer")
            .setContentText(state.notificationText)
            .setSmallIcon(R.drawable.ic_stat_mirror)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private enum class SupervisorState(val notificationText: String) {
        WaitingForDock("Waiting for external display"),
        Disabled("Automatic mirror disabled"),
        // Device-neutral wording: support is gated on the PServerBinder service existing, not on a
        // model list, so naming one handheld here would be wrong on any other that qualifies.
        WaitingForInternalController("Waiting for built-in controller"),
        WaitingForExternalController("Waiting for external controller"),
        Starting("Starting dock mirror"),
        Active("Dock mirror active"),
        Restarting("Restarting dock mirror"),
        MirrorStartFailed("Could not start the mirror"),
        Error("Mirror supervisor error"),
    }

    companion object {
        private const val TAG = "InputMirrorSupervisor"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "input_mirror_supervisor"
        private const val SUPERVISOR_INTERVAL_MS = 2500L
        private const val SUPERVISOR_IDLE_INTERVAL_MS = 8000L
        private const val SUPERVISOR_WAITING_FOR_DEVICE_INTERVAL_MS = 6000L
        private const val SUPERVISOR_ERROR_INTERVAL_MS = 15000L
        private const val RESTART_THROTTLE_MS = 10000L
        private const val START_RETRY_BACKOFF_MS = 60000L
    }
}
