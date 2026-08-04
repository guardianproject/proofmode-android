package org.witness.proofmode.plugins.wallet.infra.exception

open class WalletException(message: String, cause: Throwable? = null) : Exception(message, cause)

class WalletNotInitializedException(message: String = "Wallet not initialized") : WalletException(message)

class WalletAuthException(message: String, cause: Throwable? = null) : WalletException(message, cause)

class WalletSigningException(message: String, cause: Throwable? = null) : WalletException(message, cause)

open class WalletTransactionException(message: String, cause: Throwable? = null) : WalletException(message, cause)

class WalletCancelledException(message: String = "User cancelled") : WalletException(message)

class WalletUnsupportedCapabilityException(
    capability: String,
    message: String = "Unsupported capability: $capability"
) : WalletTransactionException(message)

class WalletTransactionRejectedException(message: String, cause: Throwable? = null) : WalletTransactionException(message, cause)

class WalletTransactionTimeoutException(
    message: String = "Transaction timed out",
    cause: Throwable? = null
) : WalletTransactionException(message, cause)

class WalletChainMismatchException(
    expected: String,
    actual: String,
    message: String = "Chain mismatch: expected $expected, got $actual"
) : WalletTransactionException(message)

class WalletProviderSubmitException(message: String, cause: Throwable? = null) : WalletTransactionException(message, cause)

class WalletLifecycleException(message: String, cause: Throwable? = null) : WalletException(message, cause)
