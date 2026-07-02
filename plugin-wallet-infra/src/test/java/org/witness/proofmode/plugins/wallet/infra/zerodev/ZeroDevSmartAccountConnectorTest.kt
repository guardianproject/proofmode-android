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
import org.mockito.kotlin.whenever
import org.witness.proofmode.plugins.wallet.infra.exception.WalletLifecycleException
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletDisconnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletState
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
    ) = ZeroDevConfig(
        projectId = "00000000-0000-4000-8000-000000000001",
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
        connector.sponsoredAccountProvisioner = SponsoredAccountProvisioner { _, _, _ ->
            SponsoredAccountSession(
                account = mock<Account> { on { getAddress() } doReturn Address.fromHex(smartAccountAddress) },
                context = mock(),
                signer = mock(),
            )
        }
    }

    @Test
    fun coldStartRestore_activatesSponsorshipWhenPrivyReconnectsWithoutSetChain() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val privyState = MutableStateFlow<WalletState>(WalletDisconnected)
        val identity = WalletIdentity(privyAddress, defaultChainId)
        val mockPrivyConnector = mockPrivy {
            on { stateFlow } doReturn privyState
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn identity
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { validConfig() }
        stubProvisioner(connector, testScheduler)

        privyState.value = WalletConnected(identity)
        advanceUntilIdle()

        assertTrue(
            "Expected sponsored-account restore after cold-start Privy WalletConnected",
            connector.isSponsorshipActive,
        )
        assertTrue(connector.stateFlow.value is WalletConnected)
    }

    @Test
    fun coldStartRestore_selfFundedFallbackWhenSponsorshipDisabled() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val privyState = MutableStateFlow<WalletState>(WalletDisconnected)
        val identity = WalletIdentity(privyAddress, defaultChainId)
        val mockPrivyConnector = mockPrivy {
            on { stateFlow } doReturn privyState
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn identity
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) {
            validConfig(isSponsorshipEnabled = false)
        }
        connector.ioDispatcher = UnconfinedTestDispatcher(testScheduler)

        privyState.value = WalletConnected(identity)
        advanceUntilIdle()

        assertFalse(connector.isSponsorshipActive)
        assertTrue(connector.stateFlow.value is WalletConnected)
        val connected = connector.stateFlow.value as WalletConnected
        assertEquals(privyAddress, connected.identity.address)
    }

    @Test
    fun coldStartRestore_selfFundedFallbackWhenProvisionerFails() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val privyState = MutableStateFlow<WalletState>(WalletDisconnected)
        val identity = WalletIdentity(privyAddress, defaultChainId)
        val mockPrivyConnector = mockPrivy {
            on { stateFlow } doReturn privyState
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn identity
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { validConfig() }
        connector.ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        connector.sponsoredAccountProvisioner = SponsoredAccountProvisioner { _, _, _ ->
            throw RuntimeException("SDK init failed")
        }

        privyState.value = WalletConnected(identity)
        advanceUntilIdle()

        assertFalse(connector.isSponsorshipActive)
        assertTrue(connector.stateFlow.value is WalletConnected)
        val connected = connector.stateFlow.value as WalletConnected
        assertEquals(privyAddress, connected.identity.address)
    }

    @Test
    fun coldStartRestore_skipsReinitWhenAlreadyActive() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))

        val privyState = MutableStateFlow<WalletState>(WalletDisconnected)
        val identity = WalletIdentity(privyAddress, defaultChainId)
        val mockPrivyConnector = mockPrivy {
            on { stateFlow } doReturn privyState
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn identity
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { validConfig() }
        var provisionCallCount = 0
        connector.ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        connector.sponsoredAccountProvisioner = SponsoredAccountProvisioner { _, _, _ ->
            provisionCallCount++
            SponsoredAccountSession(
                account = mock<Account> { on { getAddress() } doReturn Address.fromHex(privyAddress) },
                context = mock(),
                signer = mock(),
            )
        }

        privyState.value = WalletConnected(identity)
        advanceUntilIdle()
        assertTrue(connector.isSponsorshipActive)
        assertEquals(1, provisionCallCount)

        privyState.value = WalletConnected(identity)
        advanceUntilIdle()
        assertTrue(connector.isSponsorshipActive)
        assertEquals(1, provisionCallCount)
    }

    @Test
    fun setChain_disconnectsOnProvisionerFailure() = runBlocking {
        val mockPrivyConnector = mockPrivy {
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn WalletIdentity(privyAddress, defaultChainId)
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { validConfig() }
        connector.sponsoredAccountProvisioner = SponsoredAccountProvisioner { _, _, _ ->
            throw RuntimeException("SDK init failed")
        }

        connector.setChain("eip155:84532")

        assertFalse(connector.isSponsorshipActive)
        assertTrue(connector.stateFlow.value is WalletDisconnected)
    }

    @Test
    fun setChain_whenDisconnected_selfFunded_staysDisconnected() = runBlocking {
        val mockPrivyConnector = mockPrivy {
            on { getIdentity() } doReturn null
            on { address } doReturn ""
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) {
            validConfig(isSponsorshipEnabled = false)
        }

        connector.setChain("eip155:11155111")

        assertTrue(
            "Disconnected setChain must not emit WalletConnected",
            connector.stateFlow.value is WalletDisconnected,
        )
        assertFalse(connector.isSponsorshipActive)
    }

    @Test
    fun setChain_whenDisconnected_sponsored_doesNotProvision() = runBlocking {
        var provisionCallCount = 0
        val mockPrivyConnector = mockPrivy {
            on { getIdentity() } doReturn null
            on { address } doReturn ""
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { validConfig() }
        connector.sponsoredAccountProvisioner = SponsoredAccountProvisioner { _, _, _ ->
            provisionCallCount++
            throw AssertionError("Must not provision ZeroDev account when disconnected")
        }

        connector.setChain("eip155:11155111")

        assertEquals(0, provisionCallCount)
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
        connector.sponsoredAccountProvisioner = SponsoredAccountProvisioner { _, _, _ ->
            SponsoredAccountSession(
                account = mock<Account> {
                    on { getAddress() } doReturn Address.fromHex(smartAccountAddress)
                },
                context = mock(),
                signer = mock(),
            )
        }

        connector.setChain("eip155:84532")

        assertTrue(connector.isSponsorshipActive)
        assertTrue(connector.stateFlow.value is WalletConnected)
        val connected = connector.stateFlow.value as WalletConnected
        assertEquals(smartAccountAddress, connected.identity.address)
        assertEquals("eip155:84532", connected.identity.chainId)
    }

    @Test
    fun testBlankProjectIdSetsSponsorshipInactive() = runBlocking {
        val mockPrivyConnector = mockPrivy {
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn WalletIdentity(privyAddress, defaultChainId)
        }
        val config = ZeroDevConfig(
            projectId = "",
            bundlerUrl = "https://bundler",
            paymasterUrl = "https://paymaster",
            isSponsorshipEnabled = true,
        )
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { config }

        connector.connect()

        assertFalse(connector.isSponsorshipActive)
    }

    @Test
    fun testZeroConfigDeactivationSponsorshipFlagFalse() = runBlocking {
        val mockPrivyConnector = mockPrivy {
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn WalletIdentity(privyAddress, defaultChainId)
        }
        val config = ZeroDevConfig(
            projectId = "project-id",
            bundlerUrl = "https://bundler",
            paymasterUrl = "https://paymaster",
            isSponsorshipEnabled = false
        )
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { config }

        connector.connect()

        assertEquals(privyAddress, connector.address)
        assertTrue(connector.stateFlow.value is WalletConnected)
        val identity = (connector.stateFlow.value as WalletConnected).identity
        assertEquals(privyAddress, identity.address)
        assertEquals(defaultChainId, identity.chainId)
    }

    @Test
    fun testZeroConfigDeactivationBlankBundlerUrl() = runBlocking {
        val mockPrivyConnector = mockPrivy {
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn WalletIdentity(privyAddress, defaultChainId)
        }
        val config = ZeroDevConfig(
            projectId = "project-id",
            bundlerUrl = "",
            paymasterUrl = "https://paymaster",
            isSponsorshipEnabled = true
        )
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { config }

        connector.connect()

        assertEquals(privyAddress, connector.address)
        assertTrue(connector.stateFlow.value is WalletConnected)
        val identity = (connector.stateFlow.value as WalletConnected).identity
        assertEquals(privyAddress, identity.address)
    }

    @Test
    fun testZeroConfigDeactivationBlankPaymasterUrl() = runBlocking {
        val mockPrivyConnector = mockPrivy {
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn WalletIdentity(privyAddress, defaultChainId)
        }
        val config = ZeroDevConfig(
            projectId = "project-id",
            bundlerUrl = "https://bundler",
            paymasterUrl = "",
            isSponsorshipEnabled = true
        )
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { config }

        connector.connect()

        assertEquals(privyAddress, connector.address)
        assertTrue(connector.stateFlow.value is WalletConnected)
        val identity = (connector.stateFlow.value as WalletConnected).identity
        assertEquals(privyAddress, identity.address)
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
        } catch (e: WalletLifecycleException) {
            assertTrue(e.message!!.contains("foreground Activity"))
        }
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

        val config = ZeroDevConfig("id", "", "", true)
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { config }

        connector.connect()
        val result = connector.sendTransaction(txParams)
        assertEquals(expectedResult, result)
    }

    @Test
    fun testSignTypedDataAlwaysDelegatesToPrivy() = runBlocking {
        val typedDataJson = "{}"
        val expectedSig = "0xmockedsignature"
        val mockPrivyConnector = mockPrivy {
            onBlocking { signTypedData(typedDataJson) } doReturn expectedSig
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) {
            ZeroDevConfig("id", "https://bundler", "https://paymaster", true)
        }

        val sig = connector.signTypedData(typedDataJson)
        assertEquals(expectedSig, sig)
    }

    @Test
    fun address_alwaysReturnsPrivyEoa_evenWhenSponsorshipActive() {
        val mockPrivyConnector = mockPrivy {
            on { address } doReturn privyAddress
        }
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) {
            ZeroDevConfig("id", "https://bundler", "https://paymaster", true)
        }

        connector.isSponsorshipActive = true
        assertEquals(privyAddress, connector.address)
    }

    @Test
    fun testDisconnectResetsState() = runBlocking {
        var mockedAddress = privyAddress
        val mockPrivyConnector = mockPrivy()
        whenever(mockPrivyConnector.address).thenAnswer { mockedAddress }
        whenever(mockPrivyConnector.getIdentity()).thenReturn(WalletIdentity(privyAddress, defaultChainId))
        whenever(mockPrivyConnector.disconnect()).thenAnswer {
            mockedAddress = ""
            null
        }

        val config = ZeroDevConfig("id", "", "", true)
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) { config }

        connector.connect()
        assertTrue(connector.stateFlow.value is WalletConnected)

        connector.disconnect()
        assertTrue(connector.stateFlow.value is WalletDisconnected)
        assertEquals("", connector.address)
    }

    @Test
    fun testSetChainReEvaluatesZeroConfigInvariant() = runBlocking {
        val mockPrivyConnector = mockPrivy {
            on { address } doReturn privyAddress
            on { getIdentity() } doReturn WalletIdentity(privyAddress, defaultChainId)
        }

        var sponsorshipEnabled = true
        val connector = ZeroDevSmartAccountConnector(mockPrivyConnector) {
            ZeroDevConfig("id", "https://bundler", "https://paymaster", sponsorshipEnabled)
        }

        sponsorshipEnabled = false
        connector.connect()
        assertFalse(connector.address.isEmpty())

        connector.setChain("eip155:2")
        assertTrue(connector.stateFlow.value is WalletConnected)
    }
}
