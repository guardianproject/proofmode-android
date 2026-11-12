package org.witness.proofmode.c2pa.proofsign

/**
 * The device-key authentication a ProofSign request carries outside a JSON body, as on
 * `/api/v1/timestamp`: `X-Device-ID`, `X-Device-Timestamp` and `X-Device-Signature`.
 * [signature] never goes into a log.
 */
data class DeviceAuthHeaders(
    val deviceId: String,
    val timestampMillis: Long,
    val signature: String,
)

/** Produces [DeviceAuthHeaders] over the exact bytes about to be sent. */
fun interface DeviceRequestSigner {
    fun headersFor(body: ByteArray): DeviceAuthHeaders
}

/**
 * Signs `deviceId|timestamp|claimBinding(body)`, the same input format the sign request's
 * `device_signature` uses, built from the same [ClaimBinding] primitives. [clock] is read on
 * every call so each request carries a fresh timestamp.
 */
internal fun deviceRequestSigner(
    deviceId: String,
    clock: () -> Long,
    sign: (ByteArray) -> String,
): DeviceRequestSigner = DeviceRequestSigner { body ->
    val timestamp = clock()
    val input = ClaimBinding.deviceRequestSigningInput(deviceId, timestamp, ClaimBinding.claimBinding(body))
    DeviceAuthHeaders(deviceId, timestamp, sign(input.toByteArray(Charsets.UTF_8)))
}
