package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.witness.proofmode.ProofMode
import org.witness.proofmode.plugin.ProofWriteEvent
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ProofSetCidSidecarWriterTest {

    @Test
    fun scheduleInitialSidecarWrite_gateOff_doesNotCreateSidecar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, false).commit()

        val hash = "testhash00000001"
        val tempDir = setupProofSetDir(context, hash)
        val mediaFile = File(tempDir, "media.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val executor = Executors.newSingleThreadExecutor()
        scheduleInitialSidecarWrite(context, hash, mediaFile, testStorageProvider(tempDir), executor)
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertFalse(File(tempDir, IpfsCidSidecar.sidecarBasename(hash)).exists())
    }

    @Test
    fun scheduleInitialSidecarWrite_gateOn_writesIpfsCidsJsonWithMediaLink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()

        val hash = "a1b2c3d4e5f6"
        val dir = setupProofSetDir(context, hash)
        val mediaFile = File(dir, "media.bin").apply { writeBytes("media-payload".toByteArray()) }

        val executor = Executors.newSingleThreadExecutor()
        scheduleInitialSidecarWrite(context, hash, mediaFile, DefaultStorageProvider(context), executor)
        executor.shutdown()
        executor.awaitTermination(15, TimeUnit.SECONDS)

        val sidecar = File(dir, IpfsCidSidecar.sidecarBasename(hash))
        assertTrue(sidecar.exists())
        val json = sidecar.readText()
        assertTrue(json.contains("rootCid"))
        assertTrue(json.contains("$hash.bin") || json.contains("$hash.jpg"))
        assertTrue(json.contains("$hash.proof.csv"))
        assertTrue(json.contains("\"tsizes\""))
    }

    @Test
    fun scheduleInitialSidecarWrite_doubleWrite_replacesSidecar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()

        val hash = "a1b2c3d4e5f6"
        val dir = setupProofSetDir(context, hash)
        val mediaFile = File(dir, "media.bin").apply { writeBytes("media-payload".toByteArray()) }
        val provider = DefaultStorageProvider(context)
        val executor = Executors.newSingleThreadExecutor()
        scheduleInitialSidecarWrite(context, hash, mediaFile, provider, executor)
        scheduleInitialSidecarWrite(context, hash, mediaFile, provider, executor)
        executor.shutdown()
        executor.awaitTermination(20, TimeUnit.SECONDS)

        val sidecar = File(dir, IpfsCidSidecar.sidecarBasename(hash))
        assertTrue(sidecar.exists())
        Json.parseToJsonElement(sidecar.readText())
    }

    @Test
    fun schedule_coalescesOverlappingJobsForSameHash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        val hash = "coalescehash0001"
        val dir = setupProofSetDir(context, hash)
        val mediaFile = File(dir, "media.bin").apply { writeBytes(byteArrayOf(9)) }
        val provider = DefaultStorageProvider(context)
        val executor = Executors.newSingleThreadExecutor()
        val event = proofWriteEvent(context, hash, mediaFile, provider, executor)
        ProofSetCidSidecarWriter.scheduleInitialSidecarWrite(event, executor)
        ProofSetCidSidecarWriter.scheduleInitialSidecarWrite(event, executor)
        executor.shutdown()
        executor.awaitTermination(15, TimeUnit.SECONDS)
        val sidecar = File(dir, IpfsCidSidecar.sidecarBasename(hash))
        assertTrue(sidecar.exists())
    }
}

@RunWith(AndroidJUnit4::class)
class ProofSetCidSidecarWriterMultiTriggerTest {

    @Before
    fun setUp() {
        resetSidecarWriterTestState()
    }

    @Test
    fun proofWriteThenOtsSave_recomputesWithOtsInManifest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true).commit()

        IpfsCidPlugin.clearRegistrationStateForTests()
        IpfsCidPlugin.register(context)

        val hash = "multitrigger0001"
        val dir = setupProofSetDir(context, hash)
        val mediaFile = File(dir, "media.bin").apply { writeBytes("multitrigger-media".toByteArray()) }
        val provider = DefaultStorageProvider(context)
        val executor = Executors.newSingleThreadExecutor()

        scheduleInitialSidecarWrite(context, hash, mediaFile, provider, executor)
        executor.shutdown()
        executor.awaitTermination(15, TimeUnit.SECONDS)

        val sidecarFile = File(dir, IpfsCidSidecar.sidecarBasename(hash))
        assertTrue(sidecarFile.exists())
        val before = IpfsCidSidecar.decode(sidecarFile.readBytes())
        val mediaKey = before.files.keys.first { it.startsWith("$hash.") && !it.contains(".proof.") && !it.endsWith(".ots") && !it.endsWith(".nostr") }
        assertTrue(mediaKey.endsWith(".bin") || mediaKey.endsWith(".jpg"))

        provider.saveBytes(hash, "$hash.ots", byteArrayOf(0x01, 0x02), null)

        val deadline = System.currentTimeMillis() + 20_000
        var after = before
        while (System.currentTimeMillis() < deadline) {
            after = IpfsCidSidecar.decode(sidecarFile.readBytes())
            if (after.files.containsKey("$hash.ots")) break
            Thread.sleep(200)
        }

        assertTrue(after.files.containsKey("$hash.ots"))
        assertNotEquals(before.rootCid, after.rootCid)
        assertTrue(after.tsizes.containsKey("$hash.ots"))
    }

    @Test
    fun rapidWriteAndOts_coalescedToValidSidecar() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true).commit()

        IpfsCidPlugin.clearRegistrationStateForTests()
        IpfsCidPlugin.register(context)

        val hash = "rapidcoalesce01"
        val dir = setupProofSetDir(context, hash)
        val mediaFile = File(dir, "media.bin").apply { writeBytes("rapid-media".toByteArray()) }
        val provider = DefaultStorageProvider(context)
        val executor = Executors.newSingleThreadExecutor()
        val event = proofWriteEvent(context, hash, mediaFile, provider, executor)

        ProofSetCidSidecarWriter.scheduleInitialSidecarWrite(event, executor)
        provider.saveBytes(hash, "$hash.ots", byteArrayOf(0x0A), null)

        val deadline = System.currentTimeMillis() + 20_000
        var sidecar = IpfsCidSidecar.decode(
            File(dir, IpfsCidSidecar.sidecarBasename(hash)).readBytes(),
        )
        while (System.currentTimeMillis() < deadline) {
            sidecar = IpfsCidSidecar.decode(
                File(dir, IpfsCidSidecar.sidecarBasename(hash)).readBytes(),
            )
            if (sidecar.files.containsKey("$hash.ots")) break
            Thread.sleep(200)
        }

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        val mediaKey = sidecar.files.keys.first { it.startsWith("$hash.") && !it.contains(".proof.") && !it.endsWith(".ots") }
        assertTrue(mediaKey.endsWith(".bin") || mediaKey.endsWith(".jpg"))
        assertTrue(sidecar.files.containsKey("$hash.ots"))
    }
}

@RunWith(AndroidJUnit4::class)
class ProofSetCidMembershipIntegrationTest {

    @Test
    fun firstCompute_excludesLpAndSidecarFromManifest() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(LocalIpfsCidGate.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(LocalIpfsCidGate.KEY_ENABLED, true).commit()

        val hash = "membershipint01"
        val dir = setupProofSetDir(context, hash)
        File(dir, "$hash.lp.offchain.json").writeText("{}")
        File(dir, IpfsCidSidecar.sidecarBasename(hash)).writeText("""{"stub":true}""")
        val mediaFile = File(dir, "media.bin").apply { writeBytes("membership-media".toByteArray()) }

        val executor = Executors.newSingleThreadExecutor()
        scheduleInitialSidecarWrite(context, hash, mediaFile, DefaultStorageProvider(context), executor)
        executor.shutdown()
        executor.awaitTermination(15, TimeUnit.SECONDS)

        val parsed = IpfsCidSidecar.decode(
            File(dir, IpfsCidSidecar.sidecarBasename(hash)).readBytes(),
        )
        val mediaKey = parsed.files.keys.first { it.startsWith("$hash.") && !it.contains(".proof.") }
        assertTrue(mediaKey.endsWith(".bin") || mediaKey.endsWith(".jpg"))
        assertFalse(parsed.files.keys.any { it.contains(".lp.") })
        assertFalse(parsed.files.keys.any { it.endsWith(IpfsCidSidecar.SIDECAR_SUFFIX) })
    }
}

fun setupProofSetDir(context: Context, hash: String): File {
    val dir = DefaultStorageProvider(context).getHashStorageDir(hash)!!
    dir.mkdirs()
    File(dir, "$hash.proof.csv").writeText("col1,col2\nval1,val2\n")
    File(dir, "$hash.proof.json").writeText("""{"k":"v"}""")
    File(dir, "$hash.asc").writeText("-----BEGIN PGP SIGNATURE-----\n")
    return dir
}

fun scheduleInitialSidecarWrite(
    context: Context,
    hash: String,
    mediaFile: File,
    storageProvider: StorageProvider,
    executor: java.util.concurrent.ExecutorService,
) {
    ProofSetCidSidecarWriter.scheduleInitialSidecarWrite(
        proofWriteEvent(context, hash, mediaFile, storageProvider, executor),
        executor,
    )
}

fun proofWriteEvent(
    context: Context,
    hash: String,
    mediaFile: File,
    storageProvider: StorageProvider,
    executor: java.util.concurrent.ExecutorService,
) = ProofWriteEvent(
    context = context,
    mediaHash = hash,
    mediaUri = Uri.fromFile(mediaFile),
    storageProvider = storageProvider,
    executor = executor,
)

fun testStorageProvider(dir: File): StorageProvider = object : StorageProvider {
    override fun getProofSet(hash: String): java.util.ArrayList<Uri> =
        java.util.ArrayList(dir.listFiles()?.map { Uri.fromFile(it) } ?: emptyList())

    override fun saveBytes(hash: String, identifier: String, data: ByteArray?, listener: StorageListener?) {
        if (data != null) File(dir, identifier).writeBytes(data)
    }

    override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {}
    override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
    override fun getInputStream(hash: String, identifier: String): InputStream? {
        val f = File(dir, identifier)
        return if (f.exists()) f.inputStream() else null
    }
    override fun proofExists(hash: String): Boolean = false
    override fun proofIdentifierExists(hash: String, identifier: String): Boolean = false
    override fun getProofItem(uri: Uri?): InputStream? = null
}

fun resetSidecarWriterTestState() {
    ProofSetCidSidecarWriter.resetSidecarWriterTestState()
    IpfsCidPlugin.clearRegistrationStateForTests()
}
