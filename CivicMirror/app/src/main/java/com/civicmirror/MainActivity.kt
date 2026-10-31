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
        content.addView(text("独立 Android Auto 镜像项目 · 0.2.0", 16f, false))
        content.addView(section("屏幕捕获测试"))

        status = text("状态：尚未启动。点击下方按钮并在系统弹窗中允许屏幕捕获。", 15f, false)
        content.addView(status)

        content.addView(button("开始屏幕捕获") { requestCapture() })
        content.addView(button("停止屏幕捕获") {
            startService(Intent(this, ProjectionCaptureService::class.java).setAction(ProjectionCaptureService.ACTION_STOP))
            status.text = "状态：已发送停止请求"
        })

        content.addView(section("当前实现"))
        listOf(
            "MediaProjection 用户授权流程",
            "前台服务维持捕获会话",
            "ImageReader 接收 RGBA 帧并及时释放",
            "Android 14+ 捕获区域尺寸变化处理",
            "每次授权只创建一个 VirtualDisplay"
        ).forEach { content.addView(text("•  $it", 14f, false)) }

        content.addView(section("下一阶段"))
        content.addView(text(
            "当前捕获帧用于验证手机端采集链路，尚未输出到 Android Auto 车机 Surface。之后接入车机画面通道、分辨率与帧率设置，再验证触控回传。",
            14f, false
        ))
        content.addView(section("系统限制"))
        content.addView(text(
            "每次捕获都需要系统授权。DRM 或安全窗口可能显示黑屏。Shizuku 不能绕过 MediaProjection 授权限制。",
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
        status.text = "状态：已提交捕获会话，查看系统持续通知确认运行状态"
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
