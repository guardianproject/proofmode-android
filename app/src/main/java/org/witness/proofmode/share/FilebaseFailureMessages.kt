package org.witness.proofmode.share

import android.content.Context
import org.witness.proofmode.R

private val MESSAGE_TAG =
    Regex("<Message>(.*?)</Message>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

private const val IPFS_401_PREFIX = "IPFS RPC upload failed: 401"
private const val IPFS_403_PREFIX = "IPFS RPC upload failed: 403"

// Filebase's IPFS RPC answers with JSON such as {"Message":"api key does not exist",...}.
private val IPFS_JSON_MESSAGE = Regex("\"Message\"\\s*:\\s*\"([^\"]*)\"")

// S3 error codes that mean the access key, secret, or bucket in Auto sync settings is wrong.
private val CREDENTIAL_ERROR_CODES =
    listOf("AccessDenied", "NoSuchBucket", "InvalidAccessKeyId", "SignatureDoesNotMatch")

/**
 * Formats provider failure for UI. Lead with our agnostic copy for AccountProblem / mute IPFS 403;
 * when a provider Message (or other useful detail) exists, append it for context.
 */
internal fun formatFilebaseFailureMessage(
    rawMessage: String?,
    samePolicyFallback: String,
    credentialsFallback: String,
    ipfsCredentialsFallback: String,
): String {
    val raw = rawMessage?.trim().orEmpty()
    if (raw.isEmpty()) return samePolicyFallback

    val messageBody = MESSAGE_TAG.find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    if (isIpfsCredentialFailure(raw)) {
        val ipfsReason = IPFS_JSON_MESSAGE.find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return appendDetail(ipfsCredentialsFallback, ipfsReason)
    }
    if (isS3CredentialFailure(raw)) return appendDetail(credentialsFallback, messageBody)
    if (messageBody.isNotEmpty()) {
        return appendDetail(samePolicyFallback, messageBody)
    }

    if (raw.contains("AccountProblem")) {
        return samePolicyFallback
    }

    if (isMuteIpfs403(raw)) {
        return samePolicyFallback
    }

    return raw
}

/** Reads the three failure strings from resources and formats [rawMessage] for UI. */
internal fun formatFilebaseFailureMessage(context: Context, rawMessage: String?): String =
    formatFilebaseFailureMessage(
        rawMessage,
        samePolicyFallback = context.resources.getString(R.string.filebase_failure_same_policy),
        credentialsFallback = context.resources.getString(R.string.filebase_failure_credentials),
        ipfsCredentialsFallback = context.resources.getString(R.string.filebase_failure_ipfs_credentials),
    )

/** True when the user should fix Auto sync settings: AccountProblem, mute IPFS 403, or a credential failure. */
internal fun isFilebaseReconfigureFailure(rawMessage: String?): Boolean {
    val raw = rawMessage?.trim().orEmpty()
    if (raw.isEmpty()) return false
    if (raw.contains("AccountProblem")) return true
    if (raw.contains(IPFS_401_PREFIX) || raw.contains(IPFS_403_PREFIX)) return true
    return isS3CredentialFailure(raw)
}

// A 403 without a body stays with the mute-403 copy, since it may also mean the file is too large.
private fun isIpfsCredentialFailure(raw: String): Boolean =
    raw.contains(IPFS_401_PREFIX) ||
        (raw.contains(IPFS_403_PREFIX) && !isMuteIpfs403(raw))

private fun isS3CredentialFailure(raw: String): Boolean =
    CREDENTIAL_ERROR_CODES.any { raw.contains("<Code>$it</Code>") }

private fun appendDetail(lead: String, detail: String): String {
    if (detail.isBlank() || detail == lead) return lead
    return "$lead\n\n$detail"
}

internal fun isMuteIpfs403(raw: String): Boolean {
    if (!raw.contains(IPFS_403_PREFIX)) return false
    if (raw.contains("body=(empty)")) return true
    // Bare status / length-only lines with no useful prose body
    val afterPrefix = raw.substringAfter(IPFS_403_PREFIX).trim()
    if (afterPrefix.isEmpty()) return true
    if (afterPrefix.startsWith("declaredContentLength=") &&
        !afterPrefix.contains("body=")
    ) {
        return true
    }
    // body= present but empty / (empty) already handled; any other body= with content → not mute
    val bodyIdx = afterPrefix.indexOf("body=")
    if (bodyIdx < 0) return true
    val body = afterPrefix.substring(bodyIdx + "body=".length).trim()
    return body.isEmpty() || body == "(empty)"
}
