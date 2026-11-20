package com.odininputmirror.domain.model

data class MirrorSettings(
    val source: String? = null,
    val target: String? = null,
    val sourceGuid: String? = null,
    val targetGuid: String? = null,
    val homeAsBack: Boolean = false,
    val comboHoldKillApp: Boolean = false,
    val virtualMouse: Boolean = false,
    val autoMirrorEnabled: Boolean = true,
    val expectedRunning: Boolean = false,
    val startedAt: Long = 0L,
    val manualInternalGuid: String? = null,
    // The external controller the user picked. Null means "first other controller".
    val manualExternalGuid: String? = null,
    // Advances every time a live option changes. The running daemon echoes the generation it has
    // actually adopted in its heartbeat, so comparing the two says whether a toggle has taken
    // effect — which is what lets a settings change reach the daemon without restarting it.
    val configGeneration: Long = 0L,
)
