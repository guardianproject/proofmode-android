package org.witness.proofmode.plugins.wallet.infra

internal fun String.hexToByteArray(): ByteArray {
    val clean = this.removePrefix("0x")
    val result = ByteArray(clean.length / 2)
    for (i in result.indices) {
        result[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return result
}

/** Public: `:plugin-location-protocol` shares it for log and UI text (e.g. 0x1234…abcd). */
fun abbreviateAddress(address: String): String {
    if (address.length <= 12) return address
    return "${address.take(6)}…${address.takeLast(4)}"
}

internal fun truncateHex(value: String, maxChars: Int = 18): String {
    if (value.length <= maxChars) return value
    return "${value.take(maxChars)}…(${value.length} chars)"
}
