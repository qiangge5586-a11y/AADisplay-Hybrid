package com.iosbridge.transport

enum class TransportState { IDLE, INSPECTING, PREPARED, ACTIVE, RECOVERING, FAILED }

data class TransportResult(
    val state: TransportState,
    val message: String,
    val details: Map<String, String> = emptyMap()
)

interface Transport {
    suspend fun inspect(): TransportResult
    suspend fun prepare(): TransportResult
    suspend fun recover(): TransportResult
}
