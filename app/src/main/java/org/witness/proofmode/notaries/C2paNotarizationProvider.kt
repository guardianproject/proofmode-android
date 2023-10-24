package org.witness.proofmode.org.witness.proofmode.notaries

import android.content.ContentResolver.MimeTypeInfo
import android.content.Context
import android.webkit.MimeTypeMap
import org.proofmode.c2pa.C2paJNI
import org.witness.proofmode.notarization.NotarizationListener
import org.witness.proofmode.notarization.NotarizationProvider
import java.io.File
import java.io.InputStream


public class C2paNotarizationProvider (_mContext: Context) : NotarizationProvider {

    private val mContext = _mContext

    companion object {
        const val C2PA_FILE_EXTENSION = ".c2pa"
    }

    override fun notarize(hash: String?, mimeType: String?, `inputStream`: InputStream?, listener: NotarizationListener?) {

        val outputDir: File? = mContext.cacheDir // context being the Activity pointer

        var defaultExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (defaultExt == null)
            defaultExt = ".jpg"

        val fileImage = File.createTempFile(hash, ".$defaultExt", outputDir)
        inputStream?.toFile(fileImage)

        val fileImageC2pa = File(outputDir, "$hash$C2PA_FILE_EXTENSION.$defaultExt")

        C2paJNI.addAssert(fileImage.absolutePath,hash,fileImageC2pa.absolutePath)

        fileImage.delete()

        listener?.notarizationSuccessful(hash, fileImageC2pa)
    }

    fun InputStream.toFile(path: File) {
        path.outputStream().use { this.copyTo(it) }
    }

    override fun getProof(hash: String?): String {
        TODO("Not yet implemented")
    }

    override fun getNotarizationFileExtension(): String {
        return C2PA_FILE_EXTENSION
    }
}