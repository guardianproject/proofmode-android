package org.witness.proofmode.plugins.lp

import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin

/**
 * Test-only reset for [WalletSigningPlugin] singleton state between Robolectric tests.
 * Uses reflection because [providerSelection] is lateinit with private set. The sponsorship
 * probe is stubbed to answer UNKNOWN without a request, because the sponsor toggle defaults
 * on and a real project id in secrets.properties would otherwise reach the network.
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
        WalletSigningPlugin.sponsorshipProbe = { _, _ -> null }
    }
}
