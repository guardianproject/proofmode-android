package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.witness.proofmode.ProofMode
import org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry
import org.witness.proofmode.plugin.ProofWriteHookRegistry
import org.witness.proofmode.storage.DefaultStorageProvider
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class IpfsCidPluginLateNotaryE2ETest {

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
    fun registeredHooks_otsAndNostrSave_updatesSidecarViaArtifactHookOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, true)
            .commit()

        IpfsCidPlugin.register(context)

        val hash = "e2ehooknotary01"
        val dir = setupProofSetDir(context, hash)
        val mediaFile = File(dir, "capture.jpg").apply {
            writeBytes("e2e-jpeg-payload".toByteArray())
        }
        val provider = DefaultStorageProvider(context)
        val executor = Executors.newSingleThreadExecutor()

        ProofSetCidSidecarWriter.scheduleInitialSidecarWrite(
            proofWriteEvent(context, hash, mediaFile, provider, executor),
            executor,
        )
        executor.shutdown()
        executor.awaitTermination(20, TimeUnit.SECONDS)

        val sidecarPath = File(dir, IpfsCidSidecar.sidecarBasename(hash))
        assertTrue(sidecarPath.exists())
        val before = IpfsCidSidecar.decode(sidecarPath.readBytes())
        val mediaKey = before.files.keys.first { it.startsWith("$hash.") && !it.contains(".proof.") }
        assertTrue(mediaKey.endsWith(".jpg") || mediaKey.endsWith(".bin"))

        provider.saveBytes(hash, "$hash.ots", byteArrayOf(0x01, 0x02, 0x03), null)
        provider.saveBytes(hash, "$hash.nostr", byteArrayOf(0x04), null)

        val deadline = System.currentTimeMillis() + 30_000
        var after = before
        while (System.currentTimeMillis() < deadline) {
            after = IpfsCidSidecar.decode(sidecarPath.readBytes())
            if (after.files.containsKey("$hash.ots") && after.files.containsKey("$hash.nostr")) break
            Thread.sleep(200)
        }

        assertTrue("files missing .ots: ${after.files.keys}", after.files.containsKey("$hash.ots"))
        assertTrue("files missing .nostr: ${after.files.keys}", after.files.containsKey("$hash.nostr"))
        assertTrue(after.tsizes.containsKey("$hash.ots"))
        assertTrue(after.tsizes.containsKey("$hash.nostr"))
        assertNotEquals(before.rootCid, after.rootCid)
    }
}
