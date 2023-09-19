package org.witness.proofmode.org.witness.proofmode.notaries

import android.content.ContentResolver.MimeTypeInfo
import android.content.Context
import android.webkit.MimeTypeMap
import androidx.preference.PreferenceManager
import org.proofmode.c2pa.C2paJNI
import org.witness.proofmode.ProofModeConstants
import org.witness.proofmode.crypto.pgp.PgpUtils
import org.witness.proofmode.notarization.NotarizationListener
import org.witness.proofmode.notarization.NotarizationProvider
import java.io.File
import java.io.InputStream
import java.util.Date


public class C2paNotarizationProvider (_mContext: Context) : NotarizationProvider {

    private val mContext = _mContext

    companion object {
        const val C2PA_FILE_EXTENSION = ".c2pa"

        fun addContentCredentials(mContext : Context, fileImageIn: File, fileImageOut: File) {

            var certPath = File(mContext.filesDir, "cr.cert")
            var certKey = File(mContext.filesDir, "cr.key")

            val prefs = PreferenceManager.getDefaultSharedPreferences(mContext)
            val pgpUtils = PgpUtils.getInstance(
                mContext, prefs.getString(
                    ProofModeConstants.PREFS_KEY_PASSPHRASE,
                    ProofModeConstants.PREFS_KEY_PASSPHRASE_DEFAULT
                )
            )

            var pgpKeyShort = pgpUtils.publicKeyFingerprint;
            pgpKeyShort = pgpKeyShort.subSequence(pgpKeyShort.length - 8, pgpKeyShort.length - 1).toString()

            var identity =
                "{$pgpKeyShort}@https://keys.openpgp.org/search?q={$pgpKeyShort}"; //string needs name@uri/identity format

            if (!certPath.exists() || !certKey.exists())
                C2paJNI.generateCredentials(
                    certPath.absolutePath,
                    certKey.absolutePath,
                    pgpUtils.publicKeyFingerprint
                )

            C2paJNI.addAssert(
                certPath.absolutePath,
                certKey.absolutePath,
                fileImageIn.absolutePath,
                identity,
                pgpUtils.publicKeyFingerprint,
                fileImageOut.absolutePath
            )

        }
    }

    override fun notarize(hash: String?, mimeType: String?, `inputStream`: InputStream?, listener: NotarizationListener?) {

        val outputDir: File? = mContext.cacheDir // context being the Activity pointer

        var defaultExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (defaultExt == null)
            defaultExt = ".jpg"

        val fileImage = File.createTempFile(hash, ".$defaultExt", outputDir)
        inputStream?.toFile(fileImage)

        val fileImageC2pa = File(outputDir, "$hash$C2PA_FILE_EXTENSION.$defaultExt")

        var certPath = File(mContext.filesDir,"cr.cert")
        var certKey = File(mContext.filesDir, "cr.key")

        val prefs = PreferenceManager.getDefaultSharedPreferences(mContext)
        val pgpUtils = PgpUtils.getInstance(mContext, prefs.getString(
            ProofModeConstants.PREFS_KEY_PASSPHRASE,
            ProofModeConstants.PREFS_KEY_PASSPHRASE_DEFAULT
        ))

        var pgpKeyShort = pgpUtils.publicKeyFingerprint;
        pgpKeyShort = pgpKeyShort.subSequence(pgpKeyShort.length-8,pgpKeyShort.length-1).toString()

        var identity = "{$pgpKeyShort}@https://keys.openpgp.org/search?q={$pgpKeyShort}"; //string needs name@uri/identity format

        if (!certPath.exists() || !certKey.exists())
            C2paJNI.generateCredentials(certPath.absolutePath, certKey.absolutePath, hash)

        C2paJNI.addAssert(certPath.absolutePath, certKey.absolutePath, fileImage.absolutePath, identity, hash, fileImageC2pa.absolutePath)

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