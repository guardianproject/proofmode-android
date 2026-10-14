package org.witness.proofmode.plugins.wallet.infra.privy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCreationFailedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCreationTimedOutException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletSessionExpiredException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletSignedInWithoutWalletException
import org.witness.proofmode.plugins.wallet.infra.model.WalletAwaitingNetwork
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletCreatingWallet
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletState
import org.witness.proofmode.plugins.wallet.infra.model.WalletUnavailable

@OptIn(ExperimentalCoroutinesApi::class)
class EmbeddedWalletCoordinatorTest {

    private class FakeUser(addresses: List<String> = emptyList()) : PrivySdkUser {
        override var walletAddresses: List<String> = addresses
        var createCount = 0
        /** Read on each create call, so a test can swap it between creations. */
        var gate = CompletableDeferred<String>()
        var onRefresh: () -> Unit = {}

        override suspend fun createEthereumWallet(): String {
            createCount++
            return gate.await()
        }

        override suspend fun refresh() = onRefresh()
    }

    private val published = mutableListOf<WalletState>()

    private fun TestScope.coordinator(currentUser: () -> PrivySdkUser?) =
        EmbeddedWalletCoordinator(
            scope = backgroundScope,
            currentUser = { currentUser() },
            chainId = { CHAIN },
            publish = { published += it },
        )

    private fun Result<*>.reason() =
        (exceptionOrNull() as WalletSignedInWithoutWalletException).reason

    @Test
    fun `user with wallet connects immediately without creating`() = runTest {
        val user = FakeUser(listOf("0xabc"))
        val c = coordinator { user }

        val identity = c.completeVerifiedLogin(user)

        assertEquals(WalletIdentity("0xabc", CHAIN), identity)
        assertEquals(0, user.createCount)
        assertEquals(WalletConnected(identity), published.last())
    }

    @Test
    fun `new user waits for creation then connects`() = runTest {
        val user = FakeUser()
        val c = coordinator { user }

        val login = async { c.completeVerifiedLogin(user) }
        runCurrent()
        assertTrue(!login.isCompleted)
        user.gate.complete("0xnew")
        runCurrent()
        assertTrue(login.isCompleted)

        val identity = WalletIdentity("0xnew", CHAIN)
        assertEquals(identity, login.await())
        assertEquals(listOf(WalletCreatingWallet, WalletConnected(identity)), published)
    }

    @Test
    fun `listener and login share one creation`() = runTest {
        val user = FakeUser()
        val c = coordinator { user }

        c.onAuthenticated(user)
        val login = async { c.completeVerifiedLogin(user) }
        runCurrent()
        user.gate.complete("0xnew")
        runCurrent()
        assertTrue(login.isCompleted)

        assertEquals(WalletIdentity("0xnew", CHAIN), login.await())
        assertEquals(1, user.createCount)
        assertEquals(1, published.count { it == WalletCreatingWallet })
    }

    @Test
    fun `create failure falls back to refreshed address`() = runTest {
        val user = FakeUser()
        user.gate.completeExceptionally(RuntimeException("already has a wallet"))
        user.onRefresh = { user.walletAddresses = listOf("0xold") }
        val c = coordinator { user }

        val identity = c.completeVerifiedLogin(user)

        assertEquals(WalletIdentity("0xold", CHAIN), identity)
        assertEquals(WalletConnected(identity), published.last())
    }

    @Test
    fun `create failure with no wallet after refresh throws signed in without wallet`() = runTest {
        val user = FakeUser()
        user.gate.completeExceptionally(RuntimeException("boom"))
        val c = coordinator { user }

        val result = runCatching { c.completeVerifiedLogin(user) }

        assertTrue(result.reason() is WalletCreationFailedException)
        val last = published.last()
        assertTrue(last is WalletUnavailable)
        assertTrue((last as WalletUnavailable).cause is WalletCreationFailedException)
    }

    @Test
    fun `creation past deadline times out`() = runTest {
        val user = FakeUser()
        val c = coordinator { user }

        val login = async { runCatching { c.completeVerifiedLogin(user) } }
        runCurrent()
        advanceTimeBy(89_999)
        runCurrent()
        assertTrue(!login.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(login.isCompleted)

        assertTrue(login.await().reason() is WalletCreationTimedOutException)
        val last = published.last()
        assertTrue(last is WalletUnavailable)
        assertTrue((last as WalletUnavailable).cause is WalletCreationTimedOutException)
    }

    @Test
    fun `joiner cancellation does not cancel creation`() = runTest {
        val user = FakeUser()
        val c = coordinator { user }

        val login = launch { c.completeVerifiedLogin(user) }
        runCurrent()
        login.cancel()
        runCurrent()
        user.gate.complete("0xnew")
        runCurrent()

        assertTrue(login.isCancelled)
        assertEquals(WalletConnected(WalletIdentity("0xnew", CHAIN)), published.last())
    }

    @Test
    fun `detached creation result is not published`() = runTest {
        val user = FakeUser()
        val c = coordinator { user }

        val login = async { runCatching { c.completeVerifiedLogin(user) } }
        runCurrent()
        c.detach()
        val publishedBeforeDetach = published.size
        user.gate.complete("0xnew")
        runCurrent()
        assertTrue(login.isCompleted)

        assertTrue(published.drop(publishedBeforeDetach).none { it is WalletConnected })
        assertTrue(login.await().reason() is WalletSessionExpiredException)
        assertEquals(1, user.createCount)
    }

    @Test
    fun `retry after failure starts a new creation`() = runTest {
        val user = FakeUser()
        user.gate.completeExceptionally(RuntimeException("boom"))
        val c = coordinator { user }
        val first = runCatching { c.completeVerifiedLogin(user) }
        assertTrue(first.isFailure)

        user.gate = CompletableDeferred()
        val retry = async { c.retry() }
        runCurrent()
        user.gate.complete("0xr")
        runCurrent()
        assertTrue(retry.isCompleted)

        val identity = WalletIdentity("0xr", CHAIN)
        assertEquals(identity, retry.await())
        assertEquals(2, user.createCount)
        assertEquals(WalletConnected(identity), published.last())
    }

    @Test
    fun `retry rethrows cause unwrapped`() = runTest {
        val user = FakeUser()
        user.gate.completeExceptionally(RuntimeException("boom"))
        val c = coordinator { user }

        val result = runCatching { c.retry() }

        assertTrue(result.exceptionOrNull() is WalletCreationFailedException)
    }

    @Test
    fun `currentUser null during creation is session expired`() = runTest {
        val user = FakeUser()
        val c = coordinator { null }

        val result = runCatching { c.completeVerifiedLogin(user) }

        assertTrue(result.reason() is WalletSessionExpiredException)
        assertEquals(0, user.createCount)
    }

    @Test
    fun `unverified with no creation publishes awaiting network`() = runTest {
        val c = coordinator { null }

        c.onAuthenticatedUnverified()

        assertEquals(WalletAwaitingNetwork, published.last())
    }

    @Test
    fun `unverified during creation leaves creation running`() = runTest {
        val user = FakeUser()
        val c = coordinator { user }

        c.onAuthenticated(user)
        runCurrent()
        c.onAuthenticatedUnverified()
        user.gate.complete("0xnew")
        runCurrent()

        assertEquals(WalletConnected(WalletIdentity("0xnew", CHAIN)), published.last())
        assertTrue(published.none { it == WalletAwaitingNetwork })
        assertEquals(1, user.createCount)
    }

    @Test
    fun `unverified while connected is ignored`() = runTest {
        val user = FakeUser(listOf("0xabc"))
        val c = coordinator { user }
        c.completeVerifiedLogin(user)

        c.onAuthenticatedUnverified()

        assertEquals(WalletConnected(WalletIdentity("0xabc", CHAIN)), published.last())
        assertTrue(published.none { it == WalletAwaitingNetwork })
    }

    @Test
    fun `unverified after detach publishes awaiting network`() = runTest {
        val user = FakeUser(listOf("0xabc"))
        val c = coordinator { user }
        c.completeVerifiedLogin(user)
        c.detach()

        c.onAuthenticatedUnverified()

        assertEquals(WalletAwaitingNetwork, published.last())
    }

    @Test
    fun `immediate dispatcher completion still publishes connected and clears current`() = runTest {
        val first = FakeUser(listOf("0xabc"))
        var user: PrivySdkUser = first
        val immediateScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val c = EmbeddedWalletCoordinator(
                scope = immediateScope,
                currentUser = { user },
                chainId = { CHAIN },
                publish = { published += it },
            )

            // Body returns without suspending (the current user already has a wallet).
            c.onAuthenticated(FakeUser())
            assertEquals(WalletConnected(WalletIdentity("0xabc", CHAIN)), published.last())

            // A stale completed creation would be joined here instead of starting fresh.
            user = FakeUser(listOf("0xdef"))
            val retried = c.retry()

            val identity = WalletIdentity("0xdef", CHAIN)
            assertEquals(identity, retried)
            assertEquals(WalletConnected(identity), published.last())
        } finally {
            immediateScope.cancel()
        }
    }

    private companion object {
        const val CHAIN = "eip155:84532"
    }
}
