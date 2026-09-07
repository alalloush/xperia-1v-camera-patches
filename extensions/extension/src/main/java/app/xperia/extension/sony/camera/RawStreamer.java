package app.xperia.extension.sony.camera;

import android.media.MediaCodec;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Raw H.264 (Annex-B) over TCP, as a low-latency alternative to Sony's RTMP live streaming.
 * Selected from Sony's own "Connect to: RTMP URL" dialog: URL rtmp://host:port (Sony insists on the
 * rtmp:// scheme) with stream key "raw". Wired: rtmp://127.0.0.1:6970 + `adb reverse tcp:6970 tcp:6970`;
 * wireless: rtmp://<pc-ip>:6970. The PC runs e.g.
 *   gst-launch-1.0 tcpserversrc port=6970 ! h264parse ! avdec_h264 ! videoconvert ! pipewiresink ...
 * Hooked into jp.co.sony.mc.camera.rtmp.RtmpManager: connect/sendVideo/setVideoInfo/sendAudio/disconnect.
 * MediaCodec's H.264 output is Annex-B (start codes); pedro's FLV packer strips them, we forward as-is and
 * repeat SPS/PPS before every keyframe so a receiver can join at any time.
 */
@SuppressWarnings("unused")
public final class RawStreamer {
    private static final int QUEUE_FRAMES = 6;

    private static volatile RawStreamer active;

    private final String host;
    private final int port;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_FRAMES);
    private final Thread writer;
    private volatile boolean running = true;
    private volatile byte[] parameterSets;
    private Object manager;
    private long frames;

    private RawStreamer(String host, int port) {
        this.host = host;
        this.port = port;
        writer = new Thread(this::run, "RawStreamer");
        writer.setDaemon(true);
    }

    /**
     * Sony validates the URL field (must start with rtmp://), so this transport is selected by the stream
     * key: key "raw" (or a URL path ending in /raw). Host and port come from the URL; port defaults to 6970.
     */
    public static boolean claims(String url, String key) {
        if (url == null) return false;
        String k = key == null ? "" : key.trim();
        return k.equalsIgnoreCase("raw") || url.endsWith("/raw");
    }

    /**
     * Injection point (RtmpManager.connect). Returns true when the URL is ours and Sony's RTMP client must
     * not be started.
     */
    public static boolean connect(Object manager, String url, String key) {
        if (!claims(url, key)) return false;
        Uri uri = Uri.parse(url);
        int port = uri.getPort() > 0 ? uri.getPort() : 6970;
        RawStreamer s = new RawStreamer(uri.getHost(), port);
        s.manager = manager;
        RawStreamer old = active;
        active = s;
        if (old != null) old.stop();
        s.writer.start();
        return true;
    }

    /** Injection point (RtmpManager.setVideoInfo). */
    public static boolean setVideoInfo(ByteBuffer sps, ByteBuffer pps, ByteBuffer vps) {
        RawStreamer s = active;
        if (s == null) return false;
        byte[] a = annexB(sps), b = annexB(pps), c = vps != null ? annexB(vps) : new byte[0];
        byte[] all = new byte[a.length + b.length + c.length];
        System.arraycopy(c, 0, all, 0, c.length);
        System.arraycopy(a, 0, all, c.length, a.length);
        System.arraycopy(b, 0, all, c.length + a.length, b.length);
        s.parameterSets = all;
        return true;
    }

    /** Injection point (RtmpManager.sendVideo). */
    public static boolean sendVideo(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        RawStreamer s = active;
        if (s == null) return false;
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return true; // SPS/PPS via setVideoInfo
        boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if ((++s.frames % 30) == 0) {
            long ageMs = (SystemClock.elapsedRealtimeNanos() / 1000 - info.presentationTimeUs) / 1000;
            Log.i("RawStreamer", "frame " + s.frames + " age=" + ageMs + "ms queue=" + s.queue.size() + " key=" + key + " size=" + info.size);
        }
        byte[] ps = key ? s.parameterSets : null;
        int psLen = ps != null ? ps.length : 0;
        ByteBuffer src = buffer.duplicate();
        src.position(info.offset).limit(info.offset + info.size);
        boolean hasStartCode = info.size >= 4 && src.get(info.offset) == 0 && src.get(info.offset + 1) == 0
                && (src.get(info.offset + 2) == 1 || (src.get(info.offset + 2) == 0 && src.get(info.offset + 3) == 1));
        int prefix = hasStartCode ? 0 : 4;
        byte[] out = new byte[psLen + prefix + info.size];
        if (psLen > 0) System.arraycopy(ps, 0, out, 0, psLen);
        if (prefix != 0) out[psLen + 3] = 1;
        src.get(out, psLen + prefix, info.size);
        if (key) {
            s.queue.clear(); // a keyframe supersedes any backlog
        } else if (s.queue.remainingCapacity() == 0) {
            return true; // link is behind: drop this delta frame rather than add latency
        }
        s.queue.offer(out);
        return true;
    }

    private static long rawFrames;

    /** Injection point (VideoTrack$VideoEncoderCallback.onOutputBufferAvailable): raw codec timestamps. */
    public static void onEncoderOutput(MediaCodec.BufferInfo info) {
        if (active == null || info == null) return;
        if ((++rawFrames % 30) != 0) return;
        long nowUs = SystemClock.elapsedRealtimeNanos() / 1000;
        long nowUptimeUs = SystemClock.uptimeMillis() * 1000;
        Log.i("RawStreamer", "encoder out pts=" + info.presentationTimeUs + "us ageElapsed=" + (nowUs - info.presentationTimeUs) / 1000
                + "ms ageUptime=" + (nowUptimeUs - info.presentationTimeUs) / 1000 + "ms flags=" + info.flags);
    }

    private static final int MAX_PENDING = 2;
    private static Field bufferListField, encoderField, bufferIndexField, copiedField;
    private static long trimmed, trimLogs;

    /**
     * Injection point (VideoTrack.doWriteOutputBuffer, before the original body). Sony's Track keeps
     * encoded frames in mBufferList and drains one per call; once the drain starts late the backlog is
     * carried forever as constant latency. In raw mode keep at most MAX_PENDING frames queued and release
     * the rest back to the codec.
     */
    public static void trimBacklog(Object track) {
        if (active == null) return;
        try {
            if (bufferListField == null) {
                Class<?> c = track.getClass();
                while (c != null && bufferListField == null) {
                    try {
                        bufferListField = c.getDeclaredField("mBufferList");
                        encoderField = c.getDeclaredField("mEncoder");
                    } catch (NoSuchFieldException e) {
                        c = c.getSuperclass();
                    }
                }
                bufferListField.setAccessible(true);
                encoderField.setAccessible(true);
            }
            LinkedBlockingDeque<?> list = (LinkedBlockingDeque<?>) bufferListField.get(track);
            MediaCodec encoder = (MediaCodec) encoderField.get(track);
            int size = list.size();
            if ((++trimLogs % 60) == 0) Log.i("RawStreamer", "pending encoded frames=" + size + " trimmed=" + trimmed);
            while (list.size() > MAX_PENDING) {
                Object eb = list.removeFirst();
                if (bufferIndexField == null) {
                    bufferIndexField = eb.getClass().getField("bufferIndex");
                    copiedField = eb.getClass().getField("containsCopiedBuffer");
                }
                if (!copiedField.getBoolean(eb) && encoder != null) {
                    encoder.releaseOutputBuffer(bufferIndexField.getInt(eb), false);
                }
                trimmed++;
            }
        } catch (Exception e) {
            Log.w("RawStreamer", "trimBacklog failed: " + e);
        }
    }

    /** Injection point (RtmpManager.sendAudio): audio is not carried on this transport. */
    public static boolean sendAudio(ByteBuffer buffer, MediaCodec.BufferInfo info) {
        return active != null;
    }

    /** Injection point (RtmpManager.disconnect). */
    public static boolean disconnect() {
        RawStreamer s = active;
        if (s == null) return false;
        active = null;
        s.stop();
        return true;
    }

    private static byte[] annexB(ByteBuffer nal) {
        ByteBuffer b = nal.duplicate();
        b.rewind();
        int n = b.remaining();
        boolean sc = n >= 4 && b.get(0) == 0 && b.get(1) == 0 && (b.get(2) == 1 || (b.get(2) == 0 && b.get(3) == 1));
        byte[] out = new byte[(sc ? 0 : 4) + n];
        if (!sc) out[3] = 1;
        b.get(out, sc ? 0 : 4, n);
        return out;
    }

    private void stop() {
        running = false;
        writer.interrupt();
    }

    private void notify(String method) {
        try {
            manager.getClass().getMethod(method).invoke(manager);
        } catch (Exception ignored) {
        }
    }

    private void run() {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 5000);
            OutputStream os = socket.getOutputStream();
            notify("onConnectionSuccessRtmp");
            while (running) {
                byte[] frame = queue.take();
                os.write(frame);
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            if (running) {
                running = false;
                if (active == this) active = null;
                notify("onDisconnectRtmp");
            }
        } finally {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
