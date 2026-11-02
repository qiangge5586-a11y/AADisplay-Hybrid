package com.iosbridge.transport

/**
 * Describes a candidate FunctionFS endpoint layout for offline inspection.
 * This is not an Apple/iAP2-certified USB descriptor set.
 */
data class FunctionFsDescriptorSet(
    val interfaceNumber: Int,
    val interfaceClass: Int,
    val interfaceSubclass: Int,
    val interfaceProtocol: Int,
    val endpoints: List<Endpoint>
) {
    data class Endpoint(
        val address: Int,
        val attributes: Int,
        val maxPacketSize: Int,
        val interval: Int
    )

    fun validate(): List<String> = buildList {
        if (interfaceNumber !in 0..255) add("interfaceNumber out of range")
        if (interfaceClass !in 0..255) add("interfaceClass out of range")
        if (interfaceSubclass !in 0..255) add("interfaceSubclass out of range")
        if (interfaceProtocol !in 0..255) add("interfaceProtocol out of range")
        if (endpoints.isEmpty()) add("no endpoints declared")
        if (endpoints.map { it.address }.distinct().size != endpoints.size) add("duplicate endpoint address")
        endpoints.forEach {
            if (it.address !in 0..255) add("endpoint address out of range")
            if (it.attributes !in 0..3) add("unsupported endpoint attributes")
            if (it.maxPacketSize !in 1..1024) add("maxPacketSize out of range")
            if (it.interval !in 0..255) add("interval out of range")
        }
    }
}
