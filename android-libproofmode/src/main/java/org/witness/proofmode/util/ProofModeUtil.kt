package org.witness.proofmode.util

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.witness.proofmode.ProofMode
import org.witness.proofmode.crypto.HashUtils
import org.witness.proofmode.service.MediaWatcher
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader

class ProofModeUtil {

    companion object {
        @Throws(FileNotFoundException::class)
        fun getProofHash(mediaUri: Uri, context: Context): String? {
            var mediaUri = mediaUri
            var sMediaUri = mediaUri.toString()

            val hash = HashUtils.getSHA256FromFileContent(
                context.contentResolver.openInputStream(mediaUri)
            )
            if (hash != null) {
                Timber.d("Proof check if exists for URI %s and hash %s", mediaUri, hash)
                val fileFolder = MediaWatcher.getHashStorageDir(context, hash)
                return if (fileFolder != null) {
                    val fileMediaProof = File(fileFolder, hash + ProofMode.PROOF_FILE_TAG)
                    //generate now?
                    if (fileMediaProof.exists()) hash else null
                } else null
            }
            return null
        }

        fun getProofSummary (proofHash: String, context: Context): String? {

            var sb = StringBuffer ()

            val fileFolder = MediaWatcher.getHashStorageDir(context, proofHash)
            if (fileFolder != null) {
                val fileMediaProof = File(fileFolder, proofHash + ProofMode.PROOF_FILE_JSON_TAG)
                //generate now?
                if (fileMediaProof.exists()) {

                    var jsonData = FileReader(fileMediaProof).readText()
                    val jsonObject = JSONObject(jsonData)

                    var itKeys = jsonObject.keys()
                    while (itKeys.hasNext())
                    {
                        var key = itKeys.next()
                        var value = jsonObject.getString(key)

                        if (value?.isNotEmpty() == true) {
                            sb.append(key)
                            sb.append(": ")
                            sb.append(value)
                            sb.append("\n")
                        }
                    }


                }

            }

            return sb.toString()
        }
    }
}