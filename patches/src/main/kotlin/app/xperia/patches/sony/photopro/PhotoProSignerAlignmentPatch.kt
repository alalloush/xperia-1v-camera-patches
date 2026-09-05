package app.xperia.patches.sony.photopro

import app.morphe.patcher.patch.bytecodePatch

/**
 * Photo Pro needs no code changes on the Xperia 1 V. It only has to be signed with the same key as the
 * patched camera app: both declare com.sonymobile.permission.SOMC_CAMERA, and Android refuses to install
 * the second app unless the signers match (INSTALL_FAILED_DUPLICATE_PERMISSION). Applying this (empty)
 * patch makes Morphe re-sign Photo Pro with its key.
 */
@Suppress("unused")
val photoProSignerAlignmentPatch = bytecodePatch(
    name = "Photo Pro signer alignment",
    description = "No code changes. Re-signs Photo Pro with the Morphe key so it can be installed next to " +
            "the patched Xperia 1 VI camera app (both declare SOMC_CAMERA and must share a signer). " +
            "The Android/data/com.sonymobile.photopro/files/DCIM dir must still be provisioned by root.",
) {
    compatibleWith("com.sonymobile.photopro")

    execute {
        // Intentionally empty.
    }
}
