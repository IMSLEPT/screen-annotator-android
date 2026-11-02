package com.imslept.screenannotator

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Pipeline di registrazione:
 *   schermo -> VirtualDisplay -> MediaCodec H.264 \
 *                                                  > MediaMuxer MP4 -> galleria (MediaStore)
 *   microfono / audio di sistema -> mix PCM -> MediaCodec AAC /
 *
 * Gli overlay di disegno fanno parte dello schermo, quindi finiscono nel video.
 */
class ScreenRecorder(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val onWarn: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private companion object {
        const val SAMPLE_RATE = 44100
        const val CHUNK = 1024 // campioni per blocco audio (~23 ms)
    }

    @Volatile
    private var running = false

    private var baseUs = 0L
    private var width = 0
    private var height = 0

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var inputSurface: Surface? = null
    private var mic: AudioRecord? = null
    private var dev: AudioRecord? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var tm: TrackMuxer? = null

    @Volatile
    private var videoEosAt = 0L

    // ------------------------------------------------------------------ Avvio

    fun start() {
        val resolver = ctx.contentResolver
        val name = "ScreenAnnotator_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenAnnotator")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossibile creare il file video")
        uri = outUri
        pfd = resolver.openFileDescriptor(outUri, "rw")
            ?: throw IllegalStateException("Impossibile aprire il file video")
        val muxer = MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        setupAudio()
        val audioOn = audioCodec != null
        tm = TrackMuxer(muxer, if (audioOn) 2 else 1)

        baseUs = System.nanoTime() / 1000
        running = true

        setupVideo()

        videoThread = Thread({ videoLoop() }, "rec-video").also { it.start() }
        if (audioOn) audioThread = Thread({ audioLoop() }, "rec-audio").also { it.start() }
    }

    private fun realScreenSize(): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun setupVideo() {
        val (w0, h0) = realScreenSize()
        val target = Prefs.resolution
        val shortSide = min(w0, h0)
        val scale = if (target <= 0 || target >= shortSide) 1f else target.toFloat() / shortSide
        width = max(16, (w0 * scale).toInt() / 16 * 16)
        height = max(16, (h0 * scale).toInt() / 16 * 16)
        val fps = Prefs.fps

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, Prefs.bitrateMbps * 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        videoCodec = codec
        inputSurface = surface

        display = projection.createVirtualDisplay(
            "ScreenAnnotator", width, height, ctx.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
    }

    private fun pcmFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    private fun setupAudio() {
        val granted = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val mode = if (granted) Prefs.audioMode else 0
        if (mode == 0) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf * 2, 16384)

        if (mode == 1 || mode == 3) {
            try {
                val r = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(pcmFormat())
                    .setBufferSizeInBytes(bufSize)
                    .build()
                if (r.state == AudioRecord.STATE_INITIALIZED) mic = r else r.release()
            } catch (_: Exception) {
            }
        }
        if (mode == 2 || mode == 3) {
            try {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val r = AudioRecord.Builder()
                    .setAudioFormat(pcmFormat())
                    .setBufferSizeInBytes(bufSize)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
                if (r.state == AudioRecord.STATE_INITIALIZED) dev = r else r.release()
            } catch (_: Exception) {
            }
        }
        if (mic == null && dev == null) return

        val afmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(afmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        audioCodec = codec
    }

    // ------------------------------------------------------------------ Thread video

    private fun videoLoop() {
        val codec = videoCodec ?: return
        val muxer = tm ?: return
        val info = MediaCodec.BufferInfo()
        var track = -1
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = muxer.addTrack(codec.outputFormat)
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && buf != null && track >= 0) {
                            info.presentationTimeUs = max(0L, info.presentationTimeUs - baseUs)
                            muxer.write(track, buf, info)
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        val eosAt = videoEosAt
                        if (eosAt != 0L && System.currentTimeMillis() - eosAt > 3000) break
                    }
                }
            }
        } catch (e: Exception) {
            if (running) onError("Errore video: " + e.message)
        }
    }

    // ------------------------------------------------------------------ Thread audio

    private fun readFully(rec: AudioRecord, buf: ShortArray) {
        var off = 0
        while (off < buf.size && running) {
            val r = rec.read(buf, off, buf.size - off)
            if (r <= 0) break
            off += r
        }
        // eventuale parte non letta resta a zero (silenzio)
        while (off < buf.size) buf[off++] = 0
    }

    private fun audioLoop() {
        val codec = audioCodec ?: return
        val muxer = tm ?: return
        val info = MediaCodec.BufferInfo()
        var track = -1
        val micBuf = ShortArray(CHUNK)
        val devBuf = ShortArray(CHUNK)
        val out = ShortArray(CHUNK)
        var totalSamples = 0L
        var startUs = -1L
        val chunkUs = CHUNK * 1_000_000L / SAMPLE_RATE

        fun drain() {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 0)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = muxer.addTrack(codec.outputFormat)
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && buf != null && track >= 0) muxer.write(track, buf, info)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> return
                }
            }
        }

        try {
            mic?.startRecording()
            dev?.startRecording()
            while (running) {
                mic?.let { readFully(it, micBuf) }
                dev?.let { readFully(it, devBuf) }
                if (startUs < 0) {
                    startUs = max(0L, System.nanoTime() / 1000 - baseUs - chunkUs)
                }
                for (i in 0 until CHUNK) {
                    val a = if (mic != null) micBuf[i].toInt() else 0
                    val b = if (dev != null) devBuf[i].toInt() else 0
                    out[i] = (a + b).coerceIn(-32768, 32767).toShort()
                }
                val idx = codec.dequeueInputBuffer(20_000)
                if (idx >= 0) {
                    val ib = codec.getInputBuffer(idx)!!
                    ib.clear()
                    ib.order(ByteOrder.LITTLE_ENDIAN)
                    ib.asShortBuffer().put(out)
                    val pts = startUs + totalSamples * 1_000_000L / SAMPLE_RATE
                    codec.queueInputBuffer(idx, 0, CHUNK * 2, pts, 0)
                }
                totalSamples += CHUNK
                drain()
            }
            // fine del flusso audio
            val idx = codec.dequeueInputBuffer(100_000)
            if (idx >= 0) {
                val pts = startUs + totalSamples * 1_000_000L / SAMPLE_RATE
                codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                val deadline = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < deadline) {
                    drain()
                    Thread.sleep(20)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) {
            // L'audio non e' indispensabile: se fallisce si continua con il solo video
            if (track < 0) muxer.dropExpected()
            if (running) onWarn("Audio non disponibile: registro solo il video")
        }
    }

    // ------------------------------------------------------------------ Stop

    /** Da chiamare fuori dal thread principale. Restituisce l'URI del video salvato (o null). */
    fun stop(): Uri? {
        if (!running) return null
        running = false
        try { display?.release() } catch (_: Exception) {}
        videoEosAt = System.currentTimeMillis()
        try { videoCodec?.signalEndOfInputStream() } catch (_: Exception) {}
        audioThread?.join(4000)
        videoThread?.join(5000)

        try { mic?.stop() } catch (_: Exception) {}
        try { dev?.stop() } catch (_: Exception) {}
        mic?.release()
        dev?.release()
        try { videoCodec?.stop() } catch (_: Exception) {}
        videoCodec?.release()
        try { audioCodec?.stop() } catch (_: Exception) {}
        audioCodec?.release()
        inputSurface?.release()

        val ok = tm?.finish() ?: false
        try { pfd?.close() } catch (_: Exception) {}

        val u = uri ?: return null
        val resolver = ctx.contentResolver
        return if (ok) {
            val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(u, v, null, null)
            u
        } else {
            resolver.delete(u, null, null)
            null
        }
    }
}

/**
 * Unisce i campioni video e audio nello stesso MP4. Il muxer parte solo quando entrambe le tracce
 * sono pronte: i campioni arrivati prima vengono tenuti in memoria e scritti subito dopo.
 */
private class TrackMuxer(private val muxer: MediaMuxer, private var expected: Int) {
    private class Pending(val track: Int, val data: ByteArray, val flags: Int, val pts: Long)

    private var added = 0
    private var started = false
    private var wroteAny = false
    private val pending = ArrayList<Pending>()
    private val last = HashMap<Int, Long>()

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val idx = muxer.addTrack(format)
        added++
        if (added == expected) {
            muxer.start()
            started = true
            for (p in pending) writeNow(p.track, p.data, p.flags, p.pts)
            pending.clear()
        }
        return idx
    }

    /** Una traccia attesa non arrivera' piu' (audio fallito): si parte con quelle gia' pronte. */
    @Synchronized
    fun dropExpected() {
        if (expected > 1) expected--
        if (!started && added > 0 && added == expected) {
            muxer.start()
            started = true
            for (p in pending) writeNow(p.track, p.data, p.flags, p.pts)
            pending.clear()
        }
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        buf.get(data)
        if (!started) {
            pending.add(Pending(track, data, info.flags, info.presentationTimeUs))
        } else {
            writeNow(track, data, info.flags, info.presentationTimeUs)
        }
    }

    private fun writeNow(track: Int, data: ByteArray, flags: Int, pts: Long) {
        var t = max(0L, pts)
        val l = last[track]
        if (l != null && t <= l) t = l + 1 // i timestamp devono crescere in ogni traccia
        last[track] = t
        val bi = MediaCodec.BufferInfo()
        bi.set(0, data.size, t, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
        muxer.writeSampleData(track, ByteBuffer.wrap(data), bi)
        wroteAny = true
    }

    /** Chiude il file. Restituisce true se contiene dati validi. */
    @Synchronized
    fun finish(): Boolean {
        var ok = false
        if (started) {
            try {
                muxer.stop()
                ok = wroteAny
            } catch (_: Exception) {
            }
        }
        try { muxer.release() } catch (_: Exception) {}
        return ok
    }
}
