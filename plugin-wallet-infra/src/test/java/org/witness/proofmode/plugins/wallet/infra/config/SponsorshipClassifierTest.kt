package org.witness.proofmode.plugins.wallet.infra.config

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SponsorshipClassifierTest {

    private fun classify(body: String?) = SponsorshipClassifier.classify(body)

    @Test
    fun paymasterResult_isSponsored() {
        val body = """
            {"jsonrpc":"2.0","id":1,"result":{
              "paymaster":"0x777777777777AeC03fd955926DbF81597e66834C",
              "paymasterData":"0x01"}}
        """.trimIndent()
        assertEquals(SponsorshipClassification.SPONSORED, classify(body))
    }

    @Test
    fun nullOrBlankPaymaster_isUnsponsored() {
        listOf(
            "null paymaster" to """{"jsonrpc":"2.0","id":1,"result":{"paymaster":null}}""",
            "blank paymaster" to """{"jsonrpc":"2.0","id":1,"result":{"paymaster":"   "}}""",
            "gas-only result" to """
                {"jsonrpc":"2.0","id":1,"result":{
                  "callGasLimit":"0x0","verificationGasLimit":"0x1a2b",
                  "preVerificationGas":"0xc350"}}
            """.trimIndent(),
        ).forEach { (label, body) ->
            assertEquals(label, SponsorshipClassification.UNSPONSORED, classify(body))
        }
    }

    @Test
    fun noPolicyError_isUnsponsored() {
        val topLevelString = """{"error":"No Sponsoring Policies found for this project on chain 8453"}"""
        val jsonRpcErrorObject = """
            {"jsonrpc":"2.0","id":1,"error":{"code":-32602,
              "message":"no sponsoring policy matched this user operation"}}
        """.trimIndent()
        assertEquals(SponsorshipClassification.UNSPONSORED, classify(topLevelString))
        assertEquals(SponsorshipClassification.UNSPONSORED, classify(jsonRpcErrorObject))
    }

    @Test
    fun errorWithoutNoPolicyPhrase_isUnknown() {
        assertEquals(
            SponsorshipClassification.UNKNOWN,
            classify("""{"error":"No gas policy is configured for this chain"}"""),
        )
        assertEquals(
            SponsorshipClassification.UNKNOWN,
            classify("""{"jsonrpc":"2.0","id":1,"error":{"code":-32500,"message":"sponsorship rules missing"}}"""),
        )
    }

    @Test
    fun malformedEmptyNullOrNonObjectBody_isUnknown() {
        listOf(
            null,
            "<html><body>502 Bad Gateway</body></html>",
            """{"jsonrpc":"2.0","id":1,"result":{"paymaster":"0xabc""",
        ).forEach { body ->
            assertEquals(body.toString(), SponsorshipClassification.UNKNOWN, classify(body))
        }
    }

    @Test
    fun paymasterBeatsErrorAndResultBeatsError() {
        val noPolicy = """{"code":-32602,"message":"No sponsoring policies"}"""
        val otherError = """{"code":-32500,"message":"AA23 reverted"}"""
        assertEquals(
            SponsorshipClassification.SPONSORED,
            classify("""{"result":{"paymaster":"0x7777"},"error":$noPolicy}"""),
        )
        assertEquals(
            SponsorshipClassification.UNSPONSORED,
            classify("""{"result":{"callGasLimit":"0x0"},"error":$otherError}"""),
        )
    }
}
