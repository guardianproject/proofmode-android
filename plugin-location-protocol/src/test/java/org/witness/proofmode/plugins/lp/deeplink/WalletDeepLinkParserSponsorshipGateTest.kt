package org.witness.proofmode.plugins.lp.deeplink

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.deeplink.ParamOutcome
import org.witness.proofmode.plugins.wallet.infra.BuildConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletDeepLinkParserSponsorshipGateTest {

    @Test
    fun featureSponsorshipEnabled_isTrueInDebugUnitTests() {
        assertTrue(BuildConfig.FEATURE_SPONSORSHIP_ENABLED)
    }

    @Test
    fun sponsorshipDisabled_presentSponsorAndProjectId_areAbsentNotValid() {
        val parser = WalletDeepLinkParser(sponsorshipEnabled = false)
        val uri = Uri.parse(
            "proofmode://wallet?chain=eip155:42161&sponsor=true" +
                "&projectId=550e8400-e29b-41d4-a716-446655440000",
        )
        val result = parser.parse(uri)
        assertEquals(ParamOutcome.VALID, result.chain?.outcome)
        assertEquals(ParamOutcome.ABSENT, result.sponsor?.outcome)
        assertEquals(ParamOutcome.ABSENT, result.projectId?.outcome)
    }
}
