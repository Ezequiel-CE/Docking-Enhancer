package com.odininputmirror.domain.usecase

import com.odininputmirror.domain.model.CaptureKind
import com.odininputmirror.domain.model.CaptureRead
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.ControllerMapping
import com.odininputmirror.domain.model.MirrorSettings
import com.odininputmirror.domain.model.MirrorStartRequest
import com.odininputmirror.domain.model.findSavedControllerDevice
import com.odininputmirror.domain.repository.DockStateRepository
import com.odininputmirror.domain.repository.MirrorProcessRepository
import com.odininputmirror.domain.repository.MirrorSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorUseCaseTest {
    @Test
    fun startMirrorStartsProcessThenPersistsFullRequest() {
        val process = FakeMirrorProcessRepository()
        val settings = FakeMirrorSettingsRepository()
        val request = mirrorRequest(
            source = "/dev/input/event9",
            target = "/dev/input/event2",
            sourceGuid = "external-guid",
            targetGuid = "local-guid",
            homeAsBack = true,
            comboHoldKillApp = true,
        )

        StartMirrorUseCase(process, settings, nowMillis = { 1234L })(request)

        assertEquals(listOf(request), process.startRequests)
        assertEquals(
            MirrorSettings(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                sourceGuid = "external-guid",
                targetGuid = "local-guid",
                homeAsBack = true,
                comboHoldKillApp = true,
                expectedRunning = true,
                startedAt = 1234L,
            ),
            settings.state,
        )
    }

    @Test
    fun stopMirrorStopsProcessClearsFilesAndMarksMirrorAsNotExpected() {
        val process = FakeMirrorProcessRepository()
        val settings = FakeMirrorSettingsRepository(
            MirrorSettings(source = "/dev/input/event9", target = "/dev/input/event2", expectedRunning = true),
        )

        StopMirrorUseCase(process, settings)()

        assertEquals(1, process.stopCount)
        assertEquals(1, process.clearProcessFilesCount)
        assertFalse(settings.state.expectedRunning)
    }

    @Test
    fun stopMirrorStillClearsFilesAndMarksNotExpectedWhenStopFails() {
        val process = FakeMirrorProcessRepository(throwOnStop = true)
        val settings = FakeMirrorSettingsRepository(
            MirrorSettings(source = "/dev/input/event9", target = "/dev/input/event2", expectedRunning = true),
        )

        runCatching { StopMirrorUseCase(process, settings)() }

        assertEquals(1, process.stopCount)
        assertEquals(1, process.clearProcessFilesCount)
        assertFalse(settings.state.expectedRunning)
    }

    @Test
    fun getMirrorStatusReturnsRunningCheckAndPersistedSettings() {
        val process = FakeMirrorProcessRepository(running = true)
        val settings = FakeMirrorSettingsRepository(
            MirrorSettings(
                source = "/dev/input/event9",
                target = "/dev/input/event2",
                sourceGuid = "external-guid",
                targetGuid = "local-guid",
                homeAsBack = true,
                comboHoldKillApp = false,
                expectedRunning = true,
            ),
        )

        val status = GetMirrorStatusUseCase(process, settings, FakeDockStateRepository(docked = true))()

        assertTrue(status.running)
        assertTrue(status.expectedRunning)
        assertEquals("/dev/input/event9", status.source)
        assertEquals("/dev/input/event2", status.target)
        assertEquals("external-guid", status.sourceGuid)
        assertEquals("local-guid", status.targetGuid)
        assertTrue(status.homeAsBack)
        assertFalse(status.comboHoldKillApp)
        assertTrue(status.docked)
        assertEquals(1, process.isRunningCount)
    }

    @Test
    fun findSavedDevicePrefersGuidBeforePath() {
        val devices = listOf(
            externalController(path = "/dev/input/event9", guid = "old-path-device"),
            externalController(path = "/dev/input/event11", guid = "external-guid"),
        )

        val found = devices.findSavedControllerDevice(
            path = "/dev/input/event9",
            guid = "external-guid",
        )

        assertEquals("/dev/input/event11", found?.path)
    }

    @Test
    fun findSavedDeviceFallsBackToPathWhenGuidIsMissingOrUnknown() {
        val devices = listOf(externalController(path = "/dev/input/event9", guid = "external-guid"))

        val foundWithoutGuid = devices.findSavedControllerDevice(
            path = "/dev/input/event9",
            guid = null,
        )
        val foundWithUnknownGuid = devices.findSavedControllerDevice(
            path = "/dev/input/event9",
            guid = "unknown-guid",
        )

        assertEquals("/dev/input/event9", foundWithoutGuid?.path)
        assertEquals("/dev/input/event9", foundWithUnknownGuid?.path)
    }

    @Test
    fun findSavedDeviceReturnsNullWithoutUsableIdentity() {
        val devices = listOf(externalController())

        assertNull(devices.findSavedControllerDevice(path = null, guid = null))
        assertNull(devices.findSavedControllerDevice(path = "/dev/input/missing", guid = "missing"))
    }

    @Test
    fun setMirrorOptionsPersistIndividualFlagsWithoutChangingOtherSettings() {
        val settings = FakeMirrorSettingsRepository(
            MirrorSettings(source = "/dev/input/event9", target = "/dev/input/event2", expectedRunning = true),
        )

        SetHomeAsBackEnabledUseCase(settings)(true)
        SetComboHoldKillAppEnabledUseCase(settings)(true)
        SetVirtualMouseEnabledUseCase(settings)(true)

        assertTrue(settings.state.homeAsBack)
        assertTrue(settings.state.comboHoldKillApp)
        assertTrue(settings.state.virtualMouse)
        assertTrue(settings.state.expectedRunning)
        assertEquals("/dev/input/event9", settings.state.source)
        assertEquals("/dev/input/event2", settings.state.target)
    }

    @Test
    fun startMirrorPropagatesVirtualMouseFlagAndPersistsIt() {
        val process = FakeMirrorProcessRepository()
        val settings = FakeMirrorSettingsRepository()
        val request = mirrorRequest(virtualMouse = true)

        StartMirrorUseCase(process, settings, nowMillis = { 1L })(request)

        assertTrue(process.startRequests.single().virtualMouse)
        assertTrue(settings.state.virtualMouse)
    }

    @Test
    fun getMirrorStatusReflectsVirtualMouseSetting() {
        val process = FakeMirrorProcessRepository(running = true)
        val settings = FakeMirrorSettingsRepository(MirrorSettings(virtualMouse = true))

        val status = GetMirrorStatusUseCase(process, settings, FakeDockStateRepository())()

        assertTrue(status.virtualMouse)
    }

    @Test
    fun autoMirrorPropagatesVirtualMouseFlagIntoStartRequest() {
        val firstExternal = externalController(path = "/dev/input/event9", guid = "first-external")
        val local = localController(path = "/dev/input/event2", guid = "odin-internal")

        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(local, firstExternal),
            settings = MirrorSettings(virtualMouse = true),
            mirrorRunning = false,
        )

        assertEquals(
            AutoMirrorDecision.Start(
                mirrorRequest(
                    source = "/dev/input/event9",
                    target = "/dev/input/event2",
                    sourceGuid = "first-external",
                    targetGuid = "odin-internal",
                    virtualMouse = true,
                )
            ),
            decision,
        )
    }

    @Test
    fun autoMirrorStopsWhenDockIsInactive() {
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = false,
            devices = reconnectedControllers(),
            settings = MirrorSettings(),
            mirrorRunning = true,
        )

        assertEquals(AutoMirrorDecision.StopForDock, decision)
    }

    @Test
    fun autoMirrorWaitsForInternalController() {
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(externalController()),
            settings = MirrorSettings(),
            mirrorRunning = false,
        )

        assertEquals(AutoMirrorDecision.WaitingForInternalController, decision)
    }

    @Test
    fun autoMirrorPrefersTheManuallyPickedExternalController() {
        val virtualMouse = externalController(path = "/dev/input/event5", guid = "virtual-mouse", controllerNumber = 2)
        val realPad = externalController(path = "/dev/input/event9", guid = "real-pad", controllerNumber = 3)

        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(localController(), virtualMouse, realPad),
            settings = MirrorSettings(manualExternalGuid = "real-pad"),
            mirrorRunning = false,
        )

        assertEquals(
            AutoMirrorDecision.Start(mirrorRequest(source = "/dev/input/event9", sourceGuid = "real-pad")),
            decision,
        )
    }

    @Test
    fun autoMirrorWaitsWhileThePickedExternalIsAbsentInsteadOfFallingBack() {
        val virtualMouse = externalController(path = "/dev/input/event5", guid = "virtual-mouse")

        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(localController(), virtualMouse),
            settings = MirrorSettings(manualExternalGuid = "real-pad"),
            mirrorRunning = false,
        )

        assertEquals(AutoMirrorDecision.WaitingForExternalController, decision)
    }

    @Test
    fun autoMirrorIgnoresAManualExternalPickThatIsTheInternalController() {
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(localController(), externalController()),
            settings = MirrorSettings(manualExternalGuid = "local-guid"),
            mirrorRunning = false,
        )

        assertEquals(AutoMirrorDecision.WaitingForExternalController, decision)
    }

    @Test
    fun autoMirrorWaitsForExternalController() {
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(localController()),
            settings = MirrorSettings(),
            mirrorRunning = false,
        )

        assertEquals(AutoMirrorDecision.WaitingForExternalController, decision)
    }

    @Test
    fun autoMirrorIgnoresDuplicateInternalControllerEventAsExternal() {
        val local = localController(controllerNumber = 1)
        val duplicateInternalEvent = externalController(
            path = "/dev/input/event8",
            guid = "odin-mode-secondary-node",
            controllerNumber = 1,
        )

        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(local, duplicateInternalEvent),
            settings = MirrorSettings(),
            mirrorRunning = false,
        )

        assertEquals(AutoMirrorDecision.WaitingForExternalController, decision)
    }

    @Test
    fun autoMirrorStartsWithInternalTargetAndFirstExternalSource() {
        val firstExternal = externalController(path = "/dev/input/event9", guid = "first-external")
        val secondExternal = externalController(path = "/dev/input/event10", guid = "second-external")
        val local = localController(path = "/dev/input/event2", guid = "odin-internal")

        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(local, firstExternal, secondExternal),
            settings = MirrorSettings(homeAsBack = true, comboHoldKillApp = true),
            mirrorRunning = false,
        )

        assertEquals(
            AutoMirrorDecision.Start(
                mirrorRequest(
                    source = "/dev/input/event9",
                    target = "/dev/input/event2",
                    sourceGuid = "first-external",
                    targetGuid = "odin-internal",
                    homeAsBack = true,
                    comboHoldKillApp = true,
                )
            ),
            decision,
        )
    }

    @Test
    fun autoMirrorKeepsRunningWhenSelectedDevicesMatch() {
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = reconnectedControllers(),
            settings = restartableSettings().copy(
                source = "/dev/input/event11",
                target = "/dev/input/event4",
            ),
            mirrorRunning = true,
        )

        assertEquals(AutoMirrorDecision.Running, decision)
    }

    @Test
    fun autoMirrorAlwaysPassesTheExternalsHideNode() {
        // Hiding the external is the default behaviour, not a toggle.
        val external = externalController(
            path = "/dev/input/event9",
            guid = "ext",
            hideNodePath = "/dev/input/event10",
        )
        val local = localController(path = "/dev/input/event2", guid = "odin")

        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(local, external),
            settings = MirrorSettings(),
            mirrorRunning = false,
        )

        assertEquals(listOf("/dev/input/event10"), (decision as AutoMirrorDecision.Start).request.hideNodes)
    }

    @Test
    fun autoMirrorHideNodesEmptyWhenExternalHasNoHideNode() {
        val external = externalController(path = "/dev/input/event9", guid = "ext", hideNodePath = null)
        val local = localController(path = "/dev/input/event2", guid = "odin")

        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(local, external),
            settings = MirrorSettings(),
            mirrorRunning = false,
        )

        assertTrue((decision as AutoMirrorDecision.Start).request.hideNodes.isEmpty())
    }

    @Test
    fun autoMirrorPushesSettingsInsteadOfRestartingWhenOptionsChanged() {
        // The user toggled an option, so the settings have moved to a newer generation than the one
        // the running daemon reports. Same devices, still running: it needs the new config handed
        // to it, NOT a restart — a restart would release the grab and flash the pad visible.
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = reconnectedControllers(),
            settings = restartableSettings().copy(
                source = "/dev/input/event11",
                target = "/dev/input/event4",
                virtualMouse = true,
                configGeneration = 5L,
            ),
            mirrorRunning = true,
            appliedGeneration = 4L,
        )

        assertEquals(AutoMirrorDecision.ApplyLiveSettings, decision)
    }

    @Test
    fun autoMirrorIsRunningOnceTheDaemonAcknowledgesTheGeneration() {
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = reconnectedControllers(),
            settings = restartableSettings().copy(
                source = "/dev/input/event11",
                target = "/dev/input/event4",
                configGeneration = 5L,
            ),
            mirrorRunning = true,
            appliedGeneration = 5L,
        )

        assertEquals(AutoMirrorDecision.Running, decision)
    }

    @Test
    fun autoMirrorLeavesADaemonAloneUntilItReportsAGeneration() {
        // A daemon that has not answered yet is not a stale one. Treating silence as staleness would
        // push settings at it on every tick of its startup window.
        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = reconnectedControllers(),
            settings = restartableSettings().copy(
                source = "/dev/input/event11",
                target = "/dev/input/event4",
                virtualMouse = true,
                configGeneration = 5L,
            ),
            mirrorRunning = true,
            appliedGeneration = null,
        )

        assertEquals(AutoMirrorDecision.Running, decision)
    }

    @Test
    fun autoMirrorRestartsWhenExternalControllerChanges() {
        val newExternal = externalController(path = "/dev/input/event12", guid = "new-external")
        val local = localController(path = "/dev/input/event4", guid = "local-guid")

        val decision = ResolveAutoMirrorDecisionUseCase()(
            dockActive = true,
            devices = listOf(local, newExternal),
            settings = restartableSettings().copy(
                source = "/dev/input/event11",
                target = "/dev/input/event4",
            ),
            mirrorRunning = true,
        )

        assertEquals(
            AutoMirrorDecision.Restart(
                mirrorRequest(
                    source = "/dev/input/event12",
                    target = "/dev/input/event4",
                    sourceGuid = "new-external",
                    targetGuid = "local-guid",
                    homeAsBack = true,
                    comboHoldKillApp = true,
                )
            ),
            decision,
        )
    }

    private fun restartableSettings() = MirrorSettings(
        source = "/dev/input/event9",
        target = "/dev/input/event2",
        sourceGuid = "external-guid",
        targetGuid = "local-guid",
        homeAsBack = true,
        comboHoldKillApp = true,
        expectedRunning = true,
    )

    private fun reconnectedControllers() = listOf(
        externalController(path = "/dev/input/event11", guid = "external-guid"),
        localController(path = "/dev/input/event4", guid = "local-guid"),
    )

    private fun mirrorRequest(
        source: String = "/dev/input/event9",
        target: String = "/dev/input/event2",
        sourceGuid: String? = "external-guid",
        targetGuid: String? = "local-guid",
        homeAsBack: Boolean = false,
        comboHoldKillApp: Boolean = false,
        virtualMouse: Boolean = false,
    ) = MirrorStartRequest(
        source = source,
        target = target,
        sourceGuid = sourceGuid,
        targetGuid = targetGuid,
        homeAsBack = homeAsBack,
        comboHoldKillApp = comboHoldKillApp,
        virtualMouse = virtualMouse,
    )

    private fun externalController(
        path: String = "/dev/input/event9",
        guid: String = "external-guid",
        controllerNumber: Int = 2,
        hideNodePath: String? = null,
    ) = ControllerDevice(
        name = "External Controller",
        path = path,
        guid = guid,
        controllerNumber = controllerNumber,
        handlers = listOf("event${path.substringAfterLast("event")}"),
        hideNodePath = hideNodePath,
    )

    private fun localController(
        path: String = "/dev/input/event2",
        guid: String = "local-guid",
        controllerNumber: Int = 1,
    ) = ControllerDevice(
        name = "Odin Controller",
        path = path,
        guid = guid,
        controllerNumber = controllerNumber,
        handlers = listOf("event${path.substringAfterLast("event")}"),
        isInternal = true,
    )
}

private class FakeMirrorProcessRepository(
    private val running: Boolean = false,
    private val throwOnStop: Boolean = false,
) : MirrorProcessRepository {
    val startRequests = mutableListOf<MirrorStartRequest>()
    var stopCount = 0
    var isRunningCount = 0
    var clearProcessFilesCount = 0

    override fun start(request: MirrorStartRequest) {
        startRequests += request
    }

    override fun stop() {
        stopCount += 1
        if (throwOnStop) {
            throw IllegalStateException("Stop failed")
        }
    }

    override fun isRunning(): Boolean {
        isRunningCount += 1
        return running
    }

    override fun clearProcessFiles() {
        clearProcessFilesCount += 1
    }

    override fun healOrphanedHideNodes() {}

    val liveSettingsPushes = mutableListOf<MirrorSettings>()
    var reloadDelivered = true
    var reportedGeneration: Long? = null

    override fun applyLiveSettings(settings: MirrorSettings, mapping: ControllerMapping): Boolean {
        liveSettingsPushes += settings
        return reloadDelivered
    }

    override fun appliedConfigGeneration(): Long? = reportedGeneration

    val captureCommands = mutableListOf<String>()

    override fun beginCapture(kind: CaptureKind): Boolean {
        captureCommands += "begin:$kind"
        return true
    }

    override fun endCapture(): Boolean {
        captureCommands += "end"
        return true
    }

    override fun readCaptures(offset: Long): CaptureRead = CaptureRead(offset = offset)

    override fun clearCaptures() {
        captureCommands += "clear"
    }
}

private class FakeMirrorSettingsRepository(
    initialState: MirrorSettings = MirrorSettings(),
) : MirrorSettingsRepository {
    var state = initialState

    override fun getSettings(): MirrorSettings = state

    override fun saveStarted(request: MirrorStartRequest, startedAt: Long) {
        state = state.copy(
            source = request.source,
            target = request.target,
            sourceGuid = request.sourceGuid,
            targetGuid = request.targetGuid,
            homeAsBack = request.homeAsBack,
            comboHoldKillApp = request.comboHoldKillApp,
            virtualMouse = request.virtualMouse,
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

    override fun bumpConfigGeneration() {
        state = state.copy(configGeneration = state.configGeneration + 1)
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

private class FakeDockStateRepository(
    private val docked: Boolean = false,
) : DockStateRepository {
    override fun isDockActive(): Boolean = docked
}
