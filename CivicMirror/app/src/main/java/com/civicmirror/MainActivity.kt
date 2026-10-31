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
        content.addView(text("独立 Android Auto 镜像项目 · 0.3.0", 16f, false))
        content.addView(section("屏幕采集与编码测试"))

        status = text("状态：尚未启动。点击下方按钮并在系统弹窗中允许屏幕捕获。", 15f, false)
        content.addView(status)

        content.addView(button("开始屏幕捕获与 H.264 编码") { requestCapture() })
        content.addView(button("停止捕获与编码") {
            startService(Intent(this, ProjectionCaptureService::class.java).setAction(ProjectionCaptureService.ACTION_STOP))
            status.text = "状态：已发送停止请求"
        })

        content.addView(section("当前实现"))
        listOf(
            "MediaProjection 用户授权与前台服务",
            "VirtualDisplay 直接输出到 MediaCodec Surface",
            "H.264 / AVC 硬件编码，默认 1280 长边、30 fps、4 Mbps",
            "编码数据包统计与时间戳记录",
            "Android 14+ 捕获区域变化时重建编码器并切换 Surface",
            "不修改手机物理屏幕分辨率或 DPI"
        ).forEach { content.addView(text("•  $it", 14f, false)) }

        content.addView(section("车机通道状态"))
        content.addView(text(
            "H.264 编码链路已接入，但编码数据目前只在手机端统计，尚未传输到 Android Auto。普通 Android Auto Surface 不是任意视频投屏接口；需继续验证官方准入路线与独立传输方案。",
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
        status.text = "状态：已提交捕获与编码会话，查看持续通知确认编码包计数"
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
