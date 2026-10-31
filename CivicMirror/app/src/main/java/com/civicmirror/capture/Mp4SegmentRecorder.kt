package com.civicmirror.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes one encoder generation to an app-private MP4 file.
 * A new segment is used whenever the capture dimensions change.
 */
class Mp4SegmentRecorder(context: Context) {
    private val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "CivicMirror")
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var started = false
    private var finished = false
    private var outputFile: File? = null
    private var sampleCount = 0L

    @Synchronized
    fun startSegment(format: MediaFormat) {
        if (finished || started) return
        try {
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("Cannot create recording directory: $directory")
            }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val file = File(directory, "CivicMirror-$stamp.mp4")
            val nextMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val nextTrack = nextMuxer.addTrack(format)
            nextMuxer.start()
            muxer = nextMuxer
            trackIndex = nextTrack
            outputFile = file
            started = true
            Log.i(TAG, "MP4 recording started: ${file.absolutePath}")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start MP4 recording", error)
            closeMuxer()
            finished = true
        }
    }

    @Synchronized
    fun writeSample(data: ByteArray, flags: Int, presentationTimeUs: Long) {
        if (!started || finished || data.isEmpty()) return
        if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 ||
            (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
        try {
            val info = MediaCodec.BufferInfo().apply {
                set(
                    0,
                    data.size,
                    presentationTimeUs,
                    flags and MediaCodec.BUFFER_FLAG_KEY_FRAME
                )
            }
            muxer?.writeSampleData(trackIndex, java.nio.ByteBuffer.wrap(data), info)
            sampleCount++
            if (sampleCount == 1L || sampleCount % 300L == 0L) {
                Log.i(TAG, "MP4 samples=$sampleCount file=${outputFile?.name}")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to write MP4 sample", error)
            finish()
        }
    }

    @Synchronized
    fun finish(): String? {
        if (!finished) {
            finished = true
            closeMuxer()
            Log.i(TAG, "MP4 recording finished: samples=$sampleCount file=${outputFile?.absolutePath}")
        }
        return outputFile?.takeIf { it.exists() && it.length() > 0L }?.absolutePath
    }

    private fun closeMuxer() {
        try {
            if (started) muxer?.stop()
        } catch (error: Exception) {
            Log.w(TAG, "MP4 muxer stop failed", error)
        }
        try {
            muxer?.release()
        } catch (error: Exception) {
            Log.w(TAG, "MP4 muxer release failed", error)
        }
        muxer = null
        started = false
    }

    companion object {
        private const val TAG = "CivicMirrorMP4"
    }
}
