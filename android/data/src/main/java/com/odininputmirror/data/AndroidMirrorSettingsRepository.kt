package com.odininputmirror.data

import android.content.Context
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.repository.MirrorSettingsRepository

class AndroidMirrorSettingsRepository(context: Context) : MirrorSettingsRepository {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun getSettings(): MirrorSettings {
        return MirrorSettings(
            source = prefs.getString(KEY_SOURCE, null),
            target = prefs.getString(KEY_TARGET, null),
            sourceGuid = prefs.getString(KEY_SOURCE_GUID, null),
            targetGuid = prefs.getString(KEY_TARGET_GUID, null),
            homeAsBack = prefs.getBoolean(KEY_HOME_AS_BACK, false),
            comboHoldKillApp = prefs.getBoolean(KEY_COMBO_HOLD_KILL_APP, false),
            virtualMouse = prefs.getBoolean(KEY_VIRTUAL_MOUSE, false),
            autoMirrorEnabled = prefs.getBoolean(KEY_AUTO_MIRROR_ENABLED, true),
            expectedRunning = prefs.getBoolean(KEY_EXPECTED_RUNNING, false),
            startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
            manualInternalGuid = prefs.getString(KEY_MANUAL_INTERNAL_GUID, null),
            manualExternalGuid = prefs.getString(KEY_MANUAL_EXTERNAL_GUID, null),
            configGeneration = prefs.getLong(KEY_CONFIG_GENERATION, 0L),
        )
    }

    override fun saveStarted(request: MirrorStartRequest, startedAt: Long) {
        prefs.edit()
            .putString(KEY_SOURCE, request.source)
            .putString(KEY_TARGET, request.target)
            .putString(KEY_SOURCE_GUID, request.sourceGuid)
            .putString(KEY_TARGET_GUID, request.targetGuid)
            .putBoolean(KEY_HOME_AS_BACK, request.homeAsBack)
            .putBoolean(KEY_COMBO_HOLD_KILL_APP, request.comboHoldKillApp)
            .putBoolean(KEY_VIRTUAL_MOUSE, request.virtualMouse)
            .putBoolean(KEY_EXPECTED_RUNNING, true)
            .putLong(KEY_STARTED_AT, startedAt)
            .apply()
    }

    override fun setExpectedRunning(expectedRunning: Boolean) {
        prefs.edit().putBoolean(KEY_EXPECTED_RUNNING, expectedRunning).apply()
    }

    override fun setHomeAsBackEnabled(enabled: Boolean) = setLiveOption(KEY_HOME_AS_BACK, enabled)

    override fun setComboHoldKillAppEnabled(enabled: Boolean) = setLiveOption(KEY_COMBO_HOLD_KILL_APP, enabled)

    override fun setVirtualMouseEnabled(enabled: Boolean) = setLiveOption(KEY_VIRTUAL_MOUSE, enabled)

    // Options the running daemon can adopt without being restarted. The generation advances in the
    // same commit as the value: it is the number the daemon acknowledges once it has adopted the
    // change, so a value stored without advancing it would be a change nobody ever asks it to make.
    override fun bumpConfigGeneration() = synchronized(generationLock) {
        prefs.edit().putLong(KEY_CONFIG_GENERATION, prefs.getLong(KEY_CONFIG_GENERATION, 0L) + 1).apply()
    }

    private fun setLiveOption(key: String, enabled: Boolean) = synchronized(generationLock) {
        prefs.edit()
            .putBoolean(key, enabled)
            .putLong(KEY_CONFIG_GENERATION, prefs.getLong(KEY_CONFIG_GENERATION, 0L) + 1)
            .apply()
    }

    private companion object {
        /*
         * The generation is read-modify-written, and every writer runs on its own IO coroutine: two
         * toggles flipped in quick succession both read N and both write N+1, so the supervisor sees
         * the number it already pushed and the second change never reaches the daemon. It reads as a
         * setting that silently did nothing.
         *
         * Held on the companion rather than the instance because the settings are one file and
         * nothing guarantees one repository object per process — two instances synchronizing on
         * themselves would take two different locks and protect nothing.
         */
        val generationLock = Any()
    }

    override fun setAutoMirrorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_MIRROR_ENABLED, enabled).apply()
    }

    override fun setManualInternalController(guid: String?) = setOptionalGuid(KEY_MANUAL_INTERNAL_GUID, guid)

    override fun setManualExternalController(guid: String?) = setOptionalGuid(KEY_MANUAL_EXTERNAL_GUID, guid)

    private fun setOptionalGuid(key: String, guid: String?) {
        prefs.edit().apply {
            if (guid.isNullOrBlank()) {
                remove(key)
            } else {
                putString(key, guid)
            }
        }.apply()
    }
}
