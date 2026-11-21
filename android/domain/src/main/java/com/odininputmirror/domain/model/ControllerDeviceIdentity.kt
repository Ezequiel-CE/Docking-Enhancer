package com.odininputmirror.domain.model

fun List<ControllerDevice>.findSavedControllerDevice(
    path: String?,
    guid: String?,
): ControllerDevice? {
    if (!guid.isNullOrBlank()) {
        find { it.guid == guid }?.let { return it }
    }

    if (!path.isNullOrBlank()) {
        find { it.path == path }?.let { return it }
    }

    return null
}

/**
 * The controller the mirror reads from, given which one is [local] (the built-in pad).
 *
 * Without a manual pick it is the first other controller. With one, it is that controller or
 * nothing: while the picked pad is absent the mirror waits rather than falling back. Falling back is
 * exactly how a firmware's own gamepad-shaped device (the Retroid Pocket's virtual mouse) ends up
 * grabbed and mirrored, which is what the manual pick exists to prevent.
 *
 * The pick is matched on [manualName] as well as [manualGuid] whenever a name was saved: the guid is
 * only vendor:product, and on the RP5 the virtual mouse carries the very id every republished pad
 * does. Matching the guid alone would hand the mouse back to a user who picked their pad.
 */
fun List<ControllerDevice>.selectExternalController(
    local: ControllerDevice?,
    manualGuid: String?,
    manualName: String? = null,
): ControllerDevice? {
    val candidates = filter { device ->
        !device.isInternal &&
            device.path != local?.path &&
            !(local != null && device.isSamePhysicalControllerAs(local))
    }
    if (manualGuid.isNullOrBlank()) {
        return candidates.firstOrNull()
    }
    return candidates.firstOrNull { it.matchesManualPick(manualGuid, manualName) }
}

/** Whether this is the controller a manual pick of [guid] and [name] refers to. */
fun ControllerDevice.matchesManualPick(guid: String?, name: String?): Boolean {
    if (guid.isNullOrBlank() || this.guid != guid) {
        return false
    }
    return name.isNullOrBlank() || this.name.equals(name, ignoreCase = true)
}

private fun ControllerDevice.isSamePhysicalControllerAs(other: ControllerDevice): Boolean {
    return controllerNumber > 0 && controllerNumber == other.controllerNumber
}
