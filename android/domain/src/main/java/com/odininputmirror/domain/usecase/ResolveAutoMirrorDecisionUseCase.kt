package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.model.selectExternalController

class ResolveAutoMirrorDecisionUseCase {
    operator fun invoke(
        dockActive: Boolean,
        devices: List<ControllerDevice>,
        settings: MirrorSettings,
        mirrorRunning: Boolean,
        restartAllowed: Boolean = true,
        // Generation of the config the running daemon reports it has adopted; null when it hasn't
        // said yet (it has only just started) or cannot say (a daemon predating the ack).
        appliedGeneration: Long? = null,
    ): AutoMirrorDecision {
        if (!dockActive) {
            return AutoMirrorDecision.StopForDock
        }

        val local = devices.firstOrNull { it.isInternal }
            ?: return AutoMirrorDecision.WaitingForInternalController
        val external = devices.selectExternalController(local, settings.manualExternalGuid, settings.manualExternalName)
            ?: return if (mirrorRunning && !settings.manualExternalGuid.isNullOrBlank()) {
                // The picked pad is absent, so whatever the daemon is reading is not it: a pad being
                // mirrored is hidden but re-materialised from /proc, and stays listed until it really
                // disconnects. Waiting with it still running would keep mirroring the device the
                // user just picked away from, e.g. the RP5's virtual mouse. Automatic mode keeps
                // plain waiting: there the old source vanishing already ends the mirror, and a
                // stop here would turn every gap in enumeration into a restart.
                AutoMirrorDecision.StopForSourceChange
            } else {
                AutoMirrorDecision.WaitingForExternalController
            }

        // Hiding the external's framework-visible Odin node is the mirror's default behaviour.
        val request = MirrorStartRequest(
            source = external.path,
            target = local.path,
            sourceGuid = external.guid,
            targetGuid = local.guid,
            homeAsBack = settings.homeAsBack,
            comboHoldKillApp = settings.comboHoldKillApp,
            virtualMouse = settings.virtualMouse,
            hideNodes = listOfNotNull(external.hideNodePath),
        )

        val sameMirror = settings.sourceGuid == external.guid &&
            settings.targetGuid == local.guid &&
            settings.source == external.path &&
            settings.target == local.path
        if (mirrorRunning && sameMirror) {
            // The daemon re-reads its options on demand now, so a changed toggle needs a push, not a
            // restart — which is what spares the user the second of visible controller that
            // releasing the exclusive grab costs.
            return when (appliedGeneration) {
                settings.configGeneration -> AutoMirrorDecision.Running
                // Silence is not staleness: a daemon that has not reported yet is one we have
                // nothing to correct. Pushing here would fire on every tick of the startup window.
                null -> AutoMirrorDecision.Running
                else -> AutoMirrorDecision.ApplyLiveSettings
            }
        }
        if (!restartAllowed) {
            return AutoMirrorDecision.WaitingForRestartThrottle
        }

        return if (mirrorRunning) {
            AutoMirrorDecision.Restart(request)
        } else {
            AutoMirrorDecision.Start(request)
        }
    }
}

sealed class AutoMirrorDecision {
    object StopForDock : AutoMirrorDecision()
    object WaitingForInternalController : AutoMirrorDecision()
    object WaitingForExternalController : AutoMirrorDecision()

    /** A mirror is running, but the manually picked external controller is not present. */
    object StopForSourceChange : AutoMirrorDecision()
    object WaitingForRestartThrottle : AutoMirrorDecision()
    object Running : AutoMirrorDecision()

    /**
     * The right mirror is running, but with options the user has since changed. Hand it the new
     * config instead of restarting it.
     */
    object ApplyLiveSettings : AutoMirrorDecision()
    data class Start(val request: MirrorStartRequest) : AutoMirrorDecision()
    data class Restart(val request: MirrorStartRequest) : AutoMirrorDecision()
}
