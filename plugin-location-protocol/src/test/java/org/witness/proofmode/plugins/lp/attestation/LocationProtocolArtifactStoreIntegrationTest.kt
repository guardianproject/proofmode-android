package org.witness.proofmode.plugins.lp.attestation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.storage.DefaultStorageProvider
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationProtocolArtifactStoreIntegrationTest {

    @Test
    fun artifactAppearsInGetProofSet() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val defaultStorageProvider = DefaultStorageProvider(context)
        val artifactStore = LocationProtocolArtifactStore(defaultStorageProvider)

        artifactStore.saveOffchainAttestation("testhash123", """{"type":"offchain"}""")

        val proofSet = defaultStorageProvider.getProofSet("testhash123")

        assertTrue(
            "Expected testhash123.lp.offchain.json in proof-set results",
            proofSet.any { uri -> uri.path?.let { File(it).name == "testhash123.lp.offchain.json" } == true }
        )
    }
}
