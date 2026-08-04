package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry
import org.witness.proofmode.plugin.ProofWriteHookRegistry
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalIpfsCidZeroLeakageTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        IpfsCidPlugin.clearRegistrationStateForTests()
    }

    @Test
    fun gateDisabled_writerDoesNotInvokeCidLib() {
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, false).commit()
        val fake = object : StorageProvider {
            val written = mutableListOf<Triple<String, String, ByteArray>>()
            override fun getProofSet(hash: String) = java.util.ArrayList<android.net.Uri>()
            override fun saveBytes(hash: String, identifier: String, data: ByteArray?, listener: StorageListener?) {
                if (data != null) written.add(Triple(hash, identifier, data))
            }
            override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {}
            override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
            override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
            override fun getInputStream(hash: String, identifier: String): InputStream? = null
            override fun proofExists(hash: String) = false
            override fun proofIdentifierExists(hash: String, identifier: String) = false
            override fun getProofItem(uri: android.net.Uri?): InputStream? = null
        }
        val executor = Executors.newSingleThreadExecutor()
        IpfsCidPlugin.register(context, storageProvider = fake, gate = { false })
        ProofArtifactSavedHookRegistry.notify("h", "h.ots")
        ProofSetCidSidecarWriter.scheduleCidSidecarRefresh("h", fake, executor, context)
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)
        assertTrue(fake.written.isEmpty())
    }

    @Test
    fun register_gateDisabled_doesNotAttachHooks() {
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, false).commit()
        IpfsCidPlugin.register(context)
        assertEquals(0, ProofWriteHookRegistry.registeredCountForTests())
        assertEquals(0, ProofArtifactSavedHookRegistry.registeredCountForTests())
    }
}
