package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.ProofMode
import org.witness.proofmode.plugin.ProofArtifactSavedHook
import org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry
import org.witness.proofmode.plugin.ProofWriteHookRegistry
import org.witness.proofmode.storage.DefaultStorageProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class IpfsCidPluginHookWiringTest {

    @Before
    fun setUp() {
        IpfsCidPlugin.clearRegistrationStateForTests()
        ProofWriteHookRegistry.clearForTests()
        ProofArtifactSavedHookRegistry.clearForTests()
    }

    @After
    fun tearDown() {
        IpfsCidPlugin.clearRegistrationStateForTests()
    }

    @Test
    fun register_attachesHooks_saveBytesNotifiesArtifactRegistry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true).commit()

        val observed = mutableListOf<Pair<String, String>>()
        ProofArtifactSavedHookRegistry.register(
            ProofArtifactSavedHook { hash, identifier -> observed.add(hash to identifier) },
        )

        IpfsCidPlugin.register(context)
        assertTrue(ProofArtifactSavedHookRegistry.registeredCountForTests() >= 1)

        val hash = "hookwiring00001"
        setupProofSetDir(context, hash)
        DefaultStorageProvider(context).saveBytes(hash, "$hash.ots", byteArrayOf(0x01), null)

        assertTrue(observed.any { it.first == hash && it.second == "$hash.ots" })
    }
}
