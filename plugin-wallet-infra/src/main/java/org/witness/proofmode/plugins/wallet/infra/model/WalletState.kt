package org.witness.proofmode.plugins.wallet.infra.model

import org.witness.proofmode.plugins.wallet.infra.exception.WalletException

sealed class WalletState

data object WalletDisconnected : WalletState()

data class WalletConnecting(val message: String = "") : WalletState()

data class WalletConnected(val identity: WalletIdentity) : WalletState()

data object WalletAuthenticating : WalletState()

/** Privy login succeeded and the embedded wallet is being created. */
data object WalletCreatingWallet : WalletState()

/** Signed in, but the embedded wallet could not be created or restored; [retryWallet][org.witness.proofmode.plugins.wallet.infra.api.WalletConnector.retryWallet] may recover. */
data class WalletUnavailable(val cause: WalletException) : WalletState()

/**
 * A saved session exists but Privy could not verify it (offline at launch, or the startup refresh
 * failed). Privy itself retries at most once. The Privy connector asks it to retry
 * (`Privy.onNetworkRestored()`) each time the device reports a usable network while in this
 * state, and whenever [refreshSession][org.witness.proofmode.plugins.wallet.infra.api.WalletConnector.refreshSession]
 * is called (Settings does so on start).
 */
data object WalletAwaitingNetwork : WalletState()
