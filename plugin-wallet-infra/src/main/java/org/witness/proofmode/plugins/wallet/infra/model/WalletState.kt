package org.witness.proofmode.plugins.wallet.infra.model

sealed class WalletState

data object WalletDisconnected : WalletState()

data class WalletConnecting(val message: String = "") : WalletState()

data class WalletConnected(val identity: WalletIdentity) : WalletState()

data object WalletAuthenticating : WalletState()
