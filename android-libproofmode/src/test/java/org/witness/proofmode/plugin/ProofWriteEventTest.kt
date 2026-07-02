package org.witness.proofmode.plugin

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.storage.StorageProvider
import java.io.InputStream
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofWriteEventTest {
    @Test
    fun proofWriteEvent_exposesSpecFields() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://test/media")
        val executor = Executors.newSingleThreadExecutor()
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
        val event = ProofWriteEvent(
            context = context,
            mediaHash = "abc123",
            mediaUri = uri,
            storageProvider = provider,
            executor = executor,
        )
        assertSame(context, event.context)
        assertEquals("abc123", event.mediaHash)
        assertSame(uri, event.mediaUri)
        assertSame(provider, event.storageProvider)
        assertSame(executor, event.executor)
    }
}
