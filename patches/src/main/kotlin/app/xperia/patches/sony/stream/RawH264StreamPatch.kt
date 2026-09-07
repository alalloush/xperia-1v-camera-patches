package app.xperia.patches.sony.stream

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.xperia.patches.sony.SONY_CAMERA

private const val EXTENSION_CLASS = "Lapp/xperia/extension/sony/camera/RawStreamer;"
private const val MANAGER = "Ljp/co/sony/mc/camera/rtmp/RtmpManager;"
private const val BYTE_BUFFER = "Ljava/nio/ByteBuffer;"
private const val BUFFER_INFO = "Landroid/media/MediaCodec\$BufferInfo;"

/** RtmpManager method → (parameters, registers handed to the extension, extension method signature). */
private class Hook(val name: String, val parameters: List<String>, val args: String, val call: String)

private val hooks = listOf(
    Hook(
        "connect", listOf("I", "Z", "I", "I", "Ljava/lang/String;", "Ljava/lang/String;", "Z"),
        "p0, p5, p6", "connect(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Z",
    ),
    Hook("setVideoInfo", listOf(BYTE_BUFFER, BYTE_BUFFER, BYTE_BUFFER), "p1, p2, p3", "setVideoInfo($BYTE_BUFFER$BYTE_BUFFER$BYTE_BUFFER)Z"),
    Hook("sendVideo", listOf(BYTE_BUFFER, BUFFER_INFO), "p1, p2", "sendVideo($BYTE_BUFFER$BUFFER_INFO)Z"),
    Hook("sendAudio", listOf(BYTE_BUFFER, BUFFER_INFO), "p1, p2", "sendAudio($BYTE_BUFFER$BUFFER_INFO)Z"),
    Hook("disconnect", emptyList(), "", "disconnect()Z"),
)

/**
 * Sony's live streaming drives com.pedro RtmpClient through RtmpManager (connect / setVideoInfo /
 * sendVideo / sendAudio / disconnect). Each entry point first asks RawStreamer whether it owns the
 * session (stream key "raw"); if so the original RTMP code is skipped.
 */
@Suppress("unused")
val rawH264StreamPatch = bytecodePatch(
    name = "Raw H.264 stream",
    description = "Adds a low-latency transport to Live streaming: use stream key \"raw\" and the encoded " +
            "H.264 is sent straight to the host:port of the RTMP URL as a plain TCP stream instead of RTMP " +
            "(wired: rtmp://127.0.0.1:6970 with adb reverse tcp:6970; wireless: rtmp://<pc-ip>:6970). " +
            "Receive with gst-launch-1.0 tcpserversrc port=6970 ! h264parse ! avdec_h264 ! ... Video only.",
) {
    compatibleWith(SONY_CAMERA)

    extendWith("extensions/sony-camera.mpe")

    execute {
        // Drop Sony's encoded-frame backlog while in raw mode (constant ~1.6 s otherwise).
        Fingerprint(
            definingClass = "Lcom/sonymobile/android/media/internal/VideoTrack;",
            name = "doWriteOutputBuffer",
            parameters = emptyList(),
            returnType = "V",
        ).method.addInstructions(0, "invoke-static { p0 }, $EXTENSION_CLASS->trimBacklog(Ljava/lang/Object;)V")

        hooks.forEach { hook ->
            val method = Fingerprint(
                definingClass = MANAGER,
                name = hook.name,
                parameters = hook.parameters,
                returnType = "V",
            ).method
            val first = method.getInstruction(0)
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { ${hook.args} }, $EXTENSION_CLASS->${hook.call}
                    move-result v0
                    if-eqz v0, :original
                    return-void
                """,
                ExternalLabel("original", first),
            )
        }
    }
}
