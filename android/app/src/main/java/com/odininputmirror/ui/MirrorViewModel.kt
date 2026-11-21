package com.odininputmirror.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.odininputmirror.BuildConfig
import com.odininputmirror.InputMirrorSupervisorService
import com.odininputmirror.MirrorStateStore
import com.odininputmirror.data.InputMirrorGraph
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.model.MirrorStatus
import com.odininputmirror.domain.model.findSavedControllerDevice
import com.odininputmirror.domain.model.selectExternalController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MirrorUiState(
    val devices: List<ControllerDevice> = emptyList(),
    val localDevice: ControllerDevice? = null,
    val externalDevice: ControllerDevice? = null,
    val enabled: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val message: String = "",
    val homeAsBack: Boolean = false,
    val comboHoldKillApp: Boolean = false,
    val autoMirrorEnabled: Boolean = true,
    val restartWaiting: Boolean = false,
    val docked: Boolean = false,
    val manualInternalGuid: String? = null,
    val manualExternalGuid: String? = null,
    val manualExternalName: String? = null,
    val unsupported: Boolean = false,
    // How many controls the external controller has captured, for the card. Null when it has no
    // identity to key a mapping on.
    val mappedControlCount: Int? = null,
    // The external pad carries an untouched database default (as opposed to the user's own mapping).
    val hasSeededMapping: Boolean = false,
    // The service is published but never answers (Odin 2 Mini). Distinct from absent, because
    // telling that owner their device "doesn't have it" would be flatly wrong.
    val serviceUnresponsive: Boolean = false,
    val virtualMouse: Boolean = false,
) {
    // On a recognised handheld (e.g. Odin) the native layer flags the internal controller by
    // hardware signature and locks it. Otherwise the internal defaults to the first detected
    // controller and the user can re-pick it manually.
    val hasKnownInternalProfile: Boolean
        get() = devices.any { it.isKnownInternal }
}

class MirrorViewModel(private val appContext: Context) : ViewModel() {
    // Literally the same graph the supervisor uses, not a second one configured to match. They have
    // to agree about whether a dock is present — when only one was told to force dock mode the screen
    // flipped to "undocked" the moment anything made it re-read, telling the user the dock had gone
    // while the supervisor happily went on mirroring — and sharing it also means one cached PServer
    // verdict and one seeding cache instead of two that can disagree.
    private val graph = InputMirrorGraph.of(appContext, forceDockMode = BuildConfig.FORCE_DOCK_MODE_FOR_DEV)

    private val _state = MutableStateFlow(MirrorUiState())
    val state: StateFlow<MirrorUiState> = _state.asStateFlow()

    // Owned rather than injected as a second ViewModel: it lives and dies with this screen, and
    // borrowing this scope is what keeps its polling tied to the same lifecycle.
    val mappingEditor = MappingEditorController(
        mappings = graph.mappingRepository,
        devices = graph.inputDeviceRepository,
        process = graph.processRepository,
        settings = graph.settingsRepository,
        seedDefaultMapping = graph::seedDefaultMapping,
        scope = viewModelScope,
        // Fired once the editor has finished writing, so the card's badge reflects what was just
        // saved instead of waiting for a supervisor snapshot that a mapping edit never triggers.
        onMappingChanged = ::refreshMappingBadges,
    )

    fun openMappingEditor() {
        val device = _state.value.externalDevice ?: return
        val key = device.mappingKey ?: return
        mappingEditor.open(device.name, key)
    }

    init {
        viewModelScope.launch {
            // Off the main thread: establishing this costs a binder transact the first time, and on
            // a device that publishes PServerBinder without answering it that transact hangs until it
            // fails. It used to run right here, in the ViewModel's constructor, on the UI thread.
            val support = withContext(Dispatchers.IO) {
                graph.isSupportedDevice to graph.isServiceUnresponsive
            }
            if (!support.first) {
                _state.update {
                    it.copy(loading = false, unsupported = true, serviceUnresponsive = support.second)
                }
                return@launch
            }

            startSupervisor()
            // Cold start: the supervisor may not have published a snapshot yet, so read once
            // directly to fill the screen immediately. Then observe the shared snapshot the
            // supervisor keeps fresh — the UI no longer runs its own PServer poll loop in
            // parallel with the service.
            if (MirrorStateStore.snapshot.value == null) {
                initialLoad()
            }
            MirrorStateStore.snapshot.collect { snapshot ->
                if (snapshot != null) {
                    applyStatus(snapshot.status, snapshot.devices)
                    _state.update { it.copy(loading = false) }
                }
            }
        }
    }

    // Pull a fresh reading immediately on resume so opening the app doesn't wait for the next
    // supervisor tick; steady-state updates arrive through the observed snapshot.
    fun onResume() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun initialLoad() {
        try {
            val (devices, status) = withContext(Dispatchers.IO) {
                graph.getConnectedDevices() to graph.getMirrorStatus()
            }
            applyStatus(status, devices)
        } catch (error: Exception) {
            _state.update { it.copy(message = error.message ?: error.toString()) }
        } finally {
            _state.update { it.copy(loading = false) }
        }
    }

    private suspend fun refresh() {
        try {
            val (devices, status) = withContext(Dispatchers.IO) {
                graph.getConnectedDevices() to graph.getMirrorStatus()
            }
            applyStatus(status, devices)
        } catch (_: Exception) {
            // Transient root/device failures are expected during reconnects; keep last state.
        }
    }

    private fun applyStatus(status: MirrorStatus, available: List<ControllerDevice>) {
        val autoLocal = available.firstOrNull { it.isInternal }
        // The same selection the supervisor makes, so the card never names a pad other than the one
        // being mirrored.
        val autoExternal = available.selectExternalController(autoLocal, status.manualExternalGuid, status.manualExternalName)
        val savedExternalIsLocal =
            autoLocal != null && savedIdentityMatches(status.source, status.sourceGuid, autoLocal)

        val localDevice = autoLocal
            ?: deviceFromSavedIdentity(status.target, status.targetGuid, available, "Saved built-in controller")
        val externalDevice = autoExternal
            ?: if (savedExternalIsLocal || status.manualExternalGuid != null) {
                // With a manual pick that is absent, the last-mirrored identity may be the very
                // device the user picked away from; showing it would claim it is still the source.
                null
            } else {
                deviceFromSavedIdentity(status.source, status.sourceGuid, available, "Waiting for external controller")
            }

        _state.update {
            it.copy(
                devices = available,
                localDevice = localDevice,
                externalDevice = externalDevice,
                enabled = status.running,
                homeAsBack = status.homeAsBack,
                comboHoldKillApp = status.comboHoldKillApp,
                virtualMouse = status.virtualMouse,
                autoMirrorEnabled = status.autoMirrorEnabled,
                restartWaiting = status.expectedRunning && !status.running,
                docked = status.docked,
                manualInternalGuid = status.manualInternalGuid,
                manualExternalGuid = status.manualExternalGuid,
                manualExternalName = status.manualExternalName,
                // Database defaults deliberately do not count: the green border and CUSTOM MAPPING
                // badge mean "the user made this", and a pad that merely matched its profile would
                // otherwise wear them out of the box.
                mappedControlCount = externalDevice?.mappingKey?.let(::mappedCountFor),
                hasSeededMapping = externalDevice?.mappingKey?.let(::isSeededFor) ?: false,
                message = buildStatusMessage(status, available),
            )
        }
    }

    // Database defaults deliberately do not count as "mapped": the green border and CUSTOM MAPPING
    // badge mean "the user made this", and a pad that merely matched its profile would otherwise
    // wear them out of the box.
    private fun mappedCountFor(key: MappingKey): Int? =
        graph.mappingRepository.get(key).boundControlCount
            .takeIf { it > 0 && !graph.mappingRepository.isSeeded(key) }

    private fun isSeededFor(key: MappingKey): Boolean =
        graph.mappingRepository.isSeeded(key) && !graph.mappingRepository.get(key).isEmpty

    /**
     * Recompute just the badge fields after the editor writes.
     *
     * Needed because the rest of this screen's state arrives on the supervisor's snapshot, and that
     * only re-emits when the mirror or the device list changes — neither of which a mapping edit
     * does. Without this the card keeps whatever badge it had when the last snapshot landed, so a
     * freshly seeded or freshly cleared pad shows the previous answer until something unrelated
     * happens to move the supervisor.
     */
    fun refreshMappingBadges() {
        viewModelScope.launch {
            val key = _state.value.externalDevice?.mappingKey ?: return@launch
            val (count, seeded) = withContext(Dispatchers.IO) {
                mappedCountFor(key) to isSeededFor(key)
            }
            _state.update { it.copy(mappedControlCount = count, hasSeededMapping = seeded) }
        }
    }

    // Option toggles only persist the setting; if the mirror is running with different flags the
    // supervisor notices (started* snapshot vs current settings) and restarts it automatically.
    fun toggleHomeAsBack(nextValue: Boolean) {
        val current = _state.value
        if (current.busy) return
        _state.update { it.copy(homeAsBack = nextValue) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.setHomeAsBackEnabled(nextValue) }
            } catch (error: Exception) {
                _state.update { it.copy(homeAsBack = !nextValue, message = error.message ?: error.toString()) }
            }
        }
    }

    fun toggleComboHoldKillApp(nextValue: Boolean) {
        val current = _state.value
        if (current.busy) return
        _state.update { it.copy(comboHoldKillApp = nextValue) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.setComboHoldKillAppEnabled(nextValue) }
            } catch (error: Exception) {
                _state.update { it.copy(comboHoldKillApp = !nextValue, message = error.message ?: error.toString()) }
            }
        }
    }

    fun toggleAutoMirrorEnabled(nextValue: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, autoMirrorEnabled = nextValue) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    graph.setAutoMirrorEnabled(nextValue)
                    if (!nextValue) {
                        // Best-effort: the setting is already persisted and the supervisor also
                        // stops the mirror, so a stop hiccup (e.g. nothing was running) must not
                        // revert the toggle or surface an error to the user.
                        runCatching { graph.stopMirror() }
                    }
                }
                if (nextValue) startSupervisor()
                _state.update {
                    it.copy(
                        enabled = if (nextValue) it.enabled else false,
                        message = if (nextValue) "Automatic dock mirror is enabled." else "Automatic dock mirror is disabled.",
                    )
                }
            } catch (error: Exception) {
                _state.update { it.copy(autoMirrorEnabled = !nextValue, message = error.message ?: error.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun selectInternalController(guid: String?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.setManualInternalController(guid) }
                _state.update { it.copy(manualInternalGuid = guid) }
                refresh()
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: error.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // Persist only. Unlike the internal pick this is allowed while the mirror runs — a wrong source
    // is exactly when the user needs it — and the supervisor restarts onto the new source on its own.
    // Takes the device, not just its guid: the name is saved alongside so a pad can be told apart from
    // a firmware device sharing its vendor:product. Null goes back to automatic.
    fun selectExternalController(device: ControllerDevice?) {
        if (_state.value.busy) return
        val guid = device?.guid?.takeIf { it.isNotBlank() }
        val name = device?.name?.takeIf { guid != null }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.setManualExternalController(guid, name) }
                _state.update { it.copy(manualExternalGuid = guid, manualExternalName = name) }
                refresh()
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: error.toString()) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun toggleVirtualMouse(nextValue: Boolean) {
        val current = _state.value
        if (current.busy) return
        _state.update { it.copy(virtualMouse = nextValue) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { graph.setVirtualMouseEnabled(nextValue) }
            } catch (error: Exception) {
                _state.update { it.copy(virtualMouse = !nextValue, message = error.message ?: error.toString()) }
            }
        }
    }

    private fun startSupervisor() {
        val intent = Intent(appContext, InputMirrorSupervisorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    private fun buildStatusMessage(status: MirrorStatus, devices: List<ControllerDevice>): String = when {
        status.running -> "Dock mirror active."
        !status.autoMirrorEnabled -> "Automatic dock mirror is disabled."
        devices.none { it.isInternal } -> "Waiting for the built-in controller."
        devices.selectExternalController(
            devices.firstOrNull { it.isInternal },
            status.manualExternalGuid,
            status.manualExternalName,
        ) == null ->
            if (status.manualExternalGuid != null) "Waiting for the selected external controller."
            else "Waiting for an external controller."
        status.expectedRunning -> "Controller detected. Mirror will start automatically."
        else -> "Automatic dock mirror is ready."
    }

    private fun savedIdentityMatches(path: String?, guid: String?, device: ControllerDevice): Boolean =
        (guid != null && device.guid == guid) || (path != null && device.path == path)

    private fun deviceFromSavedIdentity(
        path: String?,
        guid: String?,
        devices: List<ControllerDevice>,
        fallbackName: String,
    ): ControllerDevice? {
        devices.findSavedControllerDevice(path = path, guid = guid)?.let { return it }
        if (path.isNullOrEmpty() && guid.isNullOrEmpty()) return null
        return ControllerDevice(
            name = fallbackName,
            path = path ?: "",
            guid = guid ?: "",
            controllerNumber = 0,
        )
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MirrorViewModel(context.applicationContext) as T
        }
    }
}
