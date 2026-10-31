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
import com.civicmirror.usb.UsbAccessoryProbe

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var usbStatus: TextView
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var usbProbe: UsbAccessoryProbe

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(MediaProjectionManager::class.java)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }

        content.addView(text("CivicMirror", 28f, true))
        content.addView(text("独立 Android Auto 镜像项目 · 0.5.0", 16f, false))

        content.addView(section("原厂 USB Android Auto 接口诊断"))
        usbStatus = text("尚未检测。连接车机 USB 后点击下方按钮。", 14f, false)
        content.addView(usbStatus)
        content.addView(button("检测 USB AOA 接口") { usbProbe.probe() })
        content.addView(text(
            "此检测只确认系统是否把车机识别为 Android Auto USB accessory，以及应用能否打开 USB 句柄；" +
                "不会发送 AAP 数据，也不代表车机投屏已经连通。",
            13f, false
        ))

        content.addView(section("屏幕采集、H.264 编码与 MP4 验证"))
        status = text("状态：尚未启动。开始后允许系统屏幕捕获；停止时会封装 MP4 文件。", 15f, false)
        content.addView(status)

        content.addView(button("开始屏幕捕获与 MP4 录制") { requestCapture() })
        content.addView(button("停止捕获并保存 MP4") {
            startService(Intent(this, ProjectionCaptureService::class.java).setAction(ProjectionCaptureService.ACTION_STOP))
            status.text = "状态：已发送停止请求。录制文件保存在 Android/data/com.civicmirror.debug/files/Movies/CivicMirror/"
        })

        content.addView(section("0.5.0 已实现"))
        listOf(
            "MediaProjection 用户授权与前台服务",
            "VirtualDisplay 直接输出到 MediaCodec Surface",
            "H.264 / AVC 硬件编码，默认 1280 长边、30 fps、4 Mbps",
            "按编码器输出格式创建 MP4，写入关键帧标记和时间戳",
            "捕获尺寸变化时切换编码器并分段保存 MP4",
            "USB AOA accessory 识别、系统授权请求与句柄打开诊断",
            "不修改手机物理屏幕分辨率或 DPI"
        ).forEach { content.addView(text("•  $it", 14f, false)) }

        content.addView(section("录制文件位置"))
        content.addView(text(
            "Android/data/com.civicmirror.debug/files/Movies/CivicMirror/\n每次编码器尺寸变化会生成独立 MP4 分段。请用手机文件管理器查看 Android/data 目录（部分系统可能限制访问）。",
            14f, false
        ))

        content.addView(section("车机通道状态"))
        content.addView(text(
            "USB AOA 诊断不等于 AAP 协议连接。AAP/TLS 握手、H.264 视频通道和触控回传仍未实现；" +
                "需要先确认本田车机是否接受第三方手机证书，再推进视频投屏。",
            14f, false
        ))
        content.addView(section("系统限制"))
        content.addView(text(
            "每次捕获都需要系统授权。DRM 或 FLAG_SECURE 内容可能黑屏。Shizuku 不能绕过 MediaProjection 授权，" +
                "也不能自动获得 Android Auto 视频入口。",
            14f, false
        ))

        usbProbe = UsbAccessoryProbe(this) { usbStatus.text = it }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onDestroy() {
        if (::usbProbe.isInitialized) usbProbe.dispose()
        super.onDestroy()
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
