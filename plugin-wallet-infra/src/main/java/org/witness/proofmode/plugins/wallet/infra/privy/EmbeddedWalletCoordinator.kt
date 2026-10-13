package org.witness.proofmode.plugins.wallet.infra.privy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCreationFailedException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletCreationTimedOutException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletSessionExpiredException
import org.witness.proofmode.plugins.wallet.infra.exception.WalletSignedInWithoutWalletException
import org.witness.proofmode.plugins.wallet.infra.model.WalletAwaitingNetwork
import org.witness.proofmode.plugins.wallet.infra.model.WalletConnected
import org.witness.proofmode.plugins.wallet.infra.model.WalletCreatingWallet
import org.witness.proofmode.plugins.wallet.infra.model.WalletIdentity
import org.witness.proofmode.plugins.wallet.infra.model.WalletState
import org.witness.proofmode.plugins.wallet.infra.model.WalletUnavailable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs at most one embedded-wallet creation at a time. Login, the auth-state listener and
 * Retry all start or join that one creation, so they never race to create two wallets.
 *
 * The creation coroutine publishes its result only while it is still the current creation;
 * [connectExisting] and [startOrJoin] also publish (Connected / CreatingWallet), and
 * [onAuthenticatedUnverified] publishes AwaitingNetwork when no creation is current and the
 * last published state is not Connected. After
 * [detach], the creation keeps running but its result is ignored.
 * The [creationDeadline] belongs to the creation, so a cancelled waiter never cancels it.
 *
 * Threading: call every method on the main thread, and give a [scope] that dispatches there.
 * All state is confined to that thread, so there are no locks or atomics.
 */
internal class EmbeddedWalletCoordinator(
    private val scope: CoroutineScope,
    private val currentUser: suspend () -> PrivySdkUser?,
    private val chainId: () -> String,
    private val publish: (WalletState) -> Unit,
    private val creationDeadline: Duration = 90.seconds,
) {
    /** The running creation and its id. Its Deferred never completes exceptionally. */
    private var current: Pair<Long, Deferred<Result<WalletIdentity>>>? = null
    private var nextId = 0L
    /** Creations with an id at or below this were detached; their waiters get session expired. */
    private var detachedThrough = 0L
    /** True while the last state this coordinator published is WalletConnected; cleared by [detach]. */
    private var connected = false

    /** Login path. Returns once connected; throws WalletSignedInWithoutWalletException(cause) if creation fails. */
    suspend fun completeVerifiedLogin(user: PrivySdkUser): WalletIdentity {
        connectExisting(user)?.let { return it }
        val (id, outcome) = join()
        if (id <= detachedThrough) {
            throw WalletSignedInWithoutWalletException(WalletSessionExpiredException())
        }
        return outcome.getOrElse { throw WalletSignedInWithoutWalletException(it.asWalletException()) }
    }

    /** Auth-state listener path. Starts or joins a creation; never throws. */
    fun onAuthenticated(user: PrivySdkUser) {
        if (connectExisting(user) == null) startOrJoin()
    }

    /**
     * Auth-state listener path for a saved session that could not be verified (offline).
     * Does nothing while a creation is current (it publishes its own result) or while the last
     * published state is Connected (a mid-session offline blip keeps the wallet connected).
     * Otherwise publishes AwaitingNetwork.
     */
    fun onAuthenticatedUnverified() {
        if (current == null && !connected) emit(WalletAwaitingNetwork)
    }

    /** Settings Retry. Throws the creation cause (not wrapped). */
    suspend fun retry(): WalletIdentity {
        val (id, outcome) = join()
        if (id <= detachedThrough) throw WalletSessionExpiredException()
        return outcome.getOrElse { throw it.asWalletException() }
    }

    /** Disconnect / unauthenticated. The running creation keeps going but its result is ignored. */
    fun detach() {
        current?.let { detachedThrough = it.first }
        current = null
        connected = false
    }

    private fun connectExisting(user: PrivySdkUser): WalletIdentity? {
        val address = user.walletAddresses.firstOrNull() ?: return null
        return WalletIdentity(address, chainId()).also { emit(WalletConnected(it)) }
    }

    /** Starts or joins the creation and waits for it. Cancelling the caller does not cancel the creation. */
    private suspend fun join(): Pair<Long, Result<WalletIdentity>> {
        val (id, creation) = startOrJoin()
        val outcome = try {
            creation.await()
        } catch (e: CancellationException) {
            // Either this waiter was cancelled (rethrow) or the creation died with its scope.
            currentCoroutineContext().ensureActive()
            Result.failure(WalletCreationFailedException(cause = e))
        }
        return id to outcome
    }

    private fun startOrJoin(): Pair<Long, Deferred<Result<WalletIdentity>>> {
        current?.let { return it }
        emit(WalletCreatingWallet)
        val id = ++nextId
        // LAZY so `current` is set before the body runs: on an immediate dispatcher a creation
        // that never suspends would otherwise call finish() before it is current.
        val creation = scope.async(start = CoroutineStart.LAZY) { runCreation(id) }
        val entry = id to creation
        current = entry
        creation.start()
        return entry
    }

    private suspend fun runCreation(id: Long): Result<WalletIdentity> {
        // A separate coroutine in [scope], so the deadline fires even if the SDK call ignores cancellation.
        val inner = scope.async {
            try {
                Result.success(body())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
        val result = try {
            withTimeoutOrNull(creationDeadline) { inner.await() }
                ?: run {
                    inner.cancel()
                    Result.failure(WalletCreationTimedOutException())
                }
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                // The body cancelled itself (for example an SDK-internal timeout): a plain failure.
                Result.failure(e)
            } else {
                // This creation was cancelled with its scope: forget it and publish nothing.
                if (current?.first == id) current = null
                throw e
            }
        }
        val outcome = result.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it.asWalletException()) },
        )
        finish(id, outcome)
        return outcome
    }

    private suspend fun body(): WalletIdentity {
        val user = currentUser() ?: throw WalletSessionExpiredException()
        user.walletAddresses.firstOrNull()?.let { return identity(it) }
        val address = try {
            user.createEthereumWallet()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            recoverExistingWallet(user, t)
        }
        return identity(address)
    }

    /** After a failed create, the wallet may exist anyway (e.g. a concurrent create): refresh and look again. */
    private suspend fun recoverExistingWallet(user: PrivySdkUser, createError: Throwable): String {
        try {
            user.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw PrivyErrorMapper.mapCreationError(t)
        }
        return currentUser()?.walletAddresses?.firstOrNull()
            ?: throw PrivyErrorMapper.mapCreationError(createError)
    }

    private fun finish(id: Long, result: Result<WalletIdentity>) {
        if (current?.first != id) return
        current = null
        emit(
            result.fold(
                onSuccess = { WalletConnected(it) },
                onFailure = { WalletUnavailable(it.asWalletException()) },
            )
        )
    }

    /** Every publish goes through here so [connected] tracks the last published state. */
    private fun emit(state: WalletState) {
        connected = state is WalletConnected
        publish(state)
    }

    private fun identity(address: String) = WalletIdentity(address, chainId())

    private fun Throwable.asWalletException(): WalletException =
        this as? WalletException ?: WalletCreationFailedException(cause = this)
}
