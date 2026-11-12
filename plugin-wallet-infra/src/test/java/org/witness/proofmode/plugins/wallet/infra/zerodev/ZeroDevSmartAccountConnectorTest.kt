package org.witness.proofmode.plugins.wallet.infra.zerodev

import android.app.Activity
import dev.zerodev.aa.Account
import dev.zerodev.aa.Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.witness.proofmode.plugins.wallet.infra.exception.WalletAuthException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCreationTimedOutException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletLifecycleException
import org.witness.proofmode.plugins.wallet.infra.model.WalletAwaitingNetwork
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletCreatingWallet
import org.witness.proofmode.plugins.wallet.infra.model.WalletDisconnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletState
import org.witness.proofmode.plugins.wallet.infra.model.WalletUnavailable
import org.witness.proofmode.plugins.wallet.infra.model.ZeroDevConfig
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector

@OptIn(ExperimentalCoroutinesApi::class)
class ZeroDevSmartAccountConnectorTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val privyAddress = "0x1234567890abcdef1234567890abcdef12345678"
    private val defaultChainId = "eip155:1"

    private fun mockPrivy(
        configure: org.mockito.kotlin.KStubbing<PrivyWalletConnector>.() -> Unit = {},
    ): PrivyWalletConnector = mock {
        on { stateFlow } doReturn MutableStateFlow<WalletState>(WalletDisconnected)
        configure()
    }

    private fun validConfig(
        isSponsorshipEnabled: Boolean = true,
        projectId: String = "00000000-0000-4000-8000-000000000001",
    ) = ZeroDevConfig(
        projectId = projectId,
        bundlerUrl = "https://bundler.example",
        paymasterUrl = "https://paymaster.example",
        isSponsorshipEnabled = isSponsorshipEnabled,
    )

    private fun stubProvisioner(
        connector: ZeroDevSmartAccountConnector,
        testScheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        smartAccountAddress: String = privyAddress,
    ) {
        connector.ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        connector.sponsoredAccountProvisioner = CountingProvisioner(smartAccountAddress = smartAccountAddress)
    }

    @Test
    fun coldStartRestore_activatesSponsorshipWhenPrivyReconnectsWithoutSetChain() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val transientStates = listOf<Pair<String, WalletState?>>(
            "none" to null,
            "WalletCreatingWallet" to WalletCreatingWallet,
            "WalletAwaitingNetwork" to WalletAwaitingNetwork,
        )
        transientStates.forEach { (label, transient) ->
            val privyState = MutableStateFlow<WalletState>(WalletDisconnected)
            val identity = WalletIdentity(privyAddress, defaultChainId)
            val mockPrivyConnector = mockPrivy {
                on { stateFlow } doReturn privyState
                on { address } doReturn privyAddress
                on { getIdentity() } doReturn identity
            }
            val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { validConfig() }
            stubProvisioner(connector, testScheduler)

            if (transient != null) {
                privyState.value = transient
                advanceUntilIdle()
                assertEquals(label, transient, connector.stateFlow.value)
            }

            privyState.value = WalletConnected(identity)
            advanceUntilIdle()

            assertTrue(
                "$label: expected sponsored-account restore after cold-start Privy WalletConnected",
                connector.isSponsorshipActive,
            )
            assertTrue(label, connector.stateFlow.value is WalletConnected)
            assertEquals(label, privyAddress, (connector.stateFlow.value as WalletConnected).identity.address)
        }
    }

    @Test
    fun coldStartRestore_selfFundedFallback() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val cases = listOf(
            FallbackCase("sponsorship disabled", validConfig(isSponsorshipEnabled = false)),
            FallbackCase("provisioner fails", validConfig(), CountingProvisioner(failFirst = true)),
        )
        cases.forEach { (label, config, provisioner) ->
            val privyState = MutableStateFlow<WalletState>(WalletDisconnected)
            val identity = WalletIdentity(privyAddress, defaultChainId)
            val mockPrivyConnector = mockPrivy {
                on { stateFlow } doReturn privyState
                on { address } doReturn privyAddress
                on { getIdentity() } doReturn identity
            }
            val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { config }
            connector.ioDispatcher = UnconfinedTestDispatcher(testScheduler)
            if (provisioner != null) connector.sponsoredAccountProvisioner = provisioner

            privyState.value = WalletConnected(identity)
            advanceUntilIdle()

            assertFalse(label, connector.isSponsorshipActive)
            assertTrue(label, connector.stateFlow.value is WalletConnected)
            assertEquals(label, privyAddress, (connector.stateFlow.value as WalletConnected).identity.address)
        }
    }

    @Test
    fun setChain_whenDisconnected_sponsored_doesNotProvision() = runBlocking {
        val provisioner = CountingProvisioner()
        val mockPrivyConnector = mockPrivy {
            on { getIdentity() } doReturn null
            on { address } doReturn ""
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { validConfig() }
        connector.sponsoredAccountProvisioner = provisioner

        connector.setChain("eip155:11155111")

        assertEquals(0, provisioner.calls)
        assertTrue(connector.stateFlow.value is WalletDisconnected)
        assertFalse(connector.isSponsorshipActive)
    }

    @Test
    fun setChain_whenConnected_sponsored_activatesSponsorship() = runBlocking {
        val smartAccountAddress = "0x000000000000000000000000000000000000dce6"
        val mockPrivyConnector = mockPrivy {
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn WalletIdentity(privyAddress, defaultChainId)
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { validConfig() }
        connector.sponsoredAccountProvisioner = CountingProvisioner(smartAccountAddress = smartAccountAddress)

        connector.setChain("eip155:84532")

        assertTrue(connector.isSponsorshipActive)
        assertTrue(connector.stateFlow.value is WalletConnected)
        val connected = connector.stateFlow.value as WalletConnected
        assertEquals(smartAccountAddress, connected.identity.address)
        assertEquals("eip155:84532", connected.identity.chainId)
        assertEquals(privyAddress, connector.address)
    }

    private fun connectedPrivy() = mockPrivy {
        on { address } doReturn privyAddress
        on { getIdentity() } doReturn WalletIdentity(privyAddress, defaultChainId)
    }

    private data class FallbackCase(
        val label: String,
        val config: ZeroDevConfig,
        val provisioner: SponsoredAccountProvisioner? = null,
    )

    /** Counts provisions; [failFirst] makes the first provision throw. */
    private class CountingProvisioner(
        private val failFirst: Boolean = false,
        private val smartAccountAddress: String = "0x000000000000000000000000000000000000dce6",
    ) : SponsoredAccountProvisioner {
        var calls = 0
        override fun provision(
            chainId: String,
            config: ZeroDevConfig,
            privyConnector: PrivyWalletConnector,
        ): SponsoredAccountSession {
            calls++
            if (failFirst && calls == 1) throw RuntimeException("SDK init failed")
            return SponsoredAccountSession(
                account = mock<Account> {
                    on { getAddress() } doReturn Address.fromHex(smartAccountAddress)
                },
                context = mock(),
                signer = mock(),
            )
        }
    }

    @Test
    fun sameChainSameConfig_noReprovision() = runTest {
        val provisioner = CountingProvisioner()
        val connector = ZeroDevSmartAccountConnector(connectedPrivy()) { validConfig() }
        connector.sponsoredAccountProvisioner = provisioner

        connector.setChain("eip155:84532")
        val afterFirst = connector.stateFlow.value
        connector.setChain("eip155:84532")

        assertEquals(1, provisioner.calls)
        assertTrue(connector.isSponsorshipActive)
        assertEquals(afterFirst, connector.stateFlow.value)

        connector.setChain("eip155:11155111")

        assertEquals(2, provisioner.calls)
    }

    @Test
    fun sameChainConfigChanged_toggleFlip_reprovisionsThenFallsBackToEoa() = runTest {
        val provisioner = CountingProvisioner()
        var sponsorshipEnabled = true
        val connector = ZeroDevSmartAccountConnector(connectedPrivy()) {
            validConfig(isSponsorshipEnabled = sponsorshipEnabled)
        }
        connector.sponsoredAccountProvisioner = provisioner

        connector.setChain("eip155:84532")
        assertTrue(connector.isSponsorshipActive)

        sponsorshipEnabled = false
        connector.setChain("eip155:84532")

        assertFalse(connector.isSponsorshipActive)
        assertEquals(privyAddress, (connector.stateFlow.value as WalletConnected).identity.address)

        sponsorshipEnabled = true
        connector.setChain("eip155:84532")

        assertTrue(connector.isSponsorshipActive)
        assertEquals(2, provisioner.calls)
    }

    @Test
    fun sameChainConfigChanged_projectOverride_reprovisions() = runTest {
        val provisioner = CountingProvisioner()
        var projectId = "00000000-0000-4000-8000-000000000001"
        val connector = ZeroDevSmartAccountConnector(connectedPrivy()) { validConfig(projectId = projectId) }
        connector.sponsoredAccountProvisioner = provisioner

        connector.setChain("eip155:84532")
        projectId = "00000000-0000-4000-8000-000000000002"
        connector.setChain("eip155:84532")

        assertEquals(2, provisioner.calls)
    }

    @Test
    fun failedInit_leavesDisconnected_andNextSetChainProvisionsAgain() = runTest {
        val provisioner = CountingProvisioner(failFirst = true)
        val connector = ZeroDevSmartAccountConnector(connectedPrivy()) { validConfig() }
        connector.sponsoredAccountProvisioner = provisioner

        connector.setChain("eip155:84532")
        assertTrue(connector.stateFlow.value is WalletDisconnected)

        connector.setChain("eip155:84532")

        assertEquals(2, provisioner.calls)
        assertTrue(connector.isSponsorshipActive)
        assertTrue(connector.stateFlow.value is WalletConnected)
    }

    @Test
    fun testZeroConfigDeactivation() = runTest {
        val credentialed = ZeroDevConfig(
            projectId = "project-id",
            bundlerUrl = "https://bundler",
            paymasterUrl = "https://paymaster",
            isSponsorshipEnabled = true,
        )
        val configs = listOf(
            "blank projectId" to credentialed.copy(projectId = ""),
            "sponsorship flag false" to credentialed.copy(isSponsorshipEnabled = false),
        )
        configs.forEach { (label, config) ->
            val connector = ZeroDevSmartAccountConnector(connectedPrivy()) { config }

            connector.connect()

            assertFalse(label, connector.isSponsorshipActive)
            assertTrue(label, connector.stateFlow.value is WalletConnected)
            val identity = (connector.stateFlow.value as WalletConnected).identity
            assertEquals(label, privyAddress, identity.address)
            assertEquals(label, defaultChainId, identity.chainId)
        }
    }

    @Test
    fun testForegroundActivityGuardSendTransactionThrowsWithoutActivity() = runBlocking {
        val mockPrivyConnector = mockPrivy {
            on { getActiveActivity() } doReturn null
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) {
            ZeroDevConfig("id", "https://bundler", "https://paymaster", true)
        }

        try {
            connector.sendTransaction(mapOf("to" to "0x...", "data" to "0x", "valueHex" to "0x0", "chainId" to "eip155:1"))
            fail("Expected WalletLifecycleException")
        } catch (_: WalletLifecycleException) {
        }
    }

    @Test
    fun blankUrlsWithProjectId_takeSponsoredPathAndFailAsSponsoredInit() = runTest {
        val provisioner = CountingProvisioner(failFirst = true)
        val blankUrls = ZeroDevConfig("id", "", "", true)
        val connector = ZeroDevSmartAccountConnector(connectedPrivy()) { blankUrls }
        connector.sponsoredAccountProvisioner = provisioner

        try {
            connector.connect()
            fail("Expected WalletAuthException")
        } catch (_: WalletAuthException) {
        }

        assertEquals(1, provisioner.calls)
        assertFalse(connector.isSponsorshipActive)
    }

    @Test
    fun connect_provisionerFails_throwsWalletAuthExceptionAndLeavesDisconnected() = runTest {
        val privy = connectedPrivy()
        val connector = ZeroDevSmartAccountConnector(privy) { validConfig() }
        connector.sponsoredAccountProvisioner = CountingProvisioner(failFirst = true)

        try {
            connector.connect()
            fail("Expected WalletAuthException")
        } catch (_: WalletAuthException) {
        }

        assertTrue(connector.stateFlow.value is WalletDisconnected)
        assertFalse(connector.isSponsorshipActive)
        verify(privy).disconnect()
    }

    @Test
    fun testSendTransaction_selfFundedEoaWhenSponsorshipInactive() = runBlocking {
        val mockActivity = mock<Activity>()
        val txParams = mapOf("to" to "0x...", "data" to "0x", "valueHex" to "0x0", "chainId" to "eip155:1")
        val expectedResult = mapOf("txHash" to "0xmockedtxhash")
        val mockPrivyConnector = mockPrivy {
            on { getActiveActivity() } doReturn mockActivity
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn WalletIdentity(privyAddress, defaultChainId)
            onBlocking { sendTransaction(txParams) } doReturn expectedResult
        }

        val config = ZeroDevConfig("id", "", "", false)
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { config }

        connector.connect()
        val result = connector.sendTransaction(txParams)
        assertEquals(expectedResult, result)
    }

    @Test
    fun testDisconnectResetsState() = runBlocking {
        val mockPrivyConnector = mockPrivy {
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn WalletIdentity(privyAddress, defaultChainId)
        }

        val config = ZeroDevConfig("id", "", "", false)
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { config }

        connector.connect()
        assertTrue(connector.stateFlow.value is WalletConnected)

        connector.disconnect()
        assertTrue(connector.stateFlow.value is WalletDisconnected)
    }

    @Test
    fun `mirrors privy WalletUnavailable`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val privyState = MutableStateFlow<WalletState>(WalletDisconnected)
        val connector = ZeroDevSmartAccountConnector(
            mockPrivy { on { stateFlow } doReturn privyState },
        ) { validConfig() }
        val unavailable = WalletUnavailable(WalletCreationTimedOutException())

        privyState.value = unavailable
        advanceUntilIdle()

        assertEquals(unavailable, connector.stateFlow.value)
    }

    @Test
    fun `retryWallet and refreshSession delegate to privy connector`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val privy = mockPrivy()
        val connector = ZeroDevSmartAccountConnector(privy) { validConfig() }

        connector.retryWallet()
        connector.refreshSession()

        verify(privy).retryWallet()
        verify(privy).refreshSession()
    }
}
