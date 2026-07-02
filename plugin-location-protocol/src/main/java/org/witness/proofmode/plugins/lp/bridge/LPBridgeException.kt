package org.witness.proofmode.plugins.lp.bridge

open class LPBridgeException(message: String, cause: Throwable? = null) : Exception(message, cause)

class LPBridgeTimeoutException(message: String, cause: Throwable? = null) : LPBridgeException(message, cause)

class LPBridgeMethodException(message: String, cause: Throwable? = null) : LPBridgeException(message, cause)

class LPBridgeNotReadyException(message: String, cause: Throwable? = null) : LPBridgeException(message, cause)
