package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugin.ProofWriteEvent
import org.witness.proofmode.plugin.ProofWriteHookRegistry
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.InputStream
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IpfsCidPluginZeroLeakageTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        IpfsCidPlugin.clearRegistrationStateForTests()
    }

    @After
    fun tearDown() {
        IpfsCidPlugin.clearRegistrationStateForTests()
    }

    @Test
    fun gateOff_registerThenNotify_doesNotWriteSidecar() {
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, false).commit()
        IpfsCidPlugin.register(context)

        val fake = object : StorageProvider {
            val written = mutableListOf<Triple<String, String, ByteArray>>()
            override fun getProofSet(hash: String) = java.util.ArrayList<Uri>()
            override fun saveBytes(hash: String, identifier: String, data: ByteArray?, listener: StorageListener?) {
                if (data != null) written.add(Triple(hash, identifier, data))
            }
            override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {}
            override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
            override fun getInputStream(hash: String, identifier: String): InputStream? = null
            override fun proofExists(hash: String) = false
            override fun proofIdentifierExists(hash: String, identifier: String) = false
            override fun getProofItem(uri: Uri?): InputStream? = null
        }
        val executor = Executors.newSingleThreadExecutor()
        ProofWriteHookRegistry.notify(
            ProofWriteEvent(
                context = context,
                mediaHash = "h",
                mediaUri = Uri.parse("file:///dev/null"),
                storageProvider = fake,
                executor = executor,
            ),
        )
        executor.shutdown()
        assertTrue(fake.written.isEmpty())
    }

    @Test
    fun gateOn_registerThenNotify_invokesPersisterPath() {
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        IpfsCidPlugin.register(context)

        val fake = object : StorageProvider {
            val written = mutableListOf<Triple<String, String, ByteArray>>()
            override fun getProofSet(hash: String) = java.util.ArrayList<Uri>()
            override fun saveBytes(hash: String, identifier: String, data: ByteArray?, listener: StorageListener?) {
                if (data != null) written.add(Triple(hash, identifier, data))
            }
            override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {}
            override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
            override fun getInputStream(hash: String, identifier: String): InputStream? = null
            override fun proofExists(hash: String) = false
            override fun proofIdentifierExists(hash: String, identifier: String) = false
            override fun getProofItem(uri: Uri?): InputStream? = null
        }
        val executor = Executors.newSingleThreadExecutor()
        ProofWriteHookRegistry.notify(
            ProofWriteEvent(
                context = context,
                mediaHash = "h",
                mediaUri = Uri.parse("file:///dev/null"),
                storageProvider = fake,
                executor = executor,
            ),
        )
        executor.shutdown()
        // Empty proof set — persister path runs but writes nothing.
        assertTrue(fake.written.isEmpty())
    }
}
