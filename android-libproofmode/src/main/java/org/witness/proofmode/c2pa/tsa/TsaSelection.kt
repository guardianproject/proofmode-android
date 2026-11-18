package org.witness.proofmode.c2pa.tsa

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.witness.proofmode.c2pa.SigningMode

/**
 * The built-in TSA before ProofSign held the Trufo key. Still recognised as a saved setting
 * meaning "the default", because the settings dialog pre-filled it; never called any more.
 */
const val LEGACY_TRUFO_TSA_URL = "https://tsa.trufo.ai/"

private const val PROOFSIGN_TIMESTAMP_PATH = "/api/v1/timestamp"

/** ProofSign's authenticated timestamp proxy on [serverUrl]: the default TSA. */
fun proofSignTimestampUrl(serverUrl: String): String =
    serverUrl.trim().trimEnd('/') + PROOFSIGN_TIMESTAMP_PATH

/**
 * Whether a saved TSA setting means "the default" rather than a TSA the user chose. Any
 * ProofSign timestamp endpoint counts, not only the current server's: the settings dialog
 * pre-fills and saves it, and without device headers it could only ever answer 401.
 */
internal fun isDefaultTsa(configured: String): Boolean {
    val value = configured.trim()
    if (value.isEmpty()) return true
    val url = value.toHttpUrlOrNull() ?: return false
    if (url.encodedPath.trimEnd('/') == PROOFSIGN_TIMESTAMP_PATH) return true
    return normalized(value) == normalized(LEGACY_TRUFO_TSA_URL)
}

/** Scheme and host are lowercased by [HttpUrl]; a trailing slash is not significant here. */
private fun normalized(value: String): String? =
    value.trim().toHttpUrlOrNull()?.toString()?.trimEnd('/')

/**
 * Picks the TSA for one sign, or null to sign without a timestamp.
 *
 * - [chosen] is the mode the user picked; [effective] is the signer that will actually run
 *   (REMOTE falls back to KEYSTORE without Play Integrity or recent security patches).
 * - Local modes timestamp on-device. If the on-device TSA is down they use ProofSign only when
 *   this device is already registered with it, and otherwise sign untimestamped: ProofSign
 *   rejects unregistered devices, and Trufo's keyless test TSA is not production-trusted.
 * - REMOTE with the default setting uses ProofSign through the signing loopback relay. A REMOTE
 *   user falling back to KEYSTORE is not registered on a device without Play Integrity, so
 *   unless registered they get the on-device TSA instead.
 * - Any other configured URL is the user's own TSA and is called directly, with no device
 *   headers ([resolveLoopback] still sends loopback URLs to the on-device TSA).
 */
internal fun selectTsaUrl(
    chosen: SigningMode,
    effective: SigningMode,
    configured: String,
    deviceRegistered: Boolean,
    localTsaUrl: () -> String?,
    proofSignProxyUrl: () -> String?,
    resolveLoopback: (String) -> String,
): String? {
    if (chosen != SigningMode.REMOTE) {
        return localTsaUrl() ?: if (deviceRegistered) proofSignProxyUrl() else null
    }
    if (isDefaultTsa(configured)) {
        return if (effective == SigningMode.REMOTE || deviceRegistered) proofSignProxyUrl() else localTsaUrl()
    }
    return resolveLoopback(configured.trim())
}
