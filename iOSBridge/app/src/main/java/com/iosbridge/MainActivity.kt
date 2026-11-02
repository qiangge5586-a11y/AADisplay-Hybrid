package com.iosbridge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.iosbridge.transport.ConfigFsSafetyManager
import com.iosbridge.transport.RootConfigFsSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Safe USB status + simulated handshake prototype.
 * The handshake is local-only test data; it does not contact a vehicle,
 * implement iAP2, or claim CarPlay compatibility.
 */
class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var gadgetView: TextView
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshUsbStatus()
            appendLog("USB 广播：${intent.action ?: "unknown"}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "iOSBridge 0.2.8"
            textSize = 26f
        })
        root.addView(TextView(this).apply {
            text = "USB 状态与模拟握手原型"
            textSize = 16f
            setPadding(0, 6, 0, 18)
        })
        statusView = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        root.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(Button(this).apply {
            text = "刷新 USB 状态"
            setOnClickListener { refreshUsbStatus() }
        })
        root.addView(Button(this).apply {
            text = "Root USB Gadget 只读诊断"
            setOnClickListener { runRootUsbDiagnostic(this) }
        })
        root.addView(Button(this).apply {
            text = "读取手机 USB Gadget 状态（Root，只读）"
            setOnClickListener { inspectUsbGadget() }
        })
        gadgetView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            text = "手机 USB Gadget：尚未采集。此操作只读取系统状态，不修改 USB 配置。"
            setPadding(0, 10, 0, 10)
        }
        root.addView(gadgetView)
        root.addView(Button(this).apply {
            text = "运行模拟握手测试"
            setOnClickListener { runSimulatedHandshake() }
        })
        root.addView(TextView(this).apply {
            text = "运行日志"
            textSize = 18f
            setPadding(0, 18, 0, 6)
        })
        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
            text = "等待操作。模拟握手只在本机执行，不连接车辆。"
        }
        root.addView(logView)
        setContentView(ScrollView(this).apply { addView(root) })
        refreshUsbStatus()
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(usbReceiver, filter)
            }
            receiverRegistered = true
        }
        refreshUsbStatus()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(usbReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun refreshUsbStatus() {
        val devices = usbManager.deviceList.values.toList()
        val text = buildString {
            appendLine("USB Host 状态：${if (packageManager.hasSystemFeature("android.hardware.usb.host")) "系统支持" else "系统未声明支持"}")
            appendLine("已枚举设备：${devices.size}")
            if (devices.isEmpty()) append("当前没有检测到 USB Host 设备。")
            devices.forEachIndexed { index, device ->
                appendLine()
                appendLine("设备 ${index + 1}：${device.deviceName}")
                appendLine("VID: 0x${device.vendorId.toString(16).padStart(4, '0')}  PID: 0x${device.productId.toString(16).padStart(4, '0')}")
                appendLine("接口数：${device.interfaceCount}  类别：${device.deviceClass}")
            }
            appendLine()
            append("说明：这里只枚举 USB Host 外设；不代表手机已切换为 USB Gadget/CarPlay 发送端。")
        }
        statusView.text = text
        appendLog("USB 刷新：发现 ${devices.size} 个 Host 设备")
    }

    private fun runRootUsbDiagnostic(button: Button) {
        button.isEnabled = false
        button.text = "正在进行只读诊断…"
        appendLog("开始 Root USB Gadget 只读诊断；不会修改 USB 配置。")
        Thread {
            val result = RootConfigFsSnapshot().capture()
            val rendered = if (result.success && result.snapshot != null) {
                ConfigFsSafetyManager.assess(result.snapshot).render()
            } else {
                result.message + if (result.rawOutput.isNotBlank()) "\\n" + result.rawOutput else ""
            }
            runOnUiThread {
                statusView.text = rendered
                appendLog(if (result.success) "Root USB 只读诊断完成。" else "Root USB 只读诊断未完成：" + result.message)
                button.isEnabled = true
                button.text = "Root USB Gadget 只读诊断"
            }
        }.apply { isDaemon = true; start() }
    }

    private fun inspectUsbGadget() {
        gadgetView.text = "正在请求 Root 并读取 USB Gadget 状态……"
        appendLog("开始 Root USB Gadget 只读采集")
        Thread {
            val result = RootConfigFsSnapshot().capture()
            val rendered = if (result.success && result.snapshot != null) {
                ConfigFsSafetyManager.assess(result.snapshot).render() +
                    "\n\n原始只读采集：\n" + result.rawOutput.take(6_000)
            } else {
                "USB Gadget 只读采集失败：${result.message}\n\n未执行任何 USB 写操作。"
            }
            runOnUiThread {
                gadgetView.text = rendered
                appendLog(if (result.success) "USB Gadget 只读采集完成" else "USB Gadget 只读采集失败：${result.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun runSimulatedHandshake() {
        val steps = listOf(
            "TEST_START：创建本地模拟会话",
            "LINK_REQUEST：模拟链路请求",
            "LINK_RESPONSE：模拟链路响应",
            "SESSION_READY：模拟会话状态就绪",
            "TEST_PASS：模拟握手流程完成"
        )
        appendLog("—— 模拟握手开始（非真实 iAP2）——")
        steps.forEach { appendLog(it) }
        appendLog("结果：PASS（仅验证本地状态机与界面，不代表车机连接成功）")
    }

    private fun appendLog(message: String) {
        if (!::logView.isInitialized) return
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val next = "$stamp  $message"
        logView.text = (logView.text.toString().lines() + next).takeLast(100).joinToString("\n")
    }
}
