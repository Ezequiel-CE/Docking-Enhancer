package com.odininputmirror.data

import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.model.mappingSlots
import com.odininputmirror.domain.model.odinFallbackTraits
import com.odininputmirror.domain.repository.InputDeviceRepository
import com.odininputmirror.domain.repository.MappingRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * First-time defaults from the bundled controller database.
 *
 * A pad the database knows gets its mapping the moment it is first about to be mirrored, so it works
 * before the editor is ever opened. Seeding writes through [MappingRepository] like any user edit,
 * so the editor shows and adjusts it with nothing special-cased.
 *
 * A pad whose mapping the user emptied is indistinguishable from one never seen, so "Clear all" is
 * followed by a re-seed on the next mirror start. That is the intended meaning: clearing returns the
 * pad to its defaults, and for a known pad the database entry IS its default — a user who wants a
 * binding gone clears that one row, which leaves the mapping non-empty and therefore untouched.
 */
internal class DefaultMappingSeeder(
    private val databaseLines: () -> Sequence<String>,
    private val devices: InputDeviceRepository,
    private val mappings: MappingRepository,
) {
    /**
     * Keys the DATABASE has no entry for, so a pad it does not know is looked up once.
     *
     * Strictly that, and nothing else. It used to record the key before the lookup, which meant every
     * later failure — a pad mid-reconnect, the mirror still starting — marked a pad the database DOES
     * know as unknown for the life of the process, and it never got its profile.
     *
     * Shared between the supervisor thread and the editor's IO coroutines, so it has to be concurrent.
     */
    private val missing: MutableSet<MappingKey> = ConcurrentHashMap.newKeySet()

    fun seedIfEmpty(key: MappingKey) {
        // The database's indices describe the REAL pad's codes. If the firmware's twin - the node the
        // mirror actually reads - already carries the handheld's layout (different capability bits
        // from the real pad), those indices no longer apply: mapping on top would translate twice
        // (right stick onto the triggers, X/Y/bumpers shifted). Such a pad needs no default mapping,
        // and a default seeded by an earlier version is stale and removed. A mapping the user made
        // themselves is never touched.
        if (twinAlreadyTranslated(key)) {
            if (runCatching { mappings.isSeeded(key) }.getOrDefault(false)) {
                runCatching { mappings.clear(key) }
            }
            return
        }
        val existing = runCatching { mappings.get(key) }.getOrNull() ?: return
        if (!existing.isEmpty) {
            return
        }
        // Seeding is attempted on every mirror start, and for a pad with no entry it would otherwise
        // re-read and re-parse the whole bundled database each time to reach the same answer.
        if (key in missing) {
            return
        }

        val parts = key.value.split(':')
        val vendor = parts.getOrNull(0)?.toIntOrNull(16)
        val product = parts.getOrNull(1)?.toIntOrNull(16)
        if (vendor == null || product == null) {
            // Not vendor:product hex, so no database entry can ever match it. Permanent, like an
            // absent entry, and cached for the same reason.
            missing.add(key)
            return
        }

        val entry = GameControllerDb(databaseLines()).entryFor(vendor, product)
        if (entry == null) {
            // The ONLY place a key is recorded as missing: this is the one answer that cannot change
            // while the app runs. Everything below reads the live device instead, where a null is a
            // moment in time rather than a verdict.
            missing.add(key)
            return
        }

        // The REAL pad's capabilities: the entry's indices were assigned against that device, and
        // its /proc entry survives even while its /dev node is hidden.
        val source = devices.sourceTraits(key) ?: return
        val slots = mappingSlots(devices.targetTraits() ?: odinFallbackTraits())

        val mapping = GameControllerDb.toMapping(entry, source.keys, source.axes, slots)
        if (!mapping.isEmpty) {
            mappings.save(key, mapping)
            // Marked so the UI can tell a default apart from the user's work: the "custom mapping"
            // badge should only ever mean the user made it. The first edit clears the mark.
            mappings.setSeeded(key, true)
        }
    }

    private fun twinAlreadyTranslated(key: MappingKey): Boolean {
        val real = devices.sourceTraits(key) ?: return false
        val twin = devices.twinTraits(key) ?: return false
        return twin.keys != real.keys || twin.axes != real.axes
    }
}
