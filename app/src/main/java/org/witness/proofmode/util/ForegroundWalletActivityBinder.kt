package org.witness.proofmode.util

import android.app.Activity
import android.app.Application
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.delay
import org.witness.proofmode.plugins.lp.LocationProtocolPlugin
import java.util.concurrent.atomic.AtomicBoolean

class ForegroundWalletActivityBinder : Application.ActivityLifecycleCallbacks {

    private var boundActivity: Activity? = null

    override fun onActivityStarted(activity: Activity) {
        boundActivity = activity
        LocationProtocolPlugin.bindWalletActivity(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        if (boundActivity === activity) {
            LocationProtocolPlugin.unbindWalletActivity()
            boundActivity = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        private val registered = AtomicBoolean(false)
        private const val BIND_POLL_MS = 100L

        fun register(application: Application) {
            if (!registered.compareAndSet(false, true)) return
            application.registerActivityLifecycleCallbacks(ForegroundWalletActivityBinder())
        }

        /** Test-only: reset singleton guard between tests. */
        internal fun resetRegistrationStateForTests() {
            registered.set(false)
        }

        @VisibleForTesting
        internal fun isRegisteredForTests(): Boolean = registered.get()

        /**
         * Suspend until [LocationProtocolPlugin.hasWalletActivityBound] is true or [timeoutMs] elapses.
         * Used by LP orchestrator to defer on-chain manual jobs across Activity transitions.
         */
        suspend fun awaitWalletActivityBound(timeoutMs: Long): Boolean {
            if (LocationProtocolPlugin.hasWalletActivityBound()) return true
            val maxPolls = ((timeoutMs + BIND_POLL_MS - 1) / BIND_POLL_MS).coerceAtLeast(1)
            repeat(maxPolls.toInt()) {
                delay(BIND_POLL_MS)
                if (LocationProtocolPlugin.hasWalletActivityBound()) return true
            }
            return LocationProtocolPlugin.hasWalletActivityBound()
        }
    }
}
