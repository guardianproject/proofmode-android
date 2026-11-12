package org.witness.proofmode.plugins.lp.deeplink

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletDeepLinkContractTest {

    @Test
    fun schemeAndHost_constantsMatchSpec() {
        assertEquals("proofmode", WalletDeepLinkContract.SCHEME)
        assertEquals("wallet", WalletDeepLinkContract.HOST_WALLET)
    }

    @Test
    fun extraKeys_areStableForPhase3() {
        assertEquals(
            "org.witness.proofmode.plugins.lp.EXTRA_DEEP_LINK_REJECTED",
            WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED,
        )
        assertEquals(
            "org.witness.proofmode.plugins.lp.EXTRA_DEEP_LINK_MESSAGE",
            WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE,
        )
    }

    @Test
    fun putWalletDeepLinkResult_mapsAllExtras() {
        val result = WalletDeepLinkResult(
            userMessage = "Applied",
            rejected = false,
        )
        val intent = Intent().putWalletDeepLinkResult(result)

        assertFalse(intent.getBooleanExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED, true))
        assertEquals("Applied", intent.getStringExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE))
    }

    @Test
    fun putWalletDeepLinkResult_mapsRejectedExtra() {
        val result = WalletDeepLinkResult(rejected = true, userMessage = "Bad chain")
        val intent = Intent().putWalletDeepLinkResult(result)

        assertTrue(intent.getBooleanExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_REJECTED, false))
        assertEquals("Bad chain", intent.getStringExtra(WalletDeepLinkContract.EXTRA_DEEP_LINK_MESSAGE))
    }
}
