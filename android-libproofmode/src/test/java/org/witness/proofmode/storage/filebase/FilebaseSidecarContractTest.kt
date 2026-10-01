package org.witness.proofmode.storage.filebase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.storage.proofset.RecordingStorageProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FilebaseSidecarContractTest {
    private val hash = "abc123deadbeef"

    @Test
    fun hasSuccessSidecar_trueWhenIpfsUriPresent() {
        val primary = RecordingStorageProvider(
            streams = mapOf(
                hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX to
                    "https://ipfs.filebase.io/ipfs/bafyRoot".toByteArray(),
            ),
        )
        assertTrue(FilebaseSidecarContract.hasSuccessSidecar(primary, hash))
        assertTrue(FilebaseSidecarContract.hasIpfsDirectoryUri(primary, hash))
    }

    @Test
    fun hasSuccessSidecar_trueWhenOnlyImageUriPresent() {
        val primary = RecordingStorageProvider(
            streams = mapOf(
                hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX to
                    "s3://bucket/media.jpg".toByteArray(),
            ),
        )
        assertTrue(FilebaseSidecarContract.hasSuccessSidecar(primary, hash))
        assertFalse(FilebaseSidecarContract.hasIpfsDirectoryUri(primary, hash))
    }

    @Test
    fun hasSuccessSidecar_falseWhenBothMissing() {
        val primary = RecordingStorageProvider()
        assertFalse(FilebaseSidecarContract.hasSuccessSidecar(primary, hash))
        assertFalse(FilebaseSidecarContract.hasIpfsDirectoryUri(primary, hash))
    }

    @Test
    fun hasSuccessSidecar_falseWhenBlankText() {
        val primary = RecordingStorageProvider(
            streams = mapOf(
                hash + FilebaseSidecarContract.FILEBASE_IPFS_URI_SUFFIX to "  \n".toByteArray(),
                hash + FilebaseSidecarContract.FILEBASE_IMAGE_URI_SUFFIX to "".toByteArray(),
            ),
        )
        assertFalse(FilebaseSidecarContract.hasSuccessSidecar(primary, hash))
        assertFalse(FilebaseSidecarContract.hasIpfsDirectoryUri(primary, hash))
    }
}
