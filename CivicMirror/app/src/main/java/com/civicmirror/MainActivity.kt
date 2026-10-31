package com.civicmirror

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.civicmirror.capture.ProjectionCaptureService

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }

        content.addView(text("CivicMirror", 28f, true))
        content.addView(text("独立 Android Auto 镜像项目 · 0.4.0", 16f, false))
        content.addView(section("屏幕采集、H.264 编码与 MP4 验证"))

        status = text("状态：尚未启动。开始后允许系统屏幕捕获；停止时会封装 MP4 文件。", 15f, false)
        content.addView(status)

        content.addView(button("开始屏幕捕获与 MP4 录制") { requestCapture() })
        content.addView(button("停止捕获并保存 MP4") {
            startService(Intent(this, ProjectionCaptureService::class.java).setAction(ProjectionCaptureService.ACTION_STOP))
            status.text = "状态：已发送停止请求。录制文件保存在 Android/data/com.civicmirror.debug/files/Movies/CivicMirror/"
        })

        content.addView(section("0.4.0 已实现"))
        listOf(
            "MediaProjection 用户授权与前台服务",
            "VirtualDisplay 直接输出到 MediaCodec Surface",
            "H.264 / AVC 硬件编码，默认 1280 长边、30 fps、4 Mbps",
            "按编码器输出格式创建 MP4，写入关键帧标记和时间戳",
            "捕获尺寸变化时切换编码器并分段保存 MP4",
            "停止时安全结束 MP4 封装；不修改手机物理分辨率或 DPI"
        ).forEach { content.addView(text("•  $it", 14f, false)) }

        content.addView(section("录制文件位置"))
        content.addView(text(
            "Android/data/com.civicmirror.debug/files/Movies/CivicMirror/\n每次编码器尺寸变化会生成独立 MP4 分段。请用手机文件管理器查看 Android/data 目录（部分系统可能限制访问）。",
            14f, false
        ))

        content.addView(section("车机通道状态"))
        content.addView(text(
            "MP4 只用于确认手机采集与编码链路，不是车机传输。Android Auto 普通应用接口不是通用屏幕投屏接口；视频传输与触控回传仍需独立实现和实车验证。",
            14f, false
        ))
        content.addView(section("系统限制"))
        content.addView(text(
            "每次捕获都需要系统授权。DRM 或 FLAG_SECURE 内容可能黑屏。Shizuku 不能绕过 MediaProjection 授权，也不能自动获得 Android Auto 视频入口。",
            14f, false
        ))

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun requestCapture() {
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        startActivityForResult(intent, REQUEST_CAPTURE)
    }

    @Deprecated("Uses the platform activity result API for a zero-dependency capture flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "状态：用户取消了屏幕捕获授权"
            return
        }
        val serviceIntent = Intent(this, ProjectionCaptureService::class.java).apply {
            action = ProjectionCaptureService.ACTION_START
            putExtra(ProjectionCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ProjectionCaptureService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        status.text = "状态：已启动捕获与录制；停止时封装 MP4 文件"
    }

    private fun section(value: String) =
        text(value, 17f, true).apply { setPadding(0, dp(22), 0, dp(10)) }

    private fun button(value: String, action: () -> Unit) =
        Button(this).apply {
            text = value
            setOnClickListener { action() }
        }

    private fun text(value: String, size: Float, bold: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size
            gravity = Gravity.START
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF202124.toInt())
            setPadding(0, dp(5), 0, dp(5))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_CAPTURE = 4101
    }
}
