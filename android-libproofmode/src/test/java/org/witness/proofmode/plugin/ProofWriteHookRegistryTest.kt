package org.witness.proofmode.plugin

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.storage.StorageProvider
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofWriteHookRegistryTest {
    @After
    fun tearDown() {
        ProofWriteHookRegistry.clearForTests()
    }

    @Test
    fun notify_withNoRegisteredHooks_doesNotThrow() {
        ProofWriteHookRegistry.notify(sampleEvent())
    }

    @Test
    fun notify_deliversEventToRegisteredHook() {
        var received: ProofWriteEvent? = null
        val hook = ProofWriteHook { received = it }
        ProofWriteHookRegistry.register(hook)
        val event = sampleEvent()
        ProofWriteHookRegistry.notify(event)
        assertEquals(event, received)
    }

    @Test
    fun register_isThreadSafe_copyOnWrite() {
        val hook = ProofWriteHook { }
        val pool = Executors.newFixedThreadPool(4)
        repeat(8) {
            pool.submit {
                ProofWriteHookRegistry.register(hook)
                ProofWriteHookRegistry.notify(sampleEvent())
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test
    fun unregister_removesHook() {
        var count = 0
        val hook = ProofWriteHook { count++ }
        ProofWriteHookRegistry.register(hook)
        ProofWriteHookRegistry.notify(sampleEvent())
        ProofWriteHookRegistry.unregister(hook)
        ProofWriteHookRegistry.notify(sampleEvent())
        assertEquals(1, count)
    }

    private fun sampleEvent(): ProofWriteEvent {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = object : StorageProvider {
            override fun getProofSet(hash: String) = java.util.ArrayList<Uri>()
            override fun saveBytes(hash: String, identifier: String, data: ByteArray?, listener: org.witness.proofmode.storage.StorageListener?) {}
            override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: org.witness.proofmode.storage.StorageListener?) {}
            override fun saveText(hash: String, identifier: String, data: String, listener: org.witness.proofmode.storage.StorageListener?) {}
            override fun getInputStream(hash: String, identifier: String): InputStream? = null
            override fun proofExists(hash: String) = false
            override fun proofIdentifierExists(hash: String, identifier: String) = false
            override fun getProofItem(uri: Uri?): InputStream? = null
        }
        return ProofWriteEvent(
            context = context,
            mediaHash = "hash",
            mediaUri = Uri.parse("content://test/media"),
            storageProvider = provider,
            executor = Executors.newSingleThreadExecutor(),
        )
    }
}
