package com.odininputmirror.domain.repository

import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest

interface MirrorSettingsRepository {
    fun getSettings(): MirrorSettings
    fun saveStarted(request: MirrorStartRequest, startedAt: Long)
    fun setExpectedRunning(expectedRunning: Boolean)
    fun setHomeAsBackEnabled(enabled: Boolean)
    fun setComboHoldKillAppEnabled(enabled: Boolean)
    fun setVirtualMouseEnabled(enabled: Boolean)
    fun setAutoMirrorEnabled(enabled: Boolean)
    fun setManualInternalController(guid: String?)
    /** Saves the picked external controller, or clears the pick when [guid] is null or blank. */
    fun setManualExternalController(guid: String?, name: String?)

    /**
     * Advance the config generation without changing a setting.
     *
     * Saving a mapping has to reach the running daemon, and the generation is what makes it look:
     * the supervisor pushes the config when the number it sees differs from the one stored here.
     */
    fun bumpConfigGeneration()
}
