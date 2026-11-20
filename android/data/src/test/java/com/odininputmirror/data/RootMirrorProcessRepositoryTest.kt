package com.odininputmirror.data

import com.odininputmirror.domain.model.Binding
import com.odininputmirror.domain.model.ControlRef
import com.odininputmirror.domain.model.ControllerMapping
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.repository.MirrorSettingsRepository
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootMirrorProcessRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = 1_000_000L
    private lateinit var files: InputMirrorFiles
    private lateinit var settings: FakeSettingsRepository
    private lateinit var shell: RecordingShell
    private lateinit var procRoot: File
    private lateinit var repository: RootMirrorProcessRepository

    @Before
    fun setUp() {
        files = InputMirrorFiles(temp.newFolder("files")) { ByteArrayInputStream(FAKE_BINARY) }
        settings = FakeSettingsRepository()
        shell = RecordingShell()
        procRoot = temp.newFolder("proc")
        repository = RootMirrorProcessRepository(
            mirrorSettingsRepository = settings,
            files = files,
            shell = shell,
            nowMillis = { now },
            procRoot = procRoot,
        )
    }

    // -- isRunning: heartbeat ---------------------------------------------------

    @Test
    fun isRunningTrueWhenHeartbeatIsFresh() {
        writeHeartbeat(ageMs = 1_000L)

        assertTrue(repository.isRunning())
    }

    @Test
    fun isRunningFalseWhenHeartbeatIsStaleAndNoGraceApplies() {
        writeHeartbeat(ageMs = HEARTBEAT_STALE_MS + 1_000L)

        assertFalse(repository.isRunning())
    }

    @Test
    fun emptyHeartbeatFileIsNotFreshEvenWithRecentMtime() {
        // The daemon writes content on every beat; an empty file means it never actually beat
        // (e.g. a failed launch) and must not count as alive just because its mtime is recent.
        files.heartbeatFile.writeText("")
        files.heartbeatFile.setLastModified(now)

        assertFalse(repository.isRunning())
    }

    // -- isRunning: starting grace ----------------------------------------------

    @Test
    fun graceReportsRunningRightAfterStartBeforeAnyHeartbeat() {
        settings.state = MirrorSettings(expectedRunning = true, startedAt = now - 500L)

        assertTrue(repository.isRunning())
    }

    @Test
    fun graceExpiresAfterItsWindow() {
        settings.state = MirrorSettings(expectedRunning = true, startedAt = now - STARTING_GRACE_MS - 1L)

        assertFalse(repository.isRunning())
    }

    @Test
    fun zombieProcessDuringGraceClearsPidFileAndExpectedRunning() {
        settings.state = MirrorSettings(expectedRunning = true, startedAt = now - 500L)
        files.pidFile.writeText("123\n")
        writeProcStat(pid = 123, state = "Z")

        assertFalse(repository.isRunning())
        assertFalse(files.pidFile.exists())
        assertFalse(settings.state.expectedRunning)
    }

    @Test
    fun liveProcessDuringGraceReportsRunning() {
        settings.state = MirrorSettings(expectedRunning = true, startedAt = now - 500L)
        files.pidFile.writeText("123\n")
        writeProcStat(pid = 123, state = "S")

        assertTrue(repository.isRunning())
    }

    // -- start / stop -------------------------------------------------------------

    @Test
    fun startExtractsBinaryAndLaunchesGuardedQuotedCommand() {
        repository.start(
            MirrorStartRequest(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                homeAsBack = true,
                comboHoldKillApp = true,
                virtualMouse = true,
            )
        )

        val binary = File(File(files.pidFile.parentFile, "bin"), "input_mirror")
        assertTrue(binary.exists())

        val command = shell.launchedDaemons.single()
        // The guard bails out only for a daemon already on THESE nodes, and stops any other. Both
        // halves matter: a reconnect gives the controller a new eventN, and skipping the launch
        // because some older daemon is alive is what left the mirror reporting healthy while
        // forwarding nothing.
        assertTrue(command.contains("[ \"\$2\" = '/dev/input/event9' ]"))
        assertTrue(command.contains("[ \"\$3\" = '/dev/input/event2' ]"))
        assertTrue(command.contains("kill -TERM \$p"))
        assertTrue(command.contains("'/dev/input/event9' '/dev/input/event2'"))
        assertTrue(command.contains(" --home-as-back"))
        assertTrue(command.contains(" --combo-hold-kill-app"))
        assertTrue(command.contains(" --virtual-mouse"))
        assertTrue(command.contains("--pid-file '${files.pidFile.absolutePath}'"))
        assertTrue(command.contains("--heartbeat-file '${files.heartbeatFile.absolutePath}'"))
        assertTrue(command.contains("--hidden-state-file '${files.hiddenStateFile.absolutePath}'"))
        assertTrue(command.contains("--config-file '${files.configFile.absolutePath}'"))
        assertTrue(command.contains("--control-fifo '${files.controlFifo.absolutePath}'"))
    }

    @Test
    fun startWritesTheConfigTheDaemonWillRead() {
        settings.state = settings.state.copy(configGeneration = 3L)

        repository.start(
            MirrorStartRequest(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                homeAsBack = true,
                comboHoldKillApp = false,
                virtualMouse = true,
            )
        )

        val config = files.configFile.readText()
        assertTrue(config.contains("\"generation\": 3"))
        assertTrue(config.contains("\"home_as_back\": true"))
        assertTrue(config.contains("\"combo_hold_kill_app\": false"))
        assertTrue(config.contains("\"virtual_mouse\": true"))
    }

    @Test
    fun applyLiveSettingsRewritesTheConfigAndAsksForAReload() {
        var poked: File? = null
        val reloading = RootMirrorProcessRepository(
            mirrorSettingsRepository = settings,
            files = files,
            shell = shell,
            nowMillis = { now },
            procRoot = procRoot,
            sendCommand = { fifo, _ -> poked = fifo; true },
        )

        val delivered = reloading.applyLiveSettings(
            MirrorSettings(configGeneration = 9L, homeAsBack = true, virtualMouse = false),
            ControllerMapping(listOf(Binding(ControlRef.button(304), ControlRef.button(307)))),
        )

        assertTrue(delivered)
        assertEquals(files.controlFifo, poked)
        val config = files.configFile.readText()
        assertTrue(config.contains("\"generation\": 9"))
        assertTrue(config.contains("\"home_as_back\": true"))
        assertTrue(config.contains("\"virtual_mouse\": false"))
        assertTrue(config.contains("\"bindings\": [[0, 304, 0, 0, 307, 0]]"))
        // The reload must never travel through PServer: that queue is serialized process-wide, and
        // keeping a toggle off it is the whole reason the fifo exists.
        assertTrue(shell.executed.isEmpty())
    }

    @Test
    fun appliedConfigGenerationReadsTheDaemonsAcknowledgement() {
        files.heartbeatFile.writeText("$now\ngeneration 12\n")
        files.heartbeatFile.setLastModified(now)

        assertEquals(12L, repository.appliedConfigGeneration())
    }

    @Test
    fun appliedConfigGenerationIsNullWhenTheDaemonNeverReportedOne() {
        // A daemon predating the ack: alive and beating, but with nothing to say about its config.
        files.heartbeatFile.writeText("$now\n")
        files.heartbeatFile.setLastModified(now)

        assertNull(repository.appliedConfigGeneration())
    }

    @Test
    fun healIsNoOpWhenNoHiddenStateFile() {
        repository.healOrphanedHideNodes()

        assertTrue(shell.executed.isEmpty())
    }

    @Test
    fun healIsNoOpWhenHiddenStateFileEmpty() {
        files.hiddenStateFile.writeText("")

        repository.healOrphanedHideNodes()

        assertTrue(shell.executed.isEmpty())
    }

    @Test
    fun healRunsDaemonHealModeWhenHiddenStateFilePresent() {
        files.hiddenStateFile.writeText("/dev/input/event10 13 74\n")

        repository.healOrphanedHideNodes()

        val command = shell.executed.single()
        assertTrue(command.contains(" --heal "))
        assertTrue(command.contains("--hidden-state-file '${files.hiddenStateFile.absolutePath}'"))
    }

    @Test
    fun startPassesHideNodeArgForEachHiddenNode() {
        repository.start(
            MirrorStartRequest(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                homeAsBack = false,
                comboHoldKillApp = false,
                hideNodes = listOf("/dev/input/event10", "/dev/input/event11"),
            )
        )

        val command = shell.launchedDaemons.single()
        assertTrue(command.contains("--hide-node '/dev/input/event10'"))
        assertTrue(command.contains("--hide-node '/dev/input/event11'"))
    }

    @Test
    fun startOmitsHideNodeArgWhenNoNodesToHide() {
        repository.start(
            MirrorStartRequest(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                homeAsBack = false,
                comboHoldKillApp = false,
            )
        )

        assertFalse(shell.launchedDaemons.single().contains("--hide-node"))
    }

    @Test
    fun startThrowsWhenLaunchCommandIsNotDelivered() {
        shell.deliver = false

        assertThrows(IllegalStateException::class.java) {
            repository.start(
                MirrorStartRequest(
                    source = "/dev/input/event9",
                    target = "/dev/input/event2",
                    homeAsBack = false,
                    comboHoldKillApp = false,
                )
            )
        }
    }

    @Test
    fun stopDeliversStopCommand() {
        repository.stop()

        assertEquals(listOf(STOP_MIRROR_COMMAND), shell.executed)
    }

    @Test
    fun stopThrowsWhenStopCommandIsNotDelivered() {
        shell.deliver = false

        assertThrows(IllegalStateException::class.java) { repository.stop() }
    }

    // -- helpers -------------------------------------------------------------------

    private fun writeHeartbeat(ageMs: Long) {
        files.heartbeatFile.writeText("$now\n")
        files.heartbeatFile.setLastModified(now - ageMs)
    }

    private fun writeProcStat(pid: Int, state: String) {
        val procDir = File(procRoot, pid.toString())
        procDir.mkdirs()
        File(procDir, "stat").writeText("$pid (input_mirror) $state 1 $pid $pid 0 -1\n")
    }

    private companion object {
        val FAKE_BINARY = "fake-binary".toByteArray()
    }
}

private class FakeSettingsRepository(
    var state: MirrorSettings = MirrorSettings(),
) : MirrorSettingsRepository {
    override fun getSettings(): MirrorSettings = state

    override fun bumpConfigGeneration() {
        state = state.copy(configGeneration = state.configGeneration + 1)
    }

    override fun saveStarted(request: MirrorStartRequest, startedAt: Long) {
        state = state.copy(
            source = request.source,
            target = request.target,
            sourceGuid = request.sourceGuid,
            targetGuid = request.targetGuid,
            expectedRunning = true,
            startedAt = startedAt,
        )
    }

    override fun setExpectedRunning(expectedRunning: Boolean) {
        state = state.copy(expectedRunning = expectedRunning)
    }

    override fun setHomeAsBackEnabled(enabled: Boolean) {
        state = state.copy(homeAsBack = enabled)
    }

    override fun setComboHoldKillAppEnabled(enabled: Boolean) {
        state = state.copy(comboHoldKillApp = enabled)
    }

    override fun setVirtualMouseEnabled(enabled: Boolean) {
        state = state.copy(virtualMouse = enabled)
    }

    override fun setAutoMirrorEnabled(enabled: Boolean) {
        state = state.copy(autoMirrorEnabled = enabled)
    }

    override fun setManualInternalController(guid: String?) {
        state = state.copy(manualInternalGuid = guid)
    }

    override fun setManualExternalController(guid: String?) {
        state = state.copy(manualExternalGuid = guid)
    }
}

private class RecordingShell : MirrorShell {
    var deliver = true
    val executed = mutableListOf<String>()
    val launchedDaemons = mutableListOf<String>()

    override val isAvailable: Boolean = true

    override fun exec(command: String): Boolean {
        executed += command
        return deliver
    }

    override fun read(command: String): String = ""

    override fun launchDaemon(command: String): Boolean {
        launchedDaemons += command
        return deliver
    }
}
