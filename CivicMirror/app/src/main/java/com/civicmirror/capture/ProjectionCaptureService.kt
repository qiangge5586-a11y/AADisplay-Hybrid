package com.civicmirror.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
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
import com.civicmirror.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * First capture milestone: acquire real screen frames without encoding or modifying
 * the phone's physical display. The ImageReader surface is deliberately the only
 * producer target for this VirtualDisplay.
 */
class ProjectionCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var stopped = false
    private var frameCount = 0L
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

            val reader = createReader(outputWidth, outputHeight)
            imageReader = reader

            mediaProjection.registerCallback(object : MediaProjection.Callback() {
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
            }, captureHandler)

            // One consent token -> exactly one createVirtualDisplay call.
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "CivicMirror-Capture",
                outputWidth,
                outputHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler
            )
            updateNotification("屏幕捕获运行中 · 等待首帧")
            Log.i(TAG, "Capture started at ${outputWidth}x${outputHeight}")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start screen capture", error)
            stopCapture()
            stopSelf()
        }
    }

    private fun createReader(width: Int, height: Int): ImageReader {
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ source ->
                try {
                    val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                    image.use {
                        frameCount++
                        if (frameCount == 1L || frameCount % 120L == 0L) {
                            Log.i(TAG, "Captured frames=$frameCount size=${it.width}x${it.height}")
                            updateNotification("屏幕捕获运行中 · 已接收 $frameCount 帧")
                        }
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Frame acquisition failed", error)
                }
            }, captureHandler)
        }
    }

    private fun resizeCapture(sourceWidth: Int, sourceHeight: Int) {
        val display = virtualDisplay ?: return
        val size = fitWithin(sourceWidth, sourceHeight, MAX_EDGE)
        if (size.first == outputWidth && size.second == outputHeight) return

        val replacement = try {
            createReader(size.first, size.second)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to allocate resized ImageReader", error)
            return
        }

        val old = imageReader
        try {
            display.resize(size.first, size.second, densityDpi)
            display.setSurface(replacement.surface)
            imageReader = replacement
            outputWidth = size.first
            outputHeight = size.second
            old?.setOnImageAvailableListener(null, null)
            old?.close()
            Log.i(TAG, "Capture resized to ${outputWidth}x${outputHeight}")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to retarget capture surface", error)
            replacement.close()
        }
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = (getSystemService(WindowManager::class.java)).maximumWindowMetrics.bounds
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
        return Pair(
            (width * scale).roundToInt().coerceAtLeast(2),
            (height * scale).roundToInt().coerceAtLeast(2)
        )
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
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (error: Exception) {
            Log.w(TAG, "ImageReader release failed", error)
        }
        imageReader = null
        try {
            projection?.unregisterCallback(projectionCallbackNoop)
        } catch (_: Exception) {
            // The registered callback is owned by the projection and dies with it.
        }
        projection = null
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

        // Placeholder callback is never registered; callback lifetime is managed by MediaProjection.
        private val projectionCallbackNoop = object : MediaProjection.Callback() {}
    }
}
