package com.iosbridge.core

data class DeviceReport(
    val kernel: String,
    val androidRelease: String,
    val adbRootAvailable: Boolean,
    val configFsAvailable: Boolean,
    val functionFsAvailable: Boolean,
    val udcControllers: List<String>,
    val warnings: List<String>
)
