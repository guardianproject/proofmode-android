package org.witness.proofmode.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for shared Filebase failure copy (no Robolectric). */
class FilebaseFailureMessagesTest {

    private val accountFallback = "ACCOUNT_FALLBACK_SAME_POLICY"
    private val muteFallback = "MUTE_IPFS_403_SAME_POLICY"

    private fun fmt(raw: String?) =
        formatFilebaseFailureMessage(raw, accountFallback, muteFallback)

    @Test
    fun blankOrNull_usesMuteIpfs403Fallback() {
        assertEquals(muteFallback, fmt(null))
        assertEquals(muteFallback, fmt(""))
        assertEquals(muteFallback, fmt("   "))
    }

    @Test
    fun accountProblem_leadsWithFallback_andAppendsMessageBody() {
        val raw =
            "Upload failed: 403 Forbidden key=abc/hash.mp4 contentLength=35424055 " +
                "body=<Error><Code>AccountProblem</Code>" +
                "<Message>Free accounts are limited to 25MB video uploads. Please upgrade your plan.</Message>" +
                "</Error>"
        assertEquals(
            "$accountFallback\n\nFree accounts are limited to 25MB video uploads. Please upgrade your plan.",
            fmt(raw),
        )
    }

    @Test
    fun accountProblem_withoutMessageTags_usesAccountProblemFallback() {
        val raw =
            "Upload failed: 403 Forbidden body=<Error><Code>AccountProblem</Code></Error>"
        assertEquals(accountFallback, fmt(raw))
    }

    @Test
    fun muteIpfs403_emptyBody_usesMuteFallback() {
        val raw =
            "IPFS RPC upload failed: 403 declaredContentLength=35424055 body=(empty)"
        assertEquals(muteFallback, fmt(raw))
    }

    @Test
    fun muteIpfs403_bareStatus_usesMuteFallback() {
        assertEquals(muteFallback, fmt("IPFS RPC upload failed: 403"))
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
    fun ipfs403WithUsefulBody_passthroughNotMuteFallback() {
        val raw =
            "IPFS RPC upload failed: 403 declaredContentLength=1 body=Gateway timeout detail"
        val out = fmt(raw)
        assertTrue(out.contains("Gateway timeout detail"))
        assertFalse(out == muteFallback)
    }
}
