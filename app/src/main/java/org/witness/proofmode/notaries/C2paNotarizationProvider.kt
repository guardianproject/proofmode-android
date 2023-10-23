package org.witness.proofmode.org.witness.proofmode.notaries

import android.content.Context
import org.bouncycastle.crypto.params.Blake3Parameters.context
import org.proofmode.c2pa.C2paJNI
import org.witness.proofmode.notarization.NotarizationListener
import org.witness.proofmode.notarization.NotarizationProvider
import java.io.File
import java.io.InputStream


class C2paNotarizationProvider : NotarizationProvider {


    private var mContext: Context? = null

    fun GoogleSafetyNetNotarizationProvider(context: Context?) {
        mContext = context
    }
    override fun notarize(hash: String?, `inputStream`: InputStream?, listener: NotarizationListener?) {
        //write is to temp file

        val outputDir: File? = mContext?.getCacheDir() // context being the Activity pointer
        val fileImage = File.createTempFile(hash, ".jpg", outputDir)
        inputStream?.toFile(fileImage)

        val fileImageC2pa = File.createTempFile(hash, ".c2pa.jpg", outputDir)

        C2paJNI.addAssert(fileImage.absolutePath,hash,fileImageC2pa.absolutePath)

        listener?.notarizationSuccessful(hash, fileImageC2pa)
    }

    fun InputStream.toFile(path: File) {
        path.outputStream().use { this.copyTo(it) }
    }

    override fun getProof(hash: String?): String {
        TODO("Not yet implemented")
    }

    override fun getNotarizationFileExtension(): String {
        return ".c2pa"
    }
}