package org.witness.proofmode.plugins.ipfscid

import android.net.Uri
import org.witness.proofmode.storage.StorageListener
import org.witness.proofmode.storage.StorageProvider
import java.io.File
import java.io.InputStream

open class FakeStorageProvider(
    private val proofSetDir: File? = null,
) : StorageProvider {
    val written = mutableListOf<Triple<String, String, ByteArray>>()

    override fun getProofSet(hash: String): java.util.ArrayList<Uri> {
        val dir = proofSetDir ?: return java.util.ArrayList()
        return java.util.ArrayList(dir.listFiles()?.map { Uri.fromFile(it) } ?: emptyList())
    }

    override fun saveBytes(hash: String, identifier: String, data: ByteArray?, listener: StorageListener?) {
        if (data != null) written.add(Triple(hash, identifier, data))
    }

    override fun saveStream(hash: String, identifier: String, stream: InputStream, listener: StorageListener?) {}
    override fun saveText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
    override fun replaceText(hash: String, identifier: String, data: String, listener: StorageListener?) {}
    override fun getInputStream(hash: String, identifier: String): InputStream? = null
    override fun proofExists(hash: String): Boolean = false
    override fun proofIdentifierExists(hash: String, identifier: String): Boolean = false
    override fun getProofItem(uri: Uri?): InputStream? = null
}
