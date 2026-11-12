package org.witness.proofmode.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for shared Filebase failure copy (no Robolectric). */
class FilebaseFailureMessagesTest {

    private val samePolicyFallback = "SAME_POLICY_FALLBACK"
    private val credentialsFallback = "CREDENTIALS_FALLBACK"
    private val ipfsCredentialsFallback = "IPFS_CREDENTIALS_FALLBACK"

    private fun fmt(raw: String?) =
        formatFilebaseFailureMessage(raw, samePolicyFallback, credentialsFallback, ipfsCredentialsFallback)

    @Test
    fun blankOrNull_usesSamePolicyFallback() {
        assertEquals(samePolicyFallback, fmt(null))
        assertEquals(samePolicyFallback, fmt(""))
        assertEquals(samePolicyFallback, fmt("   "))
    }

    @Test
    fun accountProblem_leadsWithFallback_andAppendsMessageBody() {
        val raw =
            "Upload failed: 403 Forbidden key=abc/hash.mp4 contentLength=35424055 " +
                "body=<Error><Code>AccountProblem</Code>" +
                "<Message>Free accounts are limited to 25MB video uploads. Please upgrade your plan.</Message>" +
                "</Error>"
        assertEquals(
            "$samePolicyFallback\n\nFree accounts are limited to 25MB video uploads. Please upgrade your plan.",
            fmt(raw),
        )
    }

    @Test
    fun accountProblem_withoutMessageTags_usesSamePolicyFallback() {
        val raw =
            "Upload failed: 403 Forbidden body=<Error><Code>AccountProblem</Code></Error>"
        assertEquals(samePolicyFallback, fmt(raw))
    }

    @Test
    fun muteIpfs403_emptyBody_usesSamePolicyFallback() {
        val raw =
            "IPFS RPC upload failed: 403 declaredContentLength=35424055 body=(empty)"
        assertEquals(samePolicyFallback, fmt(raw))
    }

    @Test
    fun muteIpfs403_bareStatus_usesSamePolicyFallback() {
        assertEquals(samePolicyFallback, fmt("IPFS RPC upload failed: 403"))
    }

    @Test
    fun otherNonBlank_passthroughTrimmed() {
        assertEquals("Connection reset", fmt("  Connection reset  "))
    }

    @Test
    fun neverSuggestsRetry() {
        val samples =
            listOf(
                null,
                "IPFS RPC upload failed: 403 body=(empty)",
                "Upload failed: 403 body=<Error><Code>AccountProblem</Code>" +
                    "<Message>Free accounts are limited to 25MB video uploads.</Message></Error>",
                "some other provider error",
            )
        for (raw in samples) {
            val out = fmt(raw)
            assertFalse("must not suggest Retry: $out", out.contains("retry", ignoreCase = true))
        }
    }

    @Test
    fun ipfs403WithBody_isCredentialFailure_notMuteFallback() {
        val raw =
            "IPFS RPC upload failed: 403 declaredContentLength=1 body=Gateway timeout detail"
        assertEquals(ipfsCredentialsFallback, fmt(raw))
        assertTrue(isFilebaseReconfigureFailure(raw))
    }

    @Test
    fun reconfigure_trueForAccountProblem() {
        assertTrue(
            isFilebaseReconfigureFailure(
                "Upload failed: 403 Forbidden body=<Error><Code>AccountProblem</Code></Error>",
            ),
        )
    }

    @Test
    fun reconfigure_trueForMuteIpfs403() {
        assertTrue(isFilebaseReconfigureFailure("IPFS RPC upload failed: 403 body=(empty)"))
    }

    @Test
    fun reconfigure_falseForGenericSocketTimeout() {
        assertFalse(isFilebaseReconfigureFailure("timeout"))
    }

    @Test
    fun reconfigure_falseForMessageOnlyBodyThatIsNotAccountProblemOrMuteIpfs403() {
        assertFalse(
            isFilebaseReconfigureFailure(
                "Upload failed: 503 body=<Error><Code>SlowDown</Code>" +
                    "<Message>Please reduce your request rate.</Message></Error>",
            ),
        )
    }

    @Test
    fun s3CredentialFailure_leadsWithCredentialsCopy_andReconfigures() {
        val raw =
            "Upload failed: 404 Not Found body=<Error><Code>NoSuchBucket</Code>" +
                "<Message>The specified bucket does not exist</Message></Error>"
        assertEquals("$credentialsFallback\n\nThe specified bucket does not exist", fmt(raw))
        assertTrue(isFilebaseReconfigureFailure(raw))
    }

    @Test
    fun ipfs401_showsIpfsCopyAndJsonReason_andReconfigures() {
        val raw =
            "IPFS RPC upload failed: 401 declaredContentLength=634093 " +
                "body={\"Message\":\"api key does not exist\",\"Code\":0,\"Type\":\"error\"}"
        assertEquals("$ipfsCredentialsFallback\n\napi key does not exist", fmt(raw))
        assertTrue(isFilebaseReconfigureFailure(raw))
    }
}
