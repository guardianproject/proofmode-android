package org.witness.proofmode.share

private val MESSAGE_TAG =
    Regex("<Message>(.*?)</Message>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/**
 * Formats provider failure for UI. Lead with our agnostic copy for AccountProblem / mute IPFS 403;
 * when a provider Message (or other useful detail) exists, append it for context.
 */
internal fun formatFilebaseFailureMessage(
    rawMessage: String?,
    accountProblemFallback: String,
    muteIpfs403Fallback: String,
): String {
    val raw = rawMessage?.trim().orEmpty()
    if (raw.isEmpty()) return muteIpfs403Fallback

    val messageBody = MESSAGE_TAG.find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    if (messageBody.isNotEmpty()) {
        return appendDetail(accountProblemFallback, messageBody)
    }

    if (raw.contains("AccountProblem")) {
        return accountProblemFallback
    }

    if (isMuteIpfs403(raw)) {
        return muteIpfs403Fallback
    }

    return raw
}

private fun appendDetail(lead: String, detail: String): String {
    if (detail.isBlank() || detail == lead) return lead
    return "$lead\n\n$detail"
}

private fun isMuteIpfs403(raw: String): Boolean {
    if (!raw.contains("IPFS RPC upload failed: 403")) return false
    if (raw.contains("body=(empty)")) return true
    // Bare status / length-only lines with no useful prose body
    val afterPrefix = raw.substringAfter("IPFS RPC upload failed: 403").trim()
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
