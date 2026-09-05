package app.xperia.patches.sony.storage

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/xperia/extension/sony/camera/XperiaStorage;"

/** Platform call → wrapper (same argument registers, static). */
private data class Rewrite(val definingClass: String, val name: String, val replacement: String)

private val rewrites = listOf(
    Rewrite(
        "Landroid/content/Context;", "getExternalFilesDirs",
        "$EXTENSION_CLASS->externalFilesDirs(Landroid/content/Context;Ljava/lang/String;)[Ljava/io/File;",
    ),
    Rewrite(
        "Landroid/os/storage/StorageManager;", "getStorageVolume",
        "$EXTENSION_CLASS->storageVolume(Landroid/os/storage/StorageManager;Ljava/io/File;)Landroid/os/storage/StorageVolume;",
    ),
    Rewrite(
        "Landroid/os/Environment;", "isExternalStorageRemovable",
        "$EXTENSION_CLASS->isExternalStorageRemovable(Ljava/io/File;)Z",
    ),
)

/**
 * Both Sony camera apps evaluate storage in a class named StorageUtil (com.sonymobile.photopro.storage /
 * jp.co.sony.mc.camera.storage). Every platform call there is redirected to XperiaStorage.
 */
@Suppress("unused")
val storageFallbackPatch = bytecodePatch(
    name = "Storage fallback",
    description = "Fixes \"Memory unavailable\" on ROMs that cannot create Android/data/<pkg>/files " +
            "(LineageOS on the Xperia 1 V): the storage probe falls back to the app's internal storage. " +
            "Photos still go to DCIM through MediaStore.",
) {
    compatibleWith("jp.co.sony.mc.cameraapp", "com.sonymobile.photopro")

    extendWith("extensions/sony-camera.mpe")

    execute {
        val storageUtil = mutableClassDefBy { classDef -> classDef.type.endsWith("/storage/StorageUtil;") }

        var rewritten = 0
        storageUtil.methods.forEach { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@forEach
            instructions.forEachIndexed { index, instruction ->
                if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_STATIC) return@forEachIndexed
                val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@forEachIndexed
                val rewrite = rewrites.firstOrNull {
                    it.definingClass == reference.definingClass && it.name == reference.name
                } ?: return@forEachIndexed
                val registers = instruction as FiveRegisterInstruction
                val args = (0 until registers.registerCount).map { i ->
                    "v" + when (i) {
                        0 -> registers.registerC
                        1 -> registers.registerD
                        2 -> registers.registerE
                        3 -> registers.registerF
                        else -> registers.registerG
                    }
                }.joinToString(", ")
                method.replaceInstruction(index, "invoke-static { $args }, ${rewrite.replacement}")
                rewritten++
            }
        }
        if (rewritten == 0) throw PatchException("No StorageUtil platform calls found to rewrite")
    }
}
