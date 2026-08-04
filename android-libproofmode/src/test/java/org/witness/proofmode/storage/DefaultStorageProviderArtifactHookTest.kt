package org.witness.proofmode.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.plugin.ProofArtifactSavedHook
import org.witness.proofmode.plugin.ProofArtifactSavedHookRegistry
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DefaultStorageProviderArtifactHookTest {
    private lateinit var context: Context
    private lateinit var provider: DefaultStorageProvider
    private val captured = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = DefaultStorageProvider(context)
        ProofArtifactSavedHookRegistry.register(
            ProofArtifactSavedHook { hash, identifier ->
                captured.add(hash to identifier)
            },
        )
    }

    @After
    fun tearDown() {
        ProofArtifactSavedHookRegistry.clearForTests()
    }

    @Test
    fun saveBytes_success_notifiesRegistry() {
        val hash = "testhash1"
        provider.saveBytes(hash, "$hash.ots", byteArrayOf(1, 2, 3), null)
        assertEquals(1, captured.size)
        assertEquals(hash to "$hash.ots", captured.single())
    }

    @Test
    fun saveStream_success_notifiesRegistry() {
        val hash = "testhash2"
        provider.saveStream(hash, "$hash.ots", ByteArrayInputStream(byteArrayOf(4, 5)), null)
        assertEquals(1, captured.size)
        assertEquals(hash to "$hash.ots", captured.single())
    }

    @Test
    fun saveBytes_failedWrite_doesNotNotify() {
        val hash = "testhash3"
        provider.saveBytes(hash, "$hash.ots", null, null)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun saveText_doesNotNotifyArtifactHook() {
        val hash = "testhash4"
        provider.saveText(hash, "$hash.csv", "line", null)
        assertTrue(captured.isEmpty())
    }
}
