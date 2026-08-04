package org.witness.proofmode.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.storage.DefaultStorageProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofHookZeroLeakageTest {
    @After
    fun tearDown() {
        ProofWriteHookRegistry.clearForTests()
        ProofArtifactSavedHookRegistry.clearForTests()
    }

    @Test
    fun proofWriteRegistry_startsEmpty() {
        ProofWriteHookRegistry.clearForTests()
        assertEquals(0, ProofWriteHookRegistry.registeredCountForTests())
    }

    @Test
    fun proofArtifactRegistry_startsEmpty() {
        ProofArtifactSavedHookRegistry.clearForTests()
        assertEquals(0, ProofArtifactSavedHookRegistry.registeredCountForTests())
    }

    @Test
    fun notify_withEmptyRegistries_completesQuickly() {
        ProofWriteHookRegistry.clearForTests()
        ProofArtifactSavedHookRegistry.clearForTests()
        val context = ApplicationProvider.getApplicationContext<Context>()
        ProofWriteHookRegistry.notify(
            ProofWriteEvent(
                context = context,
                mediaHash = "h",
                mediaUri = android.net.Uri.parse("content://test"),
                storageProvider = org.witness.proofmode.storage.DefaultStorageProvider(context),
                executor = java.util.concurrent.Executors.newSingleThreadExecutor(),
            ),
        )
        ProofArtifactSavedHookRegistry.notify("h", "h.ots")
    }

    @Test
    fun defaultStorageProvider_notifyWithEmptyRegistry_doesNotThrow() {
        ProofArtifactSavedHookRegistry.clearForTests()
        val context = ApplicationProvider.getApplicationContext<Context>()
        DefaultStorageProvider(context).saveBytes("h", "h.ots", byteArrayOf(1), null)
    }
}
