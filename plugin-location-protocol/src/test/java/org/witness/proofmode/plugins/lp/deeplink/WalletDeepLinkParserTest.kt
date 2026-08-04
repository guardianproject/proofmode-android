package org.witness.proofmode.plugins.lp.deeplink

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.deeplink.ParamOutcome

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WalletDeepLinkParserTest {

    private val parser = WalletDeepLinkParser()

    @Test
    fun wrongHost_isNotWalletRoute() {
        val uri = Uri.parse("proofmode://settings?chain=eip155:42161")
        val result = parser.parse(uri)
        assertEquals("settings", result.host)
        assertFalse(result.isWalletRoute)
        assertNull(result.chain)
        assertNull(result.sponsor)
        assertNull(result.projectId)
    }

    @Test
    fun validFullUri_rawChain_parsesAllParams() {
        val uri = Uri.parse(
            "proofmode://wallet?chain=eip155:42161&sponsor=true" +
                "&projectId=550e8400-e29b-41d4-a716-446655440000",
        )
        val result = parser.parse(uri)
        assertEquals(true, result.isWalletRoute)
        assertEquals(ParamOutcome.VALID, result.chain?.outcome)
        assertEquals("eip155:42161", result.chain?.value)
        assertEquals(ParamOutcome.VALID, result.sponsor?.outcome)
        assertEquals(true, result.sponsor?.value)
        assertEquals(ParamOutcome.VALID, result.projectId?.outcome)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", result.projectId?.value)
    }

    @Test
    fun validFullUri_encodedChainColon_parsesChain() {
        val uri = Uri.parse(
            "proofmode://wallet?chain=eip155%3A42161&sponsor=false" +
                "&projectId=550e8400-e29b-41d4-a716-446655440000",
        )
        val result = parser.parse(uri)
        assertEquals(ParamOutcome.VALID, result.chain?.outcome)
        assertEquals("eip155:42161", result.chain?.value)
    }

    @Test
    fun chainOnly_partialLink_parsesChainLeavesOthersAbsent() {
        val uri = Uri.parse("proofmode://wallet?chain=eip155:42161")
        val result = parser.parse(uri)
        assertEquals(ParamOutcome.VALID, result.chain?.outcome)
        assertEquals("eip155:42161", result.chain?.value)
        assertEquals(ParamOutcome.ABSENT, result.sponsor?.outcome)
        assertEquals(ParamOutcome.ABSENT, result.projectId?.outcome)
    }

    @Test
    fun noQueryParams_allAbsentStillWalletRoute() {
        val uri = Uri.parse("proofmode://wallet")
        val result = parser.parse(uri)
        assertEquals(true, result.isWalletRoute)
        assertEquals(ParamOutcome.ABSENT, result.chain?.outcome)
        assertEquals(ParamOutcome.ABSENT, result.sponsor?.outcome)
        assertEquals(ParamOutcome.ABSENT, result.projectId?.outcome)
    }

    @Test
    fun unsupportedChain_whenKeyPresent_isInvalid() {
        val uri = Uri.parse("proofmode://wallet?chain=eip155:99999")
        val result = parser.parse(uri)
        assertEquals(ParamOutcome.INVALID, result.chain?.outcome)
        assertEquals(null, result.chain?.value)
    }

    @Test
    fun malformedChain_whenKeyPresent_isInvalid() {
        val uri = Uri.parse("proofmode://wallet?chain=not-a-caip2-id")
        val result = parser.parse(uri)
        assertEquals(ParamOutcome.INVALID, result.chain?.outcome)
        assertNotNull(result.chain)
    }

    @Test
    fun invalidSponsor_validChainStillParseable() {
        val uri = Uri.parse("proofmode://wallet?chain=eip155:42161&sponsor=maybe")
        val result = parser.parse(uri)
        assertEquals(ParamOutcome.VALID, result.chain?.outcome)
        assertEquals("eip155:42161", result.chain?.value)
        assertEquals(ParamOutcome.INVALID, result.sponsor?.outcome)
    }

    @Test
    fun invalidProjectId_validChainStillParseable() {
        val uri = Uri.parse("proofmode://wallet?chain=eip155:42161&projectId=not-a-uuid")
        val result = parser.parse(uri)
        assertEquals(ParamOutcome.VALID, result.chain?.outcome)
        assertEquals(ParamOutcome.INVALID, result.projectId?.outcome)
    }

    @Test
    fun sponsorCaseInsensitive_acceptsTrueAndFalse() {
        val trueUri = Uri.parse("proofmode://wallet?chain=eip155:42161&sponsor=TRUE")
        val falseUri = Uri.parse("proofmode://wallet?chain=eip155:42161&sponsor=False")
        assertEquals(true, parser.parse(trueUri).sponsor?.value)
        assertEquals(false, parser.parse(falseUri).sponsor?.value)
    }
}
