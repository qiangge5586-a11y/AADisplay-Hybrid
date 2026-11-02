package com.iosbridge.transport

import java.io.ByteArrayOutputStream

/** Minimal FunctionFS v2 vendor-specific bulk endpoint descriptor experiment; not an Apple-certified profile. */
object FunctionFsDescriptorSet {
    private const val DESCRIPTORS_MAGIC_V2 = 3
    private const val STRINGS_MAGIC = 2
    private const val HAS_FS_DESC = 1
    private const val HAS_HS_DESC = 2
    private const val USB_DT_INTERFACE = 4
    private const val USB_DT_ENDPOINT = 5
    private const val USB_CLASS_VENDOR_SPEC = 0xFF
    private const val USB_ENDPOINT_XFER_BULK = 2
    data class Blobs(val descriptors: ByteArray, val strings: ByteArray)

    fun build(interfaceString: String = "iAP2 Data"): Blobs {
        require(interfaceString.isNotBlank())
        require(interfaceString.toByteArray(Charsets.UTF_8).size <= 200)
        val fs = listOf(interfaceDescriptor(), endpointDescriptor(0x01, 64), endpointDescriptor(0x81, 64)).flatten().toByteArray()
        val hs = listOf(interfaceDescriptor(), endpointDescriptor(0x01, 512), endpointDescriptor(0x81, 512)).flatten().toByteArray()
        val body = ByteArrayOutputStream().apply {
            writeLe32(HAS_FS_DESC or HAS_HS_DESC); writeLe32(3); writeLe32(3); write(fs); write(hs)
        }.toByteArray()
        val descriptors = ByteArrayOutputStream().apply {
            writeLe32(DESCRIPTORS_MAGIC_V2); writeLe32(8 + body.size); write(body)
        }.toByteArray()
        val text = interfaceString.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val strings = ByteArrayOutputStream().apply {
            writeLe32(STRINGS_MAGIC); writeLe32(16 + 2 + text.size)
            writeLe32(1); writeLe32(1); writeLe16(0x0409); write(text)
        }.toByteArray()
        return Blobs(descriptors, strings)
    }

    private fun interfaceDescriptor(): List<Byte> = listOf(9, USB_DT_INTERFACE, 0, 0, 2, USB_CLASS_VENDOR_SPEC, 0, 0, 1).map(Int::toByte)
    private fun endpointDescriptor(address: Int, maxPacket: Int): List<Byte> = listOf(
        7, USB_DT_ENDPOINT, address, USB_ENDPOINT_XFER_BULK,
        maxPacket and 0xFF, (maxPacket ushr 8) and 0xFF, 0
    ).map(Int::toByte)
    private fun ByteArrayOutputStream.writeLe32(v: Int) {
        write(v and 255); write((v ushr 8) and 255); write((v ushr 16) and 255); write((v ushr 24) and 255)
    }
    private fun ByteArrayOutputStream.writeLe16(v: Int) { write(v and 255); write((v ushr 8) and 255) }
}
