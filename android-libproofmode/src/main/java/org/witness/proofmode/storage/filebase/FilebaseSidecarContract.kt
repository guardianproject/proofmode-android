package org.witness.proofmode.storage.filebase

import org.witness.proofmode.storage.StorageProvider

/**
 * Sidecar naming and read helpers for Filebase IPFS directory / image URI artifacts.
 *
 * Naming and presence checks live here — not on [org.witness.proofmode.storage.proofset.ProofSetUploader].
 */
object FilebaseSidecarContract {
    /** IPFS directory-root gateway URL only — never written by S3. */
    const val FILEBASE_IPFS_URI_SUFFIX = ".filebase.ipfs.uri"
    const val FILEBASE_IMAGE_URI_SUFFIX = ".filebase.image.uri"

    private fun readSidecarText(primary: StorageProvider, hash: String, suffix: String): String? =
        primary.getInputStream(hash, hash + suffix)
            ?.bufferedReader()?.use { it.readText() }?.trim()

    /** True when an IPFS directory upload has authored `{hash}.filebase.ipfs.uri`. */
    fun hasIpfsDirectoryUri(primary: StorageProvider, hash: String): Boolean {
        val text = readSidecarText(primary, hash, FILEBASE_IPFS_URI_SUFFIX)
        return !text.isNullOrEmpty()
    }

    /** True when `{hash}.filebase.ipfs.uri` **or** `{hash}.filebase.image.uri` is present and non-empty. */
    fun hasSuccessSidecar(primary: StorageProvider, hash: String): Boolean {
        val ipfs = readSidecarText(primary, hash, FILEBASE_IPFS_URI_SUFFIX)
        if (!ipfs.isNullOrEmpty()) return true
        val image = readSidecarText(primary, hash, FILEBASE_IMAGE_URI_SUFFIX)
        return !image.isNullOrEmpty()
    }

    fun readPriorDirectoryRootCid(primary: StorageProvider, hash: String): String? {
        val uri = primary.getInputStream(hash, hash + FILEBASE_IPFS_URI_SUFFIX)
            ?.bufferedReader()?.use { it.readText() }?.trim() ?: return null
        return FilebaseGatewayUris.parseGatewayRootCid(uri)
    }
}
