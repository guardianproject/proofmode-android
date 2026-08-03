package org.witness.proofmode.plugins.lp

import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugins.lp.bridge.FlutterEngineProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FlutterEngineReadinessTest {

    @Before
    fun setUp() {
        FlutterEngineProvider.shutdown()
    }

    @Test
    fun isReady_falseBeforeInit() {
        assertFalse(FlutterEngineProvider.isReady())
    }

    @Test
    fun isFlutterEngineReady_falseBeforeInitFlutterEngine() {
        assertFalse(LocationProtocolPlugin.isFlutterEngineReady())
    }
}
