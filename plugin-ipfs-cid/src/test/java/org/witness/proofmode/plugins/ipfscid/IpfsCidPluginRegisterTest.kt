package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry
import org.witness.proofmode.plugin.ProofWriteHookRegistry
import org.witness.proofmode.ProofMode
import org.witness.proofmode.cid.CidLib
import org.witness.proofmode.cid.NamedBytes
import org.witness.proofmode.storage.StorageProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IpfsCidPluginRegisterTest {
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
    fun register_gateOff_doesNotAttachHooks() {
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, false).commit()
        IpfsCidPlugin.register(context)
        assertEquals(0, ProofWriteHookRegistry.registeredCountForTests())
        assertEquals(0, ProofArtifactSavedHookRegistry.registeredCountForTests())
    }

    @Test
    fun register_gateOn_attachesProofWriteAndArtifactHooks() {
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        IpfsCidPlugin.register(context)
        assertEquals(1, ProofWriteHookRegistry.registeredCountForTests())
        assertEquals(1, ProofArtifactSavedHookRegistry.registeredCountForTests())
    }

    @Test
    fun register_calledTwice_gateOn_idempotentHookCount() {
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        IpfsCidPlugin.register(context)
        IpfsCidPlugin.register(context)
        assertEquals(1, ProofWriteHookRegistry.registeredCountForTests())
        assertEquals(1, ProofArtifactSavedHookRegistry.registeredCountForTests())
    }

    @Test
    fun register_usesInjectedStorageProvider_forArtifactHook() {
        try {
            CidLib.computeProofSetCid(listOf(NamedBytes("_", byteArrayOf(0))))
        } catch (e: UnsatisfiedLinkError) {
            assumeNoException(e)
        }
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true).commit()
        val hash = "injectprov0001"
        val sidecar = IpfsCidSidecar.encode(
            rootCid = "bafyOLD",
            files = mapOf("$hash.bin" to "bafkMEDIA", "$hash.proof.csv" to "bafkCSV"),
            tsizes = mapOf("$hash.bin" to 10L, "$hash.proof.csv" to 5L),
            computedAtMs = 1L,
        )
        val storage = object : StorageProvider {
            val written = mutableListOf<Triple<String, String, ByteArray>>()

            override fun getProofSet(h: String) = java.util.ArrayList(
                listOf(
                    android.net.Uri.parse("file:///fake/${IpfsCidSidecar.sidecarBasename(h)}"),
                    android.net.Uri.parse("file:///fake/$h.proof.csv"),
                    android.net.Uri.parse("file:///fake/$h.ots"),
                ),
            )

            override fun saveBytes(hash: String, identifier: String, data: ByteArray?, listener: org.witness.proofmode.storage.StorageListener?) {
                if (data != null) written.add(Triple(hash, identifier, data))
            }

            override fun saveStream(hash: String, identifier: String, stream: java.io.InputStream, listener: org.witness.proofmode.storage.StorageListener?) {}
            override fun saveText(hash: String, identifier: String, data: String, listener: org.witness.proofmode.storage.StorageListener?) {}
            override fun replaceText(hash: String, identifier: String, data: String, listener: org.witness.proofmode.storage.StorageListener?) {}
            override fun getInputStream(h: String, identifier: String): java.io.InputStream? = when (identifier) {
                IpfsCidSidecar.sidecarBasename(h) -> java.io.ByteArrayInputStream(sidecar)
                "$h.ots" -> java.io.ByteArrayInputStream(byteArrayOf(1, 2))
                else -> null
            }
            override fun proofExists(hash: String) = false
            override fun proofIdentifierExists(hash: String, identifier: String) = false
            override fun getProofItem(uri: android.net.Uri?): java.io.InputStream? = null
        }
        IpfsCidPlugin.register(context, storageProvider = storage, gate = { true })
        assertEquals(storage, IpfsCidPlugin.registeredStorageProviderForTests())
        ProofArtifactSavedHookRegistry.notify(hash, "$hash.ots")
        Thread.sleep(2000)
        assertTrue(storage.written.isNotEmpty())
    }
}
