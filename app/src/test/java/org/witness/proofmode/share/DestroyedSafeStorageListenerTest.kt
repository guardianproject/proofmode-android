package org.witness.proofmode.share

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Plain JVM tests for [DestroyedSafeStorageListener] (app module has no Robolectric). */
class DestroyedSafeStorageListenerTest {

    private class FakeLifecycle : Lifecycle() {
        private val observers = mutableListOf<LifecycleObserver>()
        private var state: State = State.RESUMED
        private val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = this@FakeLifecycle
        }

        override val currentState: State get() = state

        override fun addObserver(observer: LifecycleObserver) {
            observers.add(observer)
        }

        override fun removeObserver(observer: LifecycleObserver) {
            observers.remove(observer)
        }

        fun moveTo(newState: State) {
            state = newState
        }

        fun destroy() {
            state = State.DESTROYED
            observers.filterIsInstance<LifecycleEventObserver>().forEach {
                it.onStateChanged(owner, Event.ON_DESTROY)
            }
        }
    }

    private fun listener(
        lifecycle: FakeLifecycle,
        onSuccess: (String?) -> Unit = {},
        onFailure: (String) -> Unit = {},
    ) = DestroyedSafeStorageListener(
        // Retained Context field; unit tests never call through it.
        object : android.content.ContextWrapper(
            android.app.Application(),
        ) {},
        lifecycle,
        onSuccess,
        onFailure,
        postToMain = { it.run() },
    )

    @Test
    fun success_deliversWhileStoppedButAlive() {
        val lifecycle = FakeLifecycle()
        val successes = AtomicInteger(0)
        val l = listener(lifecycle, onSuccess = { successes.incrementAndGet() })
        lifecycle.moveTo(Lifecycle.State.CREATED)
        l.saveSuccessful("h", "https://example")
        assertEquals(1, successes.get())
    }

    @Test
    fun success_noopsAfterDestroyed() {
        val lifecycle = FakeLifecycle()
        val successes = AtomicInteger(0)
        val l = listener(lifecycle, onSuccess = { successes.incrementAndGet() })
        lifecycle.destroy()
        l.saveSuccessful("h", "https://example")
        assertEquals(0, successes.get())
    }

    @Test
    fun failure_noopsAfterDestroyed() {
        val lifecycle = FakeLifecycle()
        val failures = AtomicInteger(0)
        val l = listener(lifecycle, onFailure = { failures.incrementAndGet() })
        lifecycle.destroy()
        l.saveFailed(RuntimeException("boom"))
        assertEquals(0, failures.get())
    }

    @Test
    fun listenerFields_doNotDeclareActivityType() {
        val lifecycle = FakeLifecycle()
        val l = listener(lifecycle)
        assertTrue(
            l.javaClass.declaredFields.none {
                it.type.name.contains("Activity") || it.type.name.contains("CoroutineScope")
            },
        )
    }
}
