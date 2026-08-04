package org.witness.proofmode.plugins.lp.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.witness.proofmode.plugins.lp.deeplink.ParamOutcome
import org.witness.proofmode.plugins.lp.deeplink.ParsedParam
import org.witness.proofmode.plugins.lp.deeplink.WalletDeepLinkParseResult

class WalletDeepLinkParseResultTest {

    @Test
    fun parsedParam_validCarriesValue() {
        val param = ParsedParam(outcome = ParamOutcome.VALID, value = "eip155:42161")
        assertEquals(ParamOutcome.VALID, param.outcome)
        assertEquals("eip155:42161", param.value)
    }

    @Test
    fun parseResult_defaultsToAbsentParams() {
        val result = WalletDeepLinkParseResult(host = "wallet", isWalletRoute = true)
        assertNull(result.chain)
        assertNull(result.sponsor)
        assertNull(result.projectId)
        assertEquals("wallet", result.host)
        assertEquals(true, result.isWalletRoute)
    }
}
