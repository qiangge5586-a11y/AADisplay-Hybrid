package com.civicmirror.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Surface-input AVC encoder. MediaProjection renders directly into the codec input
 * surface, avoiding ImageReader RGBA copies on the hot path.
 */
class H264SurfaceEncoder(
    private val width: Int,
    private val height: Int,
    private val bitRate: Int = DEFAULT_BIT_RATE,
    private val frameRate: Int = DEFAULT_FRAME_RATE,
    private val onEncodedPacket: (ByteArray, Int, Long) -> Unit,
    private val onOutputFormat: (MediaFormat) -> Unit
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val running = AtomicBoolean(false)
    private var drainThread: Thread? = null
    private var started = false
    private var released = false

    val inputSurface: Surface

    init {
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) {
            "AVC dimensions must be positive even numbers"
        }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
        } catch (error: Exception) {
            codec.release()
            throw error
        }
    }

    fun start() {
        check(!started && !released) { "Encoder cannot be started" }
        codec.start()
        started = true
        running.set(true)
        drainThread = Thread(::drainOutput, "CivicMirror-AVC-Drain").apply { start() }
    }

    private fun drainOutput() {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            try {
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    index >= 0 -> {
                        try {
                            val buffer: ByteBuffer? = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                val packet = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.get(packet)
                                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                                    (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                                    onEncodedPacket(packet, info.flags, info.presentationTimeUs)
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        onOutputFormat(codec.outputFormat)
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            } catch (error: IllegalStateException) {
                if (running.get()) Log.e(TAG, "AVC output drain failed", error)
                break
            } catch (error: Exception) {
                Log.e(TAG, "AVC packet handling failed", error)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (released) return
        released = true
        running.set(false)
        drainThread?.interrupt()
        try {
            drainThread?.join(1_500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        drainThread = null
        if (started) {
            try {
                codec.stop()
            } catch (error: Exception) {
                Log.w(TAG, "AVC encoder stop failed", error)
            }
        }
        try {
            codec.release()
        } catch (error: Exception) {
            Log.w(TAG, "AVC encoder release failed", error)
        }
        inputSurface.release()
        started = false
    }

    companion object {
        private const val TAG = "CivicMirrorAVC"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        const val DEFAULT_BIT_RATE = 4_000_000
        const val DEFAULT_FRAME_RATE = 30
    }
}
