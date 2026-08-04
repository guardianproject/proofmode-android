package org.witness.proofmode.plugins.lp.attestation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.TestWalletStackReset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OnchainAttestationConfirmationCallbackTest {

    @Before
    fun setUp() {
        TestWalletStackReset.reset()
        LocationProtocolPlugin.registerApplicationScope(
            CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @Test
    fun schedule_invokesOnConfirmedAfterArtifactSaved() = runTest {
        val easManager = mock<EASAttestationManager>()
        val artifactStore = mock<LocationProtocolArtifactStore>()
        val submitResult = OnchainSubmitResult(
            txHash = "0xabc",
            schemaId = "0xschema",
            easAddress = "0xeas",
            chainIdStr = "eip155:11155111",
            rpcUrls = listOf("https://rpc.test"),
            chainDisplayName = "Sepolia",
            submittedAt = 1L,
            sponsorshipActive = false,
            onChainAttester = "0xattester",
        )
        val confirmedPayload = """{"confirmed":true}"""
        whenever(easManager.confirmOnchainLocationAttestation(submitResult)).thenReturn(
            Result.success(
                LocationProtocolAttestationResult(
                    uid = "uid1",
                    schemaId = "0xschema",
                    attesterAddress = "0xattester",
                    timestamp = 1L,
                    offchainPayloadJson = confirmedPayload,
                    artifactPath = "",
                ),
            ),
        )

        var confirmedHash: String? = null
        OnchainAttestationConfirmation.schedule(
            mediaHash = "hash1",
            submitResult = submitResult,
            easManager = easManager,
            artifactStore = artifactStore,
            onConfirmed = { confirmedHash = it },
        )
        advanceUntilIdle()

        verify(artifactStore).saveOnchainAttestation("hash1", confirmedPayload)
        assertEquals("hash1", confirmedHash)
    }
}
