package com.odininputmirror.data

import android.view.InputDevice
import com.odininputmirror.domain.model.ControllerDevice
import com.odininputmirror.domain.model.MappingKey
import com.odininputmirror.domain.model.TargetTraits
import com.odininputmirror.domain.repository.InputDeviceRepository
import java.io.File

internal class AndroidInputDeviceRepository(
    private val shell: MirrorShell = UnavailableShell,
    private val pathExists: (String) -> Boolean = { File(it).exists() },
    private val manualInternalGuidProvider: () -> String? = { null },
    // Path of the mirror source we may have hidden (its /dev node unlinked). Lets us re-materialise
    // it from /proc so the UI and auto-mirror logic still see the controller being mirrored.
    private val hiddenSourcePathProvider: () -> String? = { null },
    // True only while the mirror is actually running. Gates re-materialisation so a STALE persisted
    // source (from a prior session, e.g. a real node that never has a /dev entry) is never revived
    // when nothing is running — which would otherwise block restarting on the live node.
    private val mirrorRunningProvider: () -> Boolean = { false },
    private val keyLayouts: KeyLayoutFiles = KeyLayoutFiles(),
) : InputDeviceRepository {
    /**
     * What the internal pad can actually receive, straight from the running system: its capability
     * bitmasks out of /proc, and its Android key layout for what the codes mean. Built fresh per
     * call — the editor opens rarely, and a cached answer would survive a controller swap.
     */
    override fun targetTraits(): TargetTraits? {
        val procEntries = readProcEntries()
        val internal = getConnectedControllers().firstOrNull { it.isInternal } ?: return null
        val entry = procEntries.firstOrNull { it.path == internal.path } ?: return null
        if (entry.keyBits.isEmpty() && entry.absBits.isEmpty()) {
            return null
        }
        val layout = keyLayouts.resolve(entry.vendorId, entry.productId)
        return TargetTraits(
            keys = entry.keyBits,
            axes = entry.absBits,
            keyLabels = layout.keyLabels,
            axisLabels = layout.axisLabels,
        )
    }

    // The real pad keeps its /proc entry — bitmasks included — even while its /dev node is hidden,
    // which is exactly when seeding runs.
    override fun sourceTraits(key: MappingKey): TargetTraits? {
        val entry = readProcEntries().firstOrNull {
            it.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                it.vendorId != 0 &&
                MappingKey.of(it.vendorId, it.productId) == key
        } ?: return null
        if (entry.keyBits.isEmpty() && entry.absBits.isEmpty()) {
            return null
        }
        return TargetTraits(keys = entry.keyBits, axes = entry.absBits)
    }

    private fun readProcEntries(): List<ProcInputEntry> =
        shell.read("cat /proc/bus/input/devices")
            .split(Regex("\\n\\s*\\n"))
            .mapNotNull { parseProcInputBlock(it) }

    override fun getConnectedControllers(): List<ControllerDevice> {
        val procEntries = readProcEntries()

        val mirroredNames = computeMirroredNames(procEntries)

        val framework = InputDevice.getDeviceIds()
            .asSequence()
            .mapNotNull { id -> InputDevice.getDevice(id) }
            .filter { device -> isPhysicalGameController(device) }
            .map { it.toCandidate() }
            .mapNotNull { candidate -> resolveControllerDevice(candidate, procEntries, mirroredNames) }
            .distinctBy { it.path }
            .toList()

        val devices = (framework + hiddenSourceDevice(framework, procEntries))
            .sortedBy { it.controllerNumber }

        return resolveInternalController(devices, manualInternalGuidProvider())
    }

    // When the hide-external feature has unlinked the mirror source's /dev node, the framework drops
    // it from enumeration but its /proc entry persists. Rebuild a ControllerDevice from that entry so
    // the external is still shown and still treated as present (it hides itself, so hideNodePath is
    // its own path). Returns empty when the source isn't hidden (present in /dev, already resolved)
    // or its /proc entry is gone (controller actually disconnected).
    internal fun hiddenSourceDevice(
        resolved: List<ControllerDevice>,
        procEntries: List<ProcInputEntry>,
    ): List<ControllerDevice> {
        if (!mirrorRunningProvider()) {
            return emptyList()
        }
        val hiddenSource = hiddenSourcePathProvider() ?: return emptyList()
        if (resolved.any { it.path == hiddenSource } || pathExists(hiddenSource)) {
            return emptyList()
        }
        val entry = procEntries.firstOrNull { it.path == hiddenSource } ?: return emptyList()
        return listOf(
            ControllerDevice(
                name = entry.name,
                path = entry.path,
                guid = guidOf(entry.vendorId, entry.productId),
                controllerNumber = 0,
                handlers = entry.handlers,
                isInternal = false,
                isKnownInternal = false,
                hideNodePath = entry.path,
                // The mirror is running, so this is the path the wizard sees the pad through.
                mappingKey = resolveMappingKey(entry.name, procEntries),
            ),
        )
    }

    // Decides which controller is the internal one:
    //  1. The user's manual pick wins, if its controller is connected — even over a recognised
    //     signature, so the auto-detection can always be overridden just in case.
    //  2. Otherwise a recognised handheld signature (Odin).
    //  3. Otherwise the first detected controller, assumed to be the always-attached built-in.
    internal fun resolveInternalController(
        devices: List<ControllerDevice>,
        manualGuid: String?,
    ): List<ControllerDevice> {
        if (devices.isEmpty()) {
            return devices
        }

        val manualMatch = manualGuid?.let { guid -> devices.firstOrNull { it.guid == guid } }
        if (manualMatch != null) {
            return devices.map { device -> device.copy(isInternal = device.path == manualMatch.path) }
        }

        if (devices.any { it.isKnownInternal }) {
            return devices
        }

        val first = devices.first()
        return devices.map { device ->
            if (device.path == first.path) device.copy(isInternal = true) else device
        }
    }

    // Some handhelds (e.g. the Odin) re-expose any active controller as a virtual HID node
    // carrying the handheld's own vendor id, for game-compatibility reasons. Such a mirrored
    // node always has a "twin" proc entry with the same name on a real, non-quirk vendor id
    // (e.g. the actual Bluetooth identity). The genuine internal controller has no such twin,
    // so this distinguishes it from a mirrored external controller. Only relevant when a
    // mirroring-quirk device is actually present; otherwise there is nothing to disambiguate.
    internal fun computeMirroredNames(procEntries: List<ProcInputEntry>): Set<String> {
        val hasQuirkDevice = procEntries.any { it.vendorId in MIRRORING_QUIRK_VENDOR_IDS }
        if (!hasQuirkDevice) {
            return emptySet()
        }
        return procEntries
            .filter { it.vendorId !in MIRRORING_QUIRK_VENDOR_IDS }
            .map { it.name.lowercase() }
            .toSet()
    }

    /**
     * The identity a saved mapping is keyed on.
     *
     * The device we resolve for an external controller is the firmware's quirk twin, and every twin
     * carries the same vendor:product — so keying a mapping on it would hand the second pad the
     * first pad's mapping. The real controller keeps its own /proc entry under the same name (its
     * /dev node is gone, which is why it is filtered out everywhere else), and that entry has its
     * true vendor:product. Returns null when no such entry exists, which is the normal case for the
     * internal controller.
     */
    internal fun resolveMappingKey(name: String, entries: List<ProcInputEntry>): MappingKey? {
        val real = entries.firstOrNull { entry ->
            entry.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                entry.vendorId != 0 &&
                entry.name.lowercase() == name.lowercase()
        } ?: return null
        return MappingKey.of(real.vendorId, real.productId)
    }

    internal fun resolveControllerDevice(
        candidate: ControllerCandidate,
        entries: List<ProcInputEntry>,
        mirroredNames: Set<String>,
    ): ControllerDevice? {
        val normalizedName = candidate.name.lowercase()
        val candidateHasDistinctInternalId = matchedInternalSignature(candidate.vendorId, candidate.productId)
            ?.hasDistinctProductId == true
        val candidates = entries.filter { entry ->
            if (!pathExists(entry.path)) {
                return@filter false
            }

            val sameIds = entry.vendorId == candidate.vendorId && entry.productId == candidate.productId
            // A reserved built-in ID identifies its node even when an external pad shares its name.
            if (candidateHasDistinctInternalId || entry.internalSignature?.hasDistinctProductId == true) {
                return@filter sameIds
            }

            val sameVendorProduct =
                sameIds &&
                    candidate.vendorId != 0 &&
                    candidate.productId != 0 &&
                    candidate.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                    entry.vendorId !in MIRRORING_QUIRK_VENDOR_IDS
            val sameName = entry.name.lowercase() == normalizedName
            sameVendorProduct || sameName
        }

        return candidates
            .sortedWith(
                compareByDescending<ProcInputEntry> {
                    candidate.vendorId !in MIRRORING_QUIRK_VENDOR_IDS &&
                        it.vendorId == candidate.vendorId &&
                        it.productId == candidate.productId
                }
                    .thenByDescending { it.bus == BUS_BLUETOOTH }
                    .thenByDescending { it.bus == BUS_USB && it.vendorId !in MIRRORING_QUIRK_VENDOR_IDS }
                    .thenBy { it.internalSignature != null }
                    .thenBy { it.eventNumber }
            )
            .firstOrNull()
            ?.let { entry ->
                val signature = entry.internalSignature
                val isInternal = signature != null &&
                    (signature.hasDistinctProductId || entry.name.lowercase() !in mirroredNames)
                ControllerDevice(
                    name = entry.name,
                    path = entry.path,
                    guid = candidate.guid,
                    controllerNumber = candidate.controllerNumber,
                    handlers = entry.handlers,
                    isInternal = isInternal,
                    isKnownInternal = isInternal,
                    hideNodePath = if (isInternal) null else resolveHideNodePath(entry, normalizedName, entries),
                    // Only an external controller gets one: the internal pad IS the quirk vendor, so
                    // it has no separate real entry to point at, and it is never the one remapped.
                    mappingKey = if (isInternal) null else resolveMappingKey(entry.name, entries),
                )
            }
    }

    // Hide the firmware's copy: either the resolved source itself or its separate twin.
    private fun resolveHideNodePath(
        source: ProcInputEntry,
        normalizedName: String,
        entries: List<ProcInputEntry>,
    ): String? {
        if (source.vendorId in MIRRORING_QUIRK_VENDOR_IDS) {
            return source.path
        }
        return entries.firstOrNull { entry ->
            entry.vendorId in MIRRORING_QUIRK_VENDOR_IDS &&
                entry.internalSignature?.hasDistinctProductId != true &&
                entry.name.lowercase() == normalizedName &&
                pathExists(entry.path)
        }?.path
    }

    internal fun parseProcInputBlock(block: String): ProcInputEntry? {
        val identityMatch = Regex("I: Bus=([0-9A-Fa-f]+) Vendor=([0-9A-Fa-f]+) Product=([0-9A-Fa-f]+)")
            .find(block)
            ?: return null
        val name = Regex("N: Name=\"([^\"]+)\"").find(block)?.groupValues?.getOrNull(1) ?: return null
        val handlersLine = Regex("H: Handlers=(.+)").find(block)?.groupValues?.getOrNull(1) ?: return null
        val eventName = Regex("\\bevent\\d+\\b").find(handlersLine)?.value ?: return null

        return ProcInputEntry(
            name = name,
            handlers = handlersLine.split(Regex("\\s+")).filter { it.isNotBlank() },
            path = "/dev/input/$eventName",
            eventName = eventName,
            bus = identityMatch.groupValues[1].toInt(16),
            vendorId = identityMatch.groupValues[2].toInt(16),
            productId = identityMatch.groupValues[3].toInt(16),
            keyBits = capabilityBits(block, "KEY"),
            absBits = capabilityBits(block, "ABS"),
        )
    }

    private fun capabilityBits(block: String, kind: String): Set<Int> =
        Regex("B: $kind=(.+)").find(block)?.groupValues?.getOrNull(1)
            ?.let(::parseCapabilityBits)
            ?: emptySet()

    private fun isPhysicalGameController(device: InputDevice?): Boolean {
        device ?: return false
        if (device.isVirtual) return false

        val sources = device.sources
        val hasControllerSource =
            sources.hasSource(InputDevice.SOURCE_GAMEPAD) ||
                sources.hasSource(InputDevice.SOURCE_JOYSTICK)
        if (!hasControllerSource) return false

        val hasControllerButtons = device.hasKeys(*CONTROLLER_BUTTONS).any { it }
        val hasControllerAxes = device.motionRanges.any { range ->
            CONTROLLER_AXES.contains(range.axis)
        }
        return hasControllerButtons || hasControllerAxes
    }

    internal data class ProcInputEntry(
        val name: String,
        val handlers: List<String>,
        val path: String,
        val eventName: String,
        val bus: Int,
        val vendorId: Int,
        val productId: Int,
        val keyBits: Set<Int> = emptySet(),
        val absBits: Set<Int> = emptySet(),
    ) {
        val eventNumber: Int
            get() = eventName.removePrefix("event").toIntOrNull() ?: Int.MAX_VALUE

        val internalSignature: InternalControllerSignature?
            get() = matchedInternalSignature(vendorId, productId)
    }
}

internal data class ControllerCandidate(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val controllerNumber: Int,
    val guid: String,
)

private fun InputDevice.toCandidate(): ControllerCandidate = ControllerCandidate(
    name = name,
    vendorId = vendorId,
    productId = productId,
    controllerNumber = controllerNumber,
    guid = getGuid(),
)

private fun InputDevice.getGuid(): String = guidOf(vendorId, productId)

// Controller GUID from vendor + product only — matches InputDevice.getGuid() so a device
// re-materialised from /proc keeps the same identity the framework had assigned it.
internal fun guidOf(vendorId: Int, productId: Int): String = String.format("%016x%016x", productId, vendorId)

private fun Int.hasSource(source: Int): Boolean = this and source == source
