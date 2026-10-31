package com.civicmirror.usb

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Safe USB AOA diagnostic. It only requests accessory permission and opens/closes the
 * accessory descriptor; it does not send Android Auto protocol bytes or claim a car session.
 */
class UsbAccessoryProbe(
    private val activity: Activity,
    private val onStatus: (String) -> Unit
) {
    private val usbManager = activity.getSystemService(UsbManager::class.java)
    private val permissionAction = "${activity.packageName}.USB_ACCESSORY_PERMISSION"
    private var receiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != permissionAction) return
            val accessory = getAccessory(intent)
            if (accessory == null) {
                onStatus("USB 检测失败：系统没有返回 accessory 信息。")
                return
            }
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                openAndReport(accessory)
            } else {
                onStatus("已检测到 Android Auto USB accessory，但系统未授予 CivicMirror 访问权限。")
            }
        }
    }

    init {
        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(permissionReceiver, filter)
        }
        receiverRegistered = true
    }

    fun probe() {
        val accessories = usbManager.accessoryList?.toList().orEmpty()
        if (accessories.isEmpty()) {
            onStatus(
                "未检测到 USB accessory。请用支持数据传输的 USB 线连接车机 USB 口，" +
                    "解锁手机并启动 Android Auto 后再检测。"
            )
            return
        }

        val accessory = accessories.firstOrNull(::isAndroidAutoAccessory)
        if (accessory == null) {
            val descriptions = accessories.joinToString { describe(it) }
            onStatus("检测到 USB accessory，但未识别为 Android Auto：$descriptions")
            return
        }

        if (usbManager.hasPermission(accessory)) {
            openAndReport(accessory)
            return
        }

        onStatus("已发现 Android Auto USB accessory，正在请求系统 USB 访问授权……")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val permissionIntent = PendingIntent.getBroadcast(
            activity,
            5107,
            Intent(permissionAction).setPackage(activity.packageName),
            flags
        )
        usbManager.requestPermission(accessory, permissionIntent)
    }

    fun dispose() {
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(permissionReceiver) }
            receiverRegistered = false
        }
    }

    private fun openAndReport(accessory: UsbAccessory) {
        var descriptor: ParcelFileDescriptor? = null
        try {
            descriptor = usbManager.openAccessory(accessory)
            if (descriptor == null) {
                onStatus("系统已授权，但 openAccessory 返回空句柄；AAP 通信尚未开始。")
                return
            }
            onStatus(
                "USB AOA 访问测试成功：${describe(accessory)}。已打开并关闭 USB 句柄。" +
                    "\n下一阶段才会进行 AAP/TLS 握手；当前尚未传输视频。"
            )
            Log.i(TAG, "USB accessory open succeeded: ${describe(accessory)}")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to open USB accessory", error)
            onStatus("USB accessory 已发现，但打开失败：${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { descriptor?.close() }
        }
    }

    private fun isAndroidAutoAccessory(accessory: UsbAccessory): Boolean {
        val manufacturer = accessory.manufacturer.orEmpty()
        val model = accessory.model.orEmpty()
        return manufacturer.equals("Android", ignoreCase = true) &&
            (model.startsWith("Android Auto", ignoreCase = true) ||
                model.equals("Android Open Automotive Protocol", ignoreCase = true))
    }

    private fun describe(accessory: UsbAccessory): String =
        "manufacturer=${accessory.manufacturer ?: "?"}, " +
            "model=${accessory.model ?: "?"}, version=${accessory.version ?: "?"}"

    @Suppress("DEPRECATION")
    private fun getAccessory(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }

    companion object {
        private const val TAG = "CivicMirrorUSB"
    }
}
