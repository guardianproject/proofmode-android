package org.witness.proofmode.util

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import org.witness.proofmode.plugins.lp.wallet.WalletSigningPlugin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class ForegroundWalletActivityBinderTest {

    private lateinit var binder: ForegroundWalletActivityBinder

    @Before
    fun setUp() {
        ForegroundWalletActivityBinder.resetRegistrationStateForTests()
        TestWalletStackHelper.resetWalletStack()
        binder = ForegroundWalletActivityBinder()
    }

    @Test
    fun register_calledTwice_registersOnlyOneCallback() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        ForegroundWalletActivityBinder.register(application)
        ForegroundWalletActivityBinder.register(application)
        val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val callbacks = field.get(application) as ArrayList<Application.ActivityLifecycleCallbacks>
        val binderCount = callbacks.count { it is ForegroundWalletActivityBinder }
        assertEquals(1, binderCount)
    }

    @Test
    fun register_doesNotCrash() {
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        ForegroundWalletActivityBinder.register(application)
    }

    @Test
    fun lifecycle_startAThenB_stopAStillBound_stopBUnbinds() {
        TestWalletStackHelper.setupFakeKeyStore()
        WalletSigningPlugin.register(ApplicationProvider.getApplicationContext())

        val activityA = Robolectric.buildActivity(Activity::class.java).setup().get()
        val activityB = Robolectric.buildActivity(Activity::class.java).setup().get()

        binder.onActivityStarted(activityA)
        assertEquals(activityA, boundActivity())
        assertTrue(LocationProtocolPlugin.hasWalletActivityBound())

        binder.onActivityStarted(activityB)
        assertEquals(activityB, boundActivity())
        assertTrue(LocationProtocolPlugin.hasWalletActivityBound())

        binder.onActivityStopped(activityA)
        assertEquals(activityB, boundActivity())
        assertTrue(LocationProtocolPlugin.hasWalletActivityBound())

        binder.onActivityStopped(activityB)
        assertNull(boundActivity())
        assertFalse(LocationProtocolPlugin.hasWalletActivityBound())
    }

    @Test
    fun awaitWalletActivityBound_returnsTrueWhenActivityAlreadyStarted() = runTest {
        TestWalletStackHelper.setupFakeKeyStore()
        WalletSigningPlugin.register(ApplicationProvider.getApplicationContext())
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        binder.onActivityStarted(activity)

        val result = async {
            ForegroundWalletActivityBinder.awaitWalletActivityBound(timeoutMs = 1_000L)
        }
        assertTrue(result.await())
    }

    @Test
    fun awaitWalletActivityBound_returnsTrueAfterDelayedStart() = runTest {
        TestWalletStackHelper.setupFakeKeyStore()
        WalletSigningPlugin.register(ApplicationProvider.getApplicationContext())
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        val deferred = async {
            ForegroundWalletActivityBinder.awaitWalletActivityBound(timeoutMs = 2_000L)
        }
        kotlinx.coroutines.delay(100)
        binder.onActivityStarted(activity)

        assertTrue(deferred.await())
    }

    @Test
    fun awaitWalletActivityBound_returnsFalseOnTimeout() = runTest {
        TestWalletStackHelper.setupFakeKeyStore()
        WalletSigningPlugin.register(ApplicationProvider.getApplicationContext())

        val result = ForegroundWalletActivityBinder.awaitWalletActivityBound(timeoutMs = 200L)
        assertFalse(result)
    }

    private fun boundActivity(): Activity? {
        val field = ForegroundWalletActivityBinder::class.java.getDeclaredField("boundActivity")
        field.isAccessible = true
        return field.get(binder) as Activity?
    }
}
