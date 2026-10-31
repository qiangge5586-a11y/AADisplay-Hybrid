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
import com.civicmirror.protocol.AapStreamSession
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the phone-side Android Open Accessory descriptor alive.
 *
 * The streams are intentionally exposed only as raw duplex transport at this stage.
 * Do not attach AapStreamSession yet: its current frame codec is plaintext-only and
 * TLS/AAP record layering has not been implemented.
 */
class UsbAccessoryProbe(
    private val activity: Activity,
    private val onStatus: (String) -> Unit
) : Closeable {
    private val usbManager = activity.getSystemService(UsbManager::class.java)
    private val permissionAction = "${activity.packageName}.USB_ACCESSORY_PERMISSION"
    private val detachAction = UsbManager.ACTION_USB_ACCESSORY_DETACHED
    private var receiverRegistered = false
    private var transport: RawAccessoryTransport? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val accessory = getAccessory(intent)
                    if (accessory == null) {
                        onStatus("USB 连接失败：系统没有返回 accessory 信息。")
                    } else if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        connectAccessory(accessory)
                    } else {
                        onStatus("已发现 Android Auto USB accessory，但系统未授予访问权限。")
                    }
                }
                detachAction -> {
                    closeTransport()
                    onStatus("USB accessory 已断开，双向数据流已关闭。")
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(detachAction)
        }
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
            onStatus("未检测到 USB accessory。请连接车机 USB 口、解锁手机并启动 Android Auto 后重试。")
            return
        }
        val accessory = accessories.firstOrNull(::isAndroidAutoAccessory)
            ?: accessories.singleOrNull()
        if (accessory == null) {
            onStatus("检测到多个 USB accessory，且无法唯一识别 Android Auto；为避免打开错误设备，已停止。")
            return
        }
        if (usbManager.hasPermission(accessory)) {
            connectAccessory(accessory)
            return
        }
        onStatus("已发现 USB accessory，正在请求系统访问授权……")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val permissionIntent = PendingIntent.getBroadcast(
            activity, 5107, Intent(permissionAction).setPackage(activity.packageName), flags
        )
        usbManager.requestPermission(accessory, permissionIntent)
    }

    fun disconnect() {
        closeTransport()
        onStatus("USB 双向数据流已由用户关闭。")
    }

    override fun close() {
        closeTransport()
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(permissionReceiver) }
            receiverRegistered = false
        }
    }

    private fun connectAccessory(accessory: UsbAccessory) {
        closeTransport()
        var original: ParcelFileDescriptor? = null
        var readDescriptor: ParcelFileDescriptor? = null
        var writeDescriptor: ParcelFileDescriptor? = null
        try {
            original = usbManager.openAccessory(accessory)
                ?: throw IllegalStateException("openAccessory 返回空句柄")
            // dup() gives each Java stream its own fd; closing one stream cannot
            // accidentally close the descriptor backing the other direction.
            readDescriptor = ParcelFileDescriptor.dup(original.fileDescriptor)
            writeDescriptor = ParcelFileDescriptor.dup(original.fileDescriptor)
            original.close()
            original = null

            val input = FileInputStream(readDescriptor.fileDescriptor)
            val output = FileOutputStream(writeDescriptor.fileDescriptor)
            val opened = RawAccessoryTransport(input, output, readDescriptor, writeDescriptor)
            readDescriptor = null
            writeDescriptor = null
            transport = opened
            onStatus(
                "USB AOA 双向句柄已保持打开：${describe(accessory)}\n" +
                    "输入/输出文件描述符已分别复制，当前仅建立原始 USB 数据流；尚未发送 AAP、TLS 或视频数据。"
            )
            Log.i(TAG, "Persistent USB accessory transport opened: ${describe(accessory)}")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to establish persistent USB transport", error)
            onStatus("USB 传输打开失败：${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { original?.close() }
            runCatching { readDescriptor?.close() }
            runCatching { writeDescriptor?.close() }
        }
    }

    private fun closeTransport() {
        val current = transport
        transport = null
        runCatching { current?.close() }
    }

    private fun isAndroidAutoAccessory(accessory: UsbAccessory): Boolean {
        val manufacturer = accessory.manufacturer.orEmpty()
        val model = accessory.model.orEmpty()
        return manufacturer.equals("Android", ignoreCase = true) &&
            (model.startsWith("Android Auto", ignoreCase = true) ||
                model.equals("Android Open Automotive Protocol", ignoreCase = true))
    }

    private fun describe(accessory: UsbAccessory): String =
        "manufacturer=${accessory.manufacturer ?: "?"}, model=${accessory.model ?: "?"}, version=${accessory.version ?: "?"}"

    @Suppress("DEPRECATION")
    private fun getAccessory(intent: Intent): UsbAccessory? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }

    private class RawAccessoryTransport(
        private val input: InputStream,
        private val output: OutputStream,
        private val readDescriptor: ParcelFileDescriptor,
        private val writeDescriptor: ParcelFileDescriptor
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { readDescriptor.close() }
            runCatching { writeDescriptor.close() }
        }
    }

    companion object {
        private const val TAG = "CivicMirrorUSB"
    }
}
