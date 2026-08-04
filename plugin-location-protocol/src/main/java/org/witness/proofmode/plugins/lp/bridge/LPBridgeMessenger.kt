package org.witness.proofmode.plugins.lp.bridge

interface LPBridgeMessenger {
    suspend fun awaitReady()

    suspend fun invokeMethod(method: String, arguments: Any?): Any?
}
