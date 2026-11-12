package org.witness.proofmode.plugins.lp.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkResult

class WalletDeepLinkResultTest {

    @Test
    fun rejectedResult_carriesUserMessage() {
        val result = WalletDeepLinkResult(
            rejected = true,
            userMessage = "Unsupported chain",
        )
        assertTrue(result.rejected)
        assertEquals("Unsupported chain", result.userMessage)
    }
}
