package org.witness.proofmode.plugins.wallet.infra.zerodev

import android.os.Looper
import io.privy.auth.PrivyUser
import io.privy.sdk.Privy
import io.privy.wallet.ethereum.EmbeddedEthereumWallet
import io.privy.wallet.ethereum.EmbeddedEthereumWalletProvider
import io.privy.wallet.ethereum.EthereumRpcRequest
import io.privy.wallet.ethereum.EthereumRpcResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.witness.proofmode.plugins.wallet.infra.privy.PrivyWalletConnector
import org.witness.proofmode.plugins.wallet.infra.exception.WalletLifecycleException

class PrivyZeroDevBridgeTest {

    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun testGetAddressThrowsWhenBlank() {
        val mockConnector = mock<PrivyWalletConnector> {
            on { address } doReturn ""
        }
        val bridge = PrivyZeroDevBridge(mockConnector, testScope)
        try {
            bridge.getAddress()
            fail("Expected WalletLifecycleException")
        } catch (e: WalletLifecycleException) {
            assertTrue(e.message!!.contains("wallet not connected"))
        }
    }

    @Test
    fun testGetAddressReturnsByteArray() {
        val mockConnector = mock<PrivyWalletConnector> {
            on { address } doReturn "0x1234567890abcdef1234567890abcdef12345678"
        }
        val bridge = PrivyZeroDevBridge(mockConnector, testScope)
        val expected = byteArrayOf(
            0x12, 0x34, 0x56, 0x78, 0x90.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(),
            0x12, 0x34, 0x56, 0x78, 0x90.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(),
            0x12, 0x34, 0x56, 0x78
        )
        assertArrayEquals(expected, bridge.getAddress())
    }

    @Test
    fun testSignHashThrowsOnMainThread() {
        val mockConnector = mock<PrivyWalletConnector>()
        val bridge = PrivyZeroDevBridge(mockConnector, testScope)

        val mockLooperInstance = mock<Looper>()
        mockStatic(Looper::class.java).use { mockedLooper ->
            mockedLooper.`when`<Any> { Looper.myLooper() }.thenReturn(mockLooperInstance)
            mockedLooper.`when`<Any> { Looper.getMainLooper() }.thenReturn(mockLooperInstance)

            try {
                bridge.signHash(byteArrayOf(1, 2, 3))
                fail("Expected WalletLifecycleException due to main thread check")
            } catch (e: WalletLifecycleException) {
                assertTrue(e.message!!.contains("main thread will deadlock"))
            }
        }
    }

    @Test
    fun testSignHashSuccessOnBackgroundThread() = runBlocking {
        val mockConnector = mock<PrivyWalletConnector>()
        val mockPrivy = mock<Privy>()
        val mockUser = mock<PrivyUser>()
        val mockWallet = mock<EmbeddedEthereumWallet>()
        val mockProvider = mock<EmbeddedEthereumWalletProvider>()

        whenever(mockConnector.requirePrivyInstance()).thenReturn(mockPrivy)
        whenever(mockPrivy.getUser()).thenReturn(mockUser)
        whenever(mockUser.embeddedEthereumWallets).thenReturn(listOf(mockWallet))
        whenever(mockWallet.provider).thenReturn(mockProvider)

        val signatureHex = "0xabcdef"
        val mockResponse = mock<EthereumRpcResponse>()
        whenever(mockResponse.data).thenReturn(signatureHex)

        whenever(mockProvider.request(any())).thenReturn(Result.success(mockResponse))

        val bridge = PrivyZeroDevBridge(mockConnector, testScope)
        val hash = byteArrayOf(1, 2, 3)

        // Statically mock Looper to simulate running on background thread
        val mockLooperInstance = mock<Looper>()
        val mockMainLooper = mock<Looper>()
        mockStatic(Looper::class.java).use { mockedLooper ->
            // Background thread means myLooper() != getMainLooper() (e.g. returns null or mockLooperInstance while main is mockMainLooper)
            mockedLooper.`when`<Any> { Looper.myLooper() }.thenReturn(mockLooperInstance)
            mockedLooper.`when`<Any> { Looper.getMainLooper() }.thenReturn(mockMainLooper)

            val signatureBytes = bridge.signHash(hash)
            assertArrayEquals(byteArrayOf(0xab.toByte(), 0xcd.toByte(), 0xef.toByte()), signatureBytes)
        }
    }

    @Test
    fun testSignMessageUsesPersonalSign() = runBlocking {
        val mockConnector = mock<PrivyWalletConnector>()
        val mockPrivy = mock<Privy>()
        val mockUser = mock<PrivyUser>()
        val mockWallet = mock<EmbeddedEthereumWallet>()
        val mockProvider = mock<EmbeddedEthereumWalletProvider>()

        whenever(mockConnector.requirePrivyInstance()).thenReturn(mockPrivy)
        whenever(mockPrivy.getUser()).thenReturn(mockUser)
        whenever(mockUser.embeddedEthereumWallets).thenReturn(listOf(mockWallet))
        whenever(mockWallet.provider).thenReturn(mockProvider)
        whenever(mockWallet.address).thenReturn("0x1234567890abcdef1234567890abcdef12345678")

        val signatureHex = "0xabcdef"
        val mockResponse = mock<EthereumRpcResponse>()
        whenever(mockResponse.data).thenReturn(signatureHex)

        val requestCaptor = argumentCaptor<EthereumRpcRequest>()
        whenever(mockProvider.request(requestCaptor.capture())).thenReturn(Result.success(mockResponse))

        val bridge = PrivyZeroDevBridge(mockConnector, testScope)
        val message = byteArrayOf(4, 5, 6)

        val mockLooperInstance = mock<Looper>()
        val mockMainLooper = mock<Looper>()
        mockStatic(Looper::class.java).use { mockedLooper ->
            mockedLooper.`when`<Any> { Looper.myLooper() }.thenReturn(mockLooperInstance)
            mockedLooper.`when`<Any> { Looper.getMainLooper() }.thenReturn(mockMainLooper)

            val signatureBytes = bridge.signMessage(message)
            assertArrayEquals(byteArrayOf(0xab.toByte(), 0xcd.toByte(), 0xef.toByte()), signatureBytes)
            assertEquals("personal_sign", requestCaptor.firstValue.method)
        }
    }

    @Test
    fun testSignTypedDataHashDelegatesToSignHash() = runBlocking {
        val mockConnector = mock<PrivyWalletConnector>()
        val mockPrivy = mock<Privy>()
        val mockUser = mock<PrivyUser>()
        val mockWallet = mock<EmbeddedEthereumWallet>()
        val mockProvider = mock<EmbeddedEthereumWalletProvider>()

        whenever(mockConnector.requirePrivyInstance()).thenReturn(mockPrivy)
        whenever(mockPrivy.getUser()).thenReturn(mockUser)
        whenever(mockUser.embeddedEthereumWallets).thenReturn(listOf(mockWallet))
        whenever(mockWallet.provider).thenReturn(mockProvider)

        val signatureHex = "0xabcdef"
        val mockResponse = mock<EthereumRpcResponse>()
        whenever(mockResponse.data).thenReturn(signatureHex)

        val requestCaptor = argumentCaptor<EthereumRpcRequest>()
        whenever(mockProvider.request(requestCaptor.capture())).thenReturn(Result.success(mockResponse))

        val bridge = PrivyZeroDevBridge(mockConnector, testScope)
        val hash = byteArrayOf(1, 2, 3)

        val mockLooperInstance = mock<Looper>()
        val mockMainLooper = mock<Looper>()
        mockStatic(Looper::class.java).use { mockedLooper ->
            mockedLooper.`when`<Any> { Looper.myLooper() }.thenReturn(mockLooperInstance)
            mockedLooper.`when`<Any> { Looper.getMainLooper() }.thenReturn(mockMainLooper)

            bridge.signTypedDataHash(hash)
            assertEquals("secp256k1_sign", requestCaptor.firstValue.method)
        }
    }

    @Test
    fun testSignMessageThrowsOnMainThread() {
        val mockConnector = mock<PrivyWalletConnector>()
        val bridge = PrivyZeroDevBridge(mockConnector, testScope)

        val mockLooperInstance = mock<Looper>()
        mockStatic(Looper::class.java).use { mockedLooper ->
            mockedLooper.`when`<Any> { Looper.myLooper() }.thenReturn(mockLooperInstance)
            mockedLooper.`when`<Any> { Looper.getMainLooper() }.thenReturn(mockLooperInstance)

            try {
                bridge.signMessage(byteArrayOf(1))
                fail("Expected WalletLifecycleException due to main thread check")
            } catch (e: WalletLifecycleException) {
                assertTrue(e.message!!.contains("main thread will deadlock"))
            }
        }
    }
}
