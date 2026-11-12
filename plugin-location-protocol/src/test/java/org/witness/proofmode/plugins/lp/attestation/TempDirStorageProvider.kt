package org.witness.proofmode.plugins.lp.attestation

import android.net.Uri
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.File
import java.io.InputStream
import java.util.ArrayList
import kotlin.io.path.createTempDirectory

/**
 * File-backed [StorageProvider] under [root], laid out `root/<hash>/<identifier>` like
 * DefaultStorageProvider, so [getProofSet] returns real `file://` URIs. Needs Robolectric for [Uri].
 */
internal class TempDirStorageProvider private constructor(private val root: File) : StorageProvider {

    companion object {
        fun inNewTempDir(): TempDirStorageProvider =
            TempDirStorageProvider(createTempDirectory("lpstorage").toFile())
    }

    fun deleteRoot() {
        root.deleteRecursively()
    }

    fun file(hash: String, identifier: String): File = File(File(root, hash), identifier)

    fun exists(hash: String, identifier: String): Boolean = file(hash, identifier).exists()

    fun read(hash: String, identifier: String): String? =
        file(hash, identifier).takeIf { it.exists() }?.readText()

    fun seed(hash: String, identifier: String, data: String) {
        file(hash, identifier).apply { parentFile?.mkdirs() }.writeText(data)
    }

    override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener) {
        seed(hash, identifier, data)
        listener.saveSuccessful(hash, Uri.fromFile(file(hash, identifier)).toString())
    }

    override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener) =
        saveText(hash, identifier, data, listener)

    override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener) =
        saveText(hash, identifier, stream.bufferedReader().readText(), listener)

    override fun saveBytes(hash: String, identifier: String, data: ByteArray, listener: StorageListener) =
        saveText(hash, identifier, String(data, Charsets.UTF_8), listener)

    override fun getInputStream(hash: String, identifier: String): InputStream? =
        file(hash, identifier).takeIf { it.exists() }?.inputStream()

    override fun proofExists(hash: String): Boolean = File(root, hash).exists()

    override fun proofIdentifierExists(hash: String, identifier: String): Boolean = exists(hash, identifier)

    override fun getProofSet(hash: String): ArrayList<Uri> =
        ArrayList(File(root, hash).listFiles().orEmpty().map { Uri.fromFile(it) })

    override fun getProofItem(uri: Uri): InputStream? = uri.path?.let { File(it).inputStream() }
}
