package com.iosbridge.core

import android.content.Context
import java.io.File

class UsbBaselineStore(context: Context) {
    private val file = File(context.filesDir, "usb-baseline.txt")

    fun save(snapshot: String) {
        file.writeText(snapshot)
    }

    fun read(): String? = file.takeIf { it.isFile }?.readText()

    fun clear() {
        if (file.exists()) file.delete()
    }
}
