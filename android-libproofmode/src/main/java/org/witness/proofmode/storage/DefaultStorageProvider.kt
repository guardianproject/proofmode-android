package org.witness.proofmode.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import org.witness.proofmode.ProofMode
import org.witness.proofmode.service.MediaWatcher
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.ArrayList

public class DefaultStorageProvider (context : Context) : StorageProvider {

    private val PROOF_BASE_FOLDER = "proofmode/"
    private var mContext = context

    override fun saveStream(
        hash: String?,
        identifier: String?,
        stream: InputStream?,
        listener: StorageListener?
    ) {
        val file = File(
            getHashStorageDir(
                hash!!
            ), identifier!!
        )
        if (stream != null) {
            copyStreamToFile(stream, file)
        }
    }

    override fun saveText(
        hash: String?,
        identifier: String?,
        data: String?,
        listener: StorageListener?
    ) {

        val file = File(
            getHashStorageDir(
                hash!!
            ), identifier!!
        )
        if (data != null) {
            writeTextToFile(file, data)
        }

    }

    override fun getInputStream(hash: String?, identifier: String?): InputStream? {
        val file = File(
            getHashStorageDir(
                hash!!
            ), identifier!!
        )
        if (file.exists())
            return FileInputStream(file)
        else
            return null
    }

    override fun getOutputStream(hash: String?, identifier: String?): OutputStream {
        val file = File(
            getHashStorageDir(
                hash!!
            ), identifier!!
        )
        return FileOutputStream(file)
    }

    override fun saveBytes(
        hash: String?,
        identifier: String?,
        data: ByteArray?,
        listener: StorageListener?
    ) {

        identifier?.let {
            var file = File(
                getHashStorageDir(
                                        hash!!
                ), it
            )

            if (data != null) {
                writeBytesToFile(
                    file, data
                )
            }
        }




    }

    override fun proofExists(hash: String?) : Boolean {
               return proofIdentifierExists(hash, hash+ProofMode.PROOF_FILE_TAG)
    }

    override fun proofIdentifierExists(hash: String?, identifier: String?): Boolean {

        val dirProof = hash?.let { getHashStorageDir(it) }
        if (dirProof?.exists() == true)
        {
            return (identifier?.let {
                val dirProofHash = File(dirProof, it)
                dirProofHash.listFiles()?.forEach { Timber.d(it.absolutePath)}
                dirProofHash.exists()
            } == true)


        }

        return false
    }

    override fun getProofSet(hash: String?): ArrayList<Uri> {

        var listProofSet = ArrayList<Uri>()

        val dirProof = hash?.let { getHashStorageDir(it) }
        if (dirProof?.exists() == true)
        {
            val fileList = dirProof.listFiles()

            for (file in fileList)
            {
                listProofSet.add(Uri.fromFile(file))
            }
        }

        return listProofSet

    }

    override fun getProofItem(uri: Uri?): InputStream? {
        return if (uri?.scheme.equals("file")) {
            val fileProofItem = uri?.toFile()
            FileInputStream(fileProofItem)
        } else
            null
    }

    private fun writeTextToFile(fileOut: File, text: String) {
        try {
            val ps = PrintStream(FileOutputStream(fileOut, true))
            ps.println(text)
            ps.flush()
            ps.close()
        } catch (ioe: IOException) {
            ioe.printStackTrace()
        }
    }

    fun getHashStorageDir(hash: String): File? {

        // Get the directory for the user's public pictures directory.
        val proofFileSystem = ProofMode.getProofFileSystem()
        val fileParentDir: File
        fileParentDir = if (proofFileSystem != null) {
            // The app implemented chooses to store proof elsewhere
            File(proofFileSystem, PROOF_BASE_FOLDER)
        } else {
            // The default app files directory is used
            File(mContext.filesDir, PROOF_BASE_FOLDER)
        }
        if (!fileParentDir.exists()) {
            fileParentDir.mkdir()
        }
        /**
         * if (android.os.Build.VERSION.SDK_INT >= 19) {
         * fileParentDir = new File(Environment.getExternalStoragePublicDirectory(
         * Environment.DIRECTORY_DOCUMENTS), PROOF_BASE_FOLDER);
         *
         * }
         * else
         * {
         * fileParentDir = new File(Environment.getExternalStoragePublicDirectory(
         * Environment.DIRECTORY_DOWNLOADS), PROOF_BASE_FOLDER);
         * }
         *
         * if (!fileParentDir.exists()) {
         * if (!fileParentDir.mkdir())
         * {
         * fileParentDir = new File(Environment.getExternalStorageDirectory(), PROOF_BASE_FOLDER);
         * if (!fileParentDir.exists())
         * if (!fileParentDir.mkdir())
         * return null;
         * }
         * } */
        val fileHashDir = File(fileParentDir, "$hash/")
        if (!fileHashDir.exists()) if (!fileHashDir.mkdir()) return null
        return fileHashDir
    }


    @Synchronized
    private fun writeBytesToFile(fileOut: File, data: ByteArray) {
        FileOutputStream(fileOut).write(data)
    }

    private fun copyFileToFile(fileIn: File, fileOut: File) {
        val inStream = FileInputStream(fileIn)
        val outStream = FileOutputStream(fileOut)
        val inChannel = inStream.channel
        val outChannel = outStream.channel
        inChannel.transferTo(0, inChannel.size(), outChannel)
        inStream.close()
        outStream.close()
    }

    private fun copyStreamToFile(inStream: InputStream, fileOut: File) {
        val outStream = FileOutputStream(fileOut)
        inStream.copyTo(outStream, DEFAULT_BUFFER_SIZE)
        inStream.close()
        outStream.close()
    }
}