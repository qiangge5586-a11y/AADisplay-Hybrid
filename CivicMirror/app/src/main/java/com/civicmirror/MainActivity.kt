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
        content.addView(text("独立 Android Auto 镜像项目 · 0.9.0", 16f, false))

        content.addView(section("原厂 USB Android Auto 双向传输"))
        usbStatus = text("尚未连接。使用数据线连接车机 USB 后点击连接。", 14f, false)
        content.addView(usbStatus)
        content.addView(button("连接并保持 USB AOA 数据流") { usbProbe.probe() })
        content.addView(button("断开 USB 数据流") { usbProbe.disconnect() })
        content.addView(text(
            "当前实现会保持 USB accessory 句柄，并为输入/输出分别复制文件描述符；拔线时自动关闭。" +
                "此步骤只验证原始 USB 传输层，不会发送 AAP/TLS 数据，也不代表车机投屏成功。",
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

        content.addView(section("0.9.0 已实现"))
        listOf(
            "MediaProjection 用户授权与前台服务",
            "VirtualDisplay 直接输出到 MediaCodec Surface",
            "H.264 / AVC 硬件编码及 MP4 分段录制",
            "USB AOA accessory 识别与系统授权请求",
            "持久 USB 双向文件描述符：读写 fd 分离，支持断开清理",
            "AAP 帧编解码、消息重组及明文流会话单元测试",
            "不修改手机物理屏幕分辨率或 DPI"
        ).forEach { content.addView(text("•  $it", 14f, false)) }

        content.addView(section("下一阶段协议状态"))
        content.addView(text(
            "当前 USB 传输已具备持续打开能力，但尚未接入 AAP/TLS：现有 AapStreamSession 仅处理明文 AAP 帧。" +
                "接下来必须实现正确的 TLS 1.2 认证与 AAP 帧/加密载荷分层，再进行版本协商、服务发现和视频通道。",
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
        if (::usbProbe.isInitialized) usbProbe.close()
        super.onDestroy()
    }

    private fun requestCapture() {
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            projectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
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
        Button(this).apply { text = value; setOnClickListener { action() } }

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

    companion object { private const val REQUEST_CAPTURE = 4101 }
}
