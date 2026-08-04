package org.witness.proofmode.plugins.lp

import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin

/**
 * Test-only reset for [WalletSigningPlugin] singleton state between Robolectric tests.
 * Uses reflection because [providerSelection] is lateinit with private set.
 */
object TestWalletStackReset {

    fun reset() {
        val clazz = WalletSigningPlugin::class.java
        clazz.getDeclaredField("sessionStore").apply {
            isAccessible = true
            set(WalletSigningPlugin, null)
        }
        clazz.getDeclaredField("providerSelection").apply {
            isAccessible = true
            set(WalletSigningPlugin, null)
        }
        clazz.getDeclaredField("sdkConfig").apply {
            isAccessible = true
            set(WalletSigningPlugin, null)
        }
    }
}
