package org.witness.proofmode.plugins.lp

import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationProtocolPluginHasWalletActivityTest {

    @Before
    fun setUp() {
        TestWalletStackReset.reset()
    }

    @Test
    fun hasWalletActivityBound_falseBeforeWalletRegister() {
        assertFalse(LocationProtocolPlugin.hasWalletActivityBound())
    }
}
