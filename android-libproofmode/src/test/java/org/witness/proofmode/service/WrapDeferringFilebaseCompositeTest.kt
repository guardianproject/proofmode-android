package org.witness.proofmode.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
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
class WrapDeferringFilebaseCompositeTest {

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

    private fun CompositeStorageProvider.secondaryProviderForTest(): StorageProvider {
        val field = CompositeStorageProvider::class.java.getDeclaredField("secondaryProvider")
        field.isAccessible = true
        return field.get(this) as StorageProvider
    }

    private fun CompositeStorageProvider.primaryProviderForTest(): StorageProvider {
        val field = CompositeStorageProvider::class.java.getDeclaredField("primaryProvider")
        field.isAccessible = true
        return field.get(this) as StorageProvider
    }

    @Test
    fun s3AutoUpload_returnsCompositeWithDeferTrue() {
        val config = s3Config()
        val result = wrapDeferringFilebaseComposite(context, primary, config)

        assertTrue(result is CompositeStorageProvider)
        val composite = result as CompositeStorageProvider
        assertTrue(composite.deferProofSetUploadForTest())
        assertNotNull(composite.filebaseConfigForTest())
        assertEquals(config, composite.filebaseConfigForTest())
    }

    @Test
    fun autoUploadFalse_configured_returnsDeferringComposite() {
        val s3 = wrapDeferringFilebaseComposite(context, primary, s3Config(autoUpload = false))
        assertTrue(s3 is CompositeStorageProvider)
        assertTrue((s3 as CompositeStorageProvider).deferProofSetUploadForTest())
        assertEquals(s3Config(autoUpload = false), s3.filebaseConfigForTest())

        val ipfsPrimary = AccumulatingStorageProvider()
        val ipfs = wrapDeferringFilebaseComposite(
            context, ipfsPrimary, ipfsConfig(autoUpload = false),
        )
        assertTrue(ipfs is CompositeStorageProvider)
        assertTrue((ipfs as CompositeStorageProvider).deferProofSetUploadForTest())
        assertEquals(ipfsConfig(autoUpload = false), ipfs.filebaseConfigForTest())
    }

    @Test
    fun nullRefresh_twice_doesNotIdentityKeepSecondary() {
        val first = wrapDeferringFilebaseComposite(
            context, AccumulatingStorageProvider(), ipfsConfig(autoUpload = false),
        ) as CompositeStorageProvider
        val second = wrapDeferringFilebaseComposite(
            context, AccumulatingStorageProvider(), ipfsConfig(autoUpload = false),
        ) as CompositeStorageProvider
        assertNotSame(first.secondaryProviderForTest(), second.secondaryProviderForTest())
    }

    @Test
    fun notConfigured_returnsNull() {
        val config = FilebaseConfig("", "", "", enabled = false, autoUpload = true)
        assertNull(wrapDeferringFilebaseComposite(context, primary, config))
    }
}
