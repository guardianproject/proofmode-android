package org.witness.proofmode.plugins.lp.wallet

import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.BuildConfig

/**
 * Documents IntegrationDiplomat gate: UI section is shown only when compile flag is true.
 * Robolectric activity test is optional; this guards against accidental flag regression in debug.
 */
class WalletSponsorshipFeatureGateTest {
    @Test
    fun featureSponsorshipEnabled_isTrueInDebugUnitTests() {
        assertTrue(BuildConfig.FEATURE_SPONSORSHIP_ENABLED)
    }
}
