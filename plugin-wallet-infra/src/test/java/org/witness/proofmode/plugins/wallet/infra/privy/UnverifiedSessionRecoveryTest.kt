package org.witness.proofmode.plugins.wallet.infra.privy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.model.WalletAwaitingNetwork
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletDisconnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletState

@OptIn(ExperimentalCoroutinesApi::class)
class UnverifiedSessionRecoveryTest {

    private val connected = WalletConnected(WalletIdentity("0xabc", "eip155:1"))

    @Test
    fun `refresh calls the hook while awaiting network`() = runTest {
        var calls = 0
        val recovery = UnverifiedSessionRecovery(
            state = MutableStateFlow<WalletState>(WalletAwaitingNetwork),
            networkAvailable = { MutableStateFlow(false) },
            onNetworkRestored = { calls++ },
        )

        recovery.refresh()

        assertEquals(1, calls)
    }

    @Test
    fun `refresh does nothing outside awaiting network`() = runTest {
        var calls = 0
        val state = MutableStateFlow<WalletState>(WalletDisconnected)
        val recovery = UnverifiedSessionRecovery(
            state = state,
            networkAvailable = { MutableStateFlow(true) },
            onNetworkRestored = { calls++ },
        )

        recovery.refresh()
        state.value = connected
        recovery.refresh()

        assertEquals(0, calls)
    }

    @Test
    fun `refresh swallows hook failures`() = runTest {
        val recovery = UnverifiedSessionRecovery(
            state = MutableStateFlow<WalletState>(WalletAwaitingNetwork),
            networkAvailable = { MutableStateFlow(false) },
            onNetworkRestored = { throw IllegalStateException("boom") },
        )

        recovery.refresh()
    }

    @Test
    fun `refresh rethrows cancellation`() = runTest {
        val recovery = UnverifiedSessionRecovery(
            state = MutableStateFlow<WalletState>(WalletAwaitingNetwork),
            networkAvailable = { MutableStateFlow(false) },
            onNetworkRestored = { throw CancellationException("cancelled") },
        )

        try {
            recovery.refresh()
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `watch refreshes once per network transition while awaiting network`() = runTest {
        var calls = 0
        val state = MutableStateFlow<WalletState>(WalletAwaitingNetwork)
        val network = MutableStateFlow(false)
        val recovery = UnverifiedSessionRecovery(state, { network }, { calls++ })
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { recovery.watch() }

        assertEquals(0, calls)
        network.value = true
        assertEquals(1, calls)
        network.value = false
        assertEquals(1, calls)
        network.value = true
        assertEquals(2, calls)

        job.cancel()
    }

    @Test
    fun `watch refreshes on entry when the network is already available`() = runTest {
        var calls = 0
        val state = MutableStateFlow<WalletState>(WalletDisconnected)
        val network = MutableStateFlow(true)
        val recovery = UnverifiedSessionRecovery(state, { network }, { calls++ })
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { recovery.watch() }

        assertEquals(0, calls)
        state.value = WalletAwaitingNetwork
        assertEquals(1, calls)

        job.cancel()
    }

    @Test
    fun `watch stops observing the network once connected`() = runTest {
        var calls = 0
        val state = MutableStateFlow<WalletState>(WalletAwaitingNetwork)
        val network = MutableStateFlow(false)
        val recovery = UnverifiedSessionRecovery(state, { network }, { calls++ })
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { recovery.watch() }
        assertEquals(1, network.subscriptionCount.value)

        state.value = connected
        assertEquals(0, network.subscriptionCount.value)
        network.value = true
        network.value = false
        network.value = true

        assertEquals(0, calls)
        job.cancel()
    }
}
