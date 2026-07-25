package org.witness.proofmode.share

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.lang.ref.WeakReference
import org.witness.proofmode.storage.StorageListener

/**
 * Long-lived [StorageListener] for Filebase uploads that must not retain an Activity.
 *
 * Fields hold only WeakRefs and a destroyed flag (no Activity / lifecycleScope).
 * UI delivery is gated on [Lifecycle.State.DESTROYED] (not STARTED) so stopped-but-alive
 * Activities still update. [postToMain] defaults to the main looper; tests inject sync dispatch.
 */
internal class DestroyedSafeStorageListener(
    /** Application context only — never an Activity. Retained for string/Toast-safe Context. */
    @Suppress("UnusedPrivateProperty")
    private val appContext: android.content.Context,
    lifecycle: Lifecycle,
    private val onSuccess: (String?) -> Unit,
    private val onFailureMessage: (String) -> Unit,
    private val postToMain: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
) : StorageListener {
    private val lifecycleRef = WeakReference(lifecycle)

    @Volatile
    private var destroyed = false

    init {
        // Lifecycle.addObserver must run on the main thread (AndroidX enforces this).
        // Callers may construct this from Dispatchers.IO; always register via postToMain.
        postToMain {
            if (lifecycle.currentState == Lifecycle.State.DESTROYED) {
                destroyed = true
                return@postToMain
            }
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) destroyed = true
            })
        }
    }

    override fun saveSuccessful(hash: String?, uri: String?) {
        if (destroyed || lifecycleRef.get()?.currentState == Lifecycle.State.DESTROYED) return
        postToMain {
            if (destroyed) return@postToMain
            onSuccess(uri)
        }
    }

    override fun saveFailed(exception: Exception?) {
        if (destroyed || lifecycleRef.get()?.currentState == Lifecycle.State.DESTROYED) return
        val message = exception?.message ?: "unknown"
        postToMain {
            if (destroyed) return@postToMain
            onFailureMessage(message)
        }
    }
}
