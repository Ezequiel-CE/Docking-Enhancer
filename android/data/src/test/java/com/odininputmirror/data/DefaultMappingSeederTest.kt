package com.odininputmirror.data

import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.ControllerMapping
import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.model.TargetTraits
import com.odininputmirror.domain.model.odinFallbackTraits
import com.odininputmirror.domain.repository.InputDeviceRepository
import com.odininputmirror.domain.repository.MappingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMappingSeederTest {
    // The same 8BitDo Ultimate line the database tests use: vendor 2dc8, product 3106.
    private val eightBitDo =
        "03000000c82d00000631000010010000,8BitDo Ultimate,a:b0,b:b1,back:b6,dpdown:h0.4,dpleft:h0.8," +
            "dpright:h0.2,dpup:h0.1,leftshoulder:b4,leftstick:b7,lefttrigger:a4,leftx:a0,lefty:a1," +
            "rightshoulder:b5,rightstick:b8,righttrigger:a5,rightx:a2,righty:a3,start:b11,x:b3,y:b2," +
            "platform:Linux"

    private val known = MappingKey("2dc8:3106")
    private val unknown = MappingKey("dead:beef")

    private val padTraits = TargetTraits(
        keys = setOf(0x131, 0x133, 0x134, 0x136, 0x137, 0x138, 0x139, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e),
        axes = setOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x10, 0x11),
    )

    /**
     * The bug this exists for: the key used to be recorded as "the database doesn't know it" BEFORE
     * the database was asked, so any later failure marked it permanently. `sourceTraits` reads the
     * live pad, and a null there just means the mirror was still coming up — after which a pad the
     * database DOES know was never seeded again for the life of the process.
     */
    @Test
    fun aPadIsStillSeededAfterTheLiveReadFailedOnce() {
        val mappings = FakeMappingRepository()
        var traitsAvailable = false
        val devices = FakeDeviceRepository { if (traitsAvailable) padTraits else null }
        val seeder = DefaultMappingSeeder({ sequenceOf(eightBitDo) }, devices, mappings)

        // First attempt lands while the pad's /proc entry is not readable yet.
        seeder.seedIfEmpty(known)
        assertTrue("it should not have seeded anything yet", mappings.saved.isEmpty())

        // The pad settles; the very next attempt has to seed it.
        traitsAvailable = true
        seeder.seedIfEmpty(known)

        assertFalse("a transient failure permanently poisoned the lookup", mappings.saved.isEmpty())
        assertTrue("it was not marked as a database default", mappings.seeded[known] == true)
    }

    @Test
    fun aPadTheDatabaseDoesNotKnowIsLookedUpOnlyOnce() {
        val mappings = FakeMappingRepository()
        var databaseReads = 0
        val devices = FakeDeviceRepository { padTraits }
        val seeder = DefaultMappingSeeder(
            { databaseReads++; sequenceOf(eightBitDo) },
            devices,
            mappings,
        )

        repeat(4) { seeder.seedIfEmpty(unknown) }

        assertEquals("the whole database was re-parsed for an answer already known", 1, databaseReads)
    }

    @Test
    fun aMalformedKeyIsNotLookedUpAtAll() {
        val mappings = FakeMappingRepository()
        var databaseReads = 0
        val seeder = DefaultMappingSeeder(
            { databaseReads++; sequenceOf(eightBitDo) },
            FakeDeviceRepository { padTraits },
            mappings,
        )

        repeat(3) { seeder.seedIfEmpty(MappingKey("not-a-key")) }

        assertEquals("a key that can never match still hit the database", 0, databaseReads)
    }

    @Test
    fun anAlreadyMappedPadIsLeftAlone() {
        val mappings = FakeMappingRepository()
        mappings.save(known, ControllerMapping(listOf()))
        var databaseReads = 0
        val seeder = DefaultMappingSeeder(
            { databaseReads++; sequenceOf(eightBitDo) },
            FakeDeviceRepository { padTraits },
            mappings,
        )

        // A non-empty stored mapping is the user's; seeding must not look at it, let alone rewrite it.
        mappings.saved[known] = ControllerMapping(mappingSlotsSample())
        seeder.seedIfEmpty(known)

        assertEquals("a user's own mapping triggered a database lookup", 0, databaseReads)
    }

    // The handheld's own layout, as the firmware's twin republishes every pad: not the real pad's codes.
    private val translatedTwin = TargetTraits(
        keys = setOf(0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x13a, 0x13b, 0x13c, 0x13d, 0x13e),
        axes = setOf(0x00, 0x01, 0x02, 0x05, 0x09, 0x0a, 0x10, 0x11),
    )

    @Test
    fun aPadWhoseTwinIsAlreadyTranslatedIsNotSeeded() {
        // The RP6 bug: mapping the real pad's layout on top of the twin's already-translated codes
        // moved the right stick onto the triggers and shifted X, Y and both bumpers.
        val mappings = FakeMappingRepository()
        val seeder = DefaultMappingSeeder(
            { sequenceOf(eightBitDo) },
            FakeDeviceRepository(twin = { translatedTwin }) { padTraits },
            mappings,
        )

        seeder.seedIfEmpty(known)

        assertTrue("a translated twin got a default mapping", mappings.saved.isEmpty())
    }

    @Test
    fun aStaleSeededMappingIsRemovedWhenTheTwinIsTranslated() {
        val mappings = FakeMappingRepository()
        mappings.saved[known] = ControllerMapping(mappingSlotsSample())
        mappings.seeded[known] = true
        val seeder = DefaultMappingSeeder(
            { sequenceOf(eightBitDo) },
            FakeDeviceRepository(twin = { translatedTwin }) { padTraits },
            mappings,
        )

        seeder.seedIfEmpty(known)

        assertTrue("the stale default survived", mappings.saved.isEmpty())
    }

    @Test
    fun aMappingTheUserMadeSurvivesATranslatedTwin() {
        val mappings = FakeMappingRepository()
        mappings.saved[known] = ControllerMapping(mappingSlotsSample())
        val seeder = DefaultMappingSeeder(
            { sequenceOf(eightBitDo) },
            FakeDeviceRepository(twin = { translatedTwin }) { padTraits },
            mappings,
        )

        seeder.seedIfEmpty(known)

        assertFalse("the user's own mapping was deleted", mappings.saved.isEmpty())
    }

    @Test
    fun aPadWhoseTwinPassesCodesThroughStillGetsItsDefault() {
        val mappings = FakeMappingRepository()
        val seeder = DefaultMappingSeeder(
            { sequenceOf(eightBitDo) },
            FakeDeviceRepository(twin = { padTraits }) { padTraits },
            mappings,
        )

        seeder.seedIfEmpty(known)

        assertFalse("a raw-passthrough twin lost its default mapping", mappings.saved.isEmpty())
    }

    private fun mappingSlotsSample() = GameControllerDb(sequenceOf(eightBitDo))
        .entryFor(0x2dc8, 0x3106)!!
        .let { entry ->
            GameControllerDb.toMapping(
                entry,
                padTraits.keys,
                padTraits.axes,
                com.odininputmirror.domain.model.mappingSlots(odinFallbackTraits()),
            ).bindings
        }

    private class FakeMappingRepository : MappingRepository {
        val saved = mutableMapOf<MappingKey, ControllerMapping>()
        val seeded = mutableMapOf<MappingKey, Boolean>()

        override fun get(key: MappingKey): ControllerMapping = saved[key] ?: ControllerMapping()
        override fun save(key: MappingKey, mapping: ControllerMapping) {
            saved[key] = mapping
        }

        override fun clear(key: MappingKey) {
            saved.remove(key)
        }

        override fun isSeeded(key: MappingKey): Boolean = seeded[key] == true
        override fun setSeeded(key: MappingKey, seeded: Boolean) {
            this.seeded[key] = seeded
        }
    }

    private class FakeDeviceRepository(
        private val twin: () -> TargetTraits? = { null },
        private val traits: () -> TargetTraits?,
    ) : InputDeviceRepository {
        override fun twinTraits(key: MappingKey): TargetTraits? = twin()
        override fun getConnectedControllers(): List<ControllerDevice> = emptyList()
        override fun targetTraits(): TargetTraits? = odinFallbackTraits()
        override fun sourceTraits(key: MappingKey): TargetTraits? = traits()
    }
}
