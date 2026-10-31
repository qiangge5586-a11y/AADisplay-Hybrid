package com.civicmirror.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.civicmirror.MainActivity
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MediaProjection -> hardware AVC encoder. The encoder input Surface is the
 * VirtualDisplay target, avoiding a CPU-side RGBA frame copy.
 */
class ProjectionCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: H264SurfaceEncoder? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var stopped = false
    private var encodedFrameCount = 0L
    private var encodedByteCount = 0L
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system or user")
            stopCapture()
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT >= 34 && width > 0 && height > 0) {
                resizeCapture(width, height)
            }
        }
    }
    private var outputWidth = 0
    private var outputHeight = 0
    private var densityDpi = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        captureThread = HandlerThread("CivicMirror-Capture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (projection != null) {
                    Log.w(TAG, "Capture already active; ignoring duplicate start")
                    return START_NOT_STICKY
                }
                startForegroundCompat()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = getResultData(intent)
                if (resultData == null || resultCode != RESULT_OK) {
                    Log.e(TAG, "Missing or invalid projection consent result")
                    stopCapture()
                    stopSelf()
                    return START_NOT_STICKY
                }
                startCapture(resultCode, resultData)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mediaProjection = manager.getMediaProjection(resultCode, resultData)
            projection = mediaProjection

            val metrics = getScreenMetrics()
            densityDpi = resources.configuration.densityDpi
            val size = fitWithin(metrics.widthPixels, metrics.heightPixels, MAX_EDGE)
            outputWidth = size.first
            outputHeight = size.second

            val activeEncoder = createEncoder(outputWidth, outputHeight)
            encoder = activeEncoder
            mediaProjection.registerCallback(projectionCallback, captureHandler)

            // One consent token -> exactly one createVirtualDisplay call.
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "CivicMirror-Capture",
                outputWidth,
                outputHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                activeEncoder.inputSurface,
                null,
                captureHandler
            )
            updateNotification("AVC 硬件编码运行中 · 等待首帧")
            Log.i(TAG, "Capture/AVC started at ${outputWidth}x${outputHeight}")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start screen capture/AVC encoder", error)
            stopCapture()
            stopSelf()
        }
    }

    private fun createEncoder(width: Int, height: Int): H264SurfaceEncoder {
        val result = H264SurfaceEncoder(width, height) { packet, flags, pts ->
            if (packet.isNotEmpty()) {
                encodedFrameCount++
                encodedByteCount += packet.size
                if (encodedFrameCount == 1L || encodedFrameCount % 60L == 0L) {
                    Log.i(
                        TAG,
                        "AVC packets=$encodedFrameCount bytes=$encodedByteCount " +
                            "size=${width}x$height ptsUs=$pts flags=$flags"
                    )
                    updateNotification("AVC 编码中 · ${encodedFrameCount} 包")
                }
            }
        }
        result.start()
        return result
    }

    private fun resizeCapture(sourceWidth: Int, sourceHeight: Int) {
        val display = virtualDisplay ?: return
        val size = fitWithin(sourceWidth, sourceHeight, MAX_EDGE)
        if (size.first == outputWidth && size.second == outputHeight) return

        val replacement = try {
            createEncoder(size.first, size.second)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to allocate resized AVC encoder", error)
            return
        }

        val old = encoder
        try {
            display.resize(size.first, size.second, densityDpi)
            display.setSurface(replacement.inputSurface)
            encoder = replacement
            outputWidth = size.first
            outputHeight = size.second
            old?.stop()
            Log.i(TAG, "AVC capture resized to ${outputWidth}x${outputHeight}")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to retarget AVC input surface", error)
            replacement.stop()
        }
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun fitWithin(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val scale = minOf(1f, maxEdge.toFloat() / max(width, height).toFloat())
        val outWidth = (width * scale).roundToInt().coerceAtLeast(2)
        val outHeight = (height * scale).roundToInt().coerceAtLeast(2)
        return Pair(outWidth - outWidth % 2, outHeight - outHeight % 2)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("正在启动屏幕捕获")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(message: String): Notification {
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("CivicMirror")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(launch)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("CivicMirror")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(launch)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "屏幕捕获", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun getResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun stopCapture() {
        if (stopped) return
        stopped = true
        try {
            virtualDisplay?.release()
        } catch (error: Exception) {
            Log.w(TAG, "VirtualDisplay release failed", error)
        }
        virtualDisplay = null
        try {
            encoder?.stop()
        } catch (error: Exception) {
            Log.w(TAG, "AVC encoder release failed", error)
        }
        encoder = null
        val activeProjection = projection
        projection = null
        try {
            activeProjection?.unregisterCallback(projectionCallback)
        } catch (error: Exception) {
            Log.w(TAG, "MediaProjection callback unregister failed", error)
        }
        try {
            activeProjection?.stop()
        } catch (error: Exception) {
            Log.w(TAG, "MediaProjection stop failed", error)
        }
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.civicmirror.capture.START"
        const val ACTION_STOP = "com.civicmirror.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val TAG = "CivicMirrorCapture"
        private const val CHANNEL_ID = "civicmirror_capture"
        private const val NOTIFICATION_ID = 41
        private const val MAX_EDGE = 1280
        private const val RESULT_OK = -1
    }
}
