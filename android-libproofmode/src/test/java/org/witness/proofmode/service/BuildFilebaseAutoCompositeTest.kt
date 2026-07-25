package org.witness.proofmode.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.storage.AccumulatingStorageProvider
import org.witness.proofmode.storage.CompositeStorageProvider
import org.witness.proofmode.storage.StorageProvider
import org.witness.proofmode.storage.filebase.FilebaseConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BuildFilebaseAutoCompositeTest {

    private lateinit var context: Context
    private lateinit var primary: StorageProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        primary = AccumulatingStorageProvider()
    }

    private fun s3Config(autoUpload: Boolean = true) = FilebaseConfig(
        accessKey = "ak",
        secretKey = "sk",
        bucketName = "bucket",
        enabled = true,
        autoUpload = autoUpload,
    )

    private fun ipfsConfig(autoUpload: Boolean = true) = FilebaseConfig(
        accessKey = "ak",
        secretKey = "sk",
        bucketName = "bucket",
        enabled = true,
        ipfsBearerToken = "token",
        autoUpload = autoUpload,
    )

    private fun CompositeStorageProvider.deferProofSetUploadForTest(): Boolean {
        val field = CompositeStorageProvider::class.java.getDeclaredField("deferProofSetUpload")
        field.isAccessible = true
        return field.getBoolean(this)
    }

    private fun CompositeStorageProvider.filebaseConfigForTest(): FilebaseConfig? {
        val field = CompositeStorageProvider::class.java.getDeclaredField("filebaseConfig")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as FilebaseConfig?
    }

    @Test
    fun s3AutoUpload_returnsCompositeWithDeferTrue() {
        val config = s3Config()
        val result = buildFilebaseAutoComposite(context, primary, config)

        assertTrue(result is CompositeStorageProvider)
        val composite = result as CompositeStorageProvider
        assertTrue(composite.deferProofSetUploadForTest())
        assertNotNull(composite.filebaseConfigForTest())
        assertEquals(config, composite.filebaseConfigForTest())
    }

    @Test
    fun ipfsAutoUpload_returnsCompositeWithDeferTrue() {
        val config = ipfsConfig()
        val result = buildFilebaseAutoComposite(context, primary, config)

        assertTrue(result is CompositeStorageProvider)
        val composite = result as CompositeStorageProvider
        assertTrue(composite.deferProofSetUploadForTest())
        assertNotNull(composite.filebaseConfigForTest())
        assertEquals(config, composite.filebaseConfigForTest())
    }

    @Test
    fun autoUploadFalse_returnsNull() {
        assertNull(buildFilebaseAutoComposite(context, primary, s3Config(autoUpload = false)))
        assertNull(buildFilebaseAutoComposite(context, primary, ipfsConfig(autoUpload = false)))
    }

    @Test
    fun notConfigured_returnsNull() {
        val config = FilebaseConfig("", "", "", enabled = false, autoUpload = true)
        assertNull(buildFilebaseAutoComposite(context, primary, config))
    }

    @Test
    fun noneMode_whenNotConfigured_returnsNull() {
        // resolveUploadMode() is NONE only when neither S3 nor IPFS credentials exist,
        // which also makes isConfigured() false — factory returns null, not primary.
        val config = FilebaseConfig("", "", "", enabled = true, autoUpload = true)
        assertNull(buildFilebaseAutoComposite(context, primary, config))
    }
}
