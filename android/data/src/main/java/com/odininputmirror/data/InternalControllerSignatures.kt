package com.odininputmirror.data

/**
 * Identifies a handheld's built-in controller by its USB id. Devices whose internal pad matches
 * a known signature are auto-detected; unknown devices fall back to manual user selection.
 *
 * @param vendorIds vendor ids the internal controller reports.
 * @param productIds product ids the internal controller reports (across its modes, if any).
 * @param hasVendorMirroringQuirk true when the OS re-exposes connected external controllers as
 *   virtual nodes carrying this vendor id (the Odin does this), which requires twin-name
 *   filtering to tell a genuine internal pad from a mirrored external one.
 */
internal data class InternalControllerSignature(
    val vendorIds: Set<Int>,
    val productIds: Set<Int>,
    val hasVendorMirroringQuirk: Boolean,
) {
    fun matches(vendorId: Int, productId: Int): Boolean =
        vendorId in vendorIds && productId in productIds
}

internal val ODIN_INTERNAL_CONTROLLER_SIGNATURE = InternalControllerSignature(
    vendorIds = setOf(0x2020),
    productIds = setOf(0x0111, 0x0112),
    hasVendorMirroringQuirk = true,
)

/**
 * Retroid Pocket 5. The built-in pad reports itself as "Xbox Wireless Controller" on 2022:3002.
 *
 * Like the Odin, the firmware republishes every external pad under its own vendor (as 2022:3001)
 * and deletes the original node. It also ships "Retroid Pocket Virtual Mouse" on that very same
 * 2022:3001. Unless the vendor is registered as a mirroring quirk, the external pad's twin gets
 * matched by vendor:product, and the virtual mouse (lower event number) wins the tie and is mirrored
 * in place of the pad. As a quirk vendor the twin is matched by name, like on the Odin.
 */
internal val RETROID_POCKET_5_INTERNAL_CONTROLLER_SIGNATURE = InternalControllerSignature(
    vendorIds = setOf(0x2022),
    productIds = setOf(0x3002),
    hasVendorMirroringQuirk = true,
)

/** Registry of recognised handheld internal controllers. Add an entry to support a new device. */
internal val KNOWN_INTERNAL_CONTROLLER_SIGNATURES: List<InternalControllerSignature> = listOf(
    ODIN_INTERNAL_CONTROLLER_SIGNATURE,
    RETROID_POCKET_5_INTERNAL_CONTROLLER_SIGNATURE,
)

internal fun matchedInternalSignature(vendorId: Int, productId: Int): InternalControllerSignature? =
    KNOWN_INTERNAL_CONTROLLER_SIGNATURES.firstOrNull { it.matches(vendorId, productId) }

/** Vendor ids belonging to a signature that re-exposes external controllers (mirroring quirk). */
internal val MIRRORING_QUIRK_VENDOR_IDS: Set<Int> =
    KNOWN_INTERNAL_CONTROLLER_SIGNATURES
        .filter { it.hasVendorMirroringQuirk }
        .flatMap { it.vendorIds }
        .toSet()
