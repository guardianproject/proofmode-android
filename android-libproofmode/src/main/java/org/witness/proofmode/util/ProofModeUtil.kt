package org.witness.proofmode.util

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.witness.proofmode.ProofMode
import org.witness.proofmode.crypto.HashUtils
import org.witness.proofmode.service.MediaWatcher
import org.witness.proofmode.storage.StorageProvider
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.InputStreamReader
import java.io.StringReader

class ProofModeUtil {

    companion object {
        @Throws(FileNotFoundException::class)
        fun getProofHash(mediaUri: Uri, context: Context): String? {
            val hash = HashUtils.getSHA256FromFileContent(
                context.contentResolver.openInputStream(mediaUri)
            )
            return hash
        }

        fun getProofSummary (storageProvider: StorageProvider, proofHash: String, context: Context): String? {

            var sb = StringBuffer ()

            if (storageProvider.proofExists(proofHash))
            {

                val jsonData = BufferedReader(InputStreamReader(storageProvider.getInputStream(proofHash,proofHash+ProofMode.PROOF_FILE_JSON_TAG))).readText();
                val jsonObject = JSONObject(jsonData)
                val itKeys = jsonObject.keys()
                while (itKeys.hasNext())
                {
                    val key = itKeys.next()
                    val value = jsonObject.getString(key)
                    if (value.isNotEmpty()) {
                        sb.append(key)
                        sb.append(": ")
                        sb.append(value)
                        sb.append("\n")
                    }
                }
            }

            return sb.toString()
        }

        fun getProofHashMap (storageProvider: StorageProvider, proofHash: String, context: Context): HashMap<String,String>? {

            val hmap = HashMap<String,String>()
            val identifier = proofHash+ProofMode.PROOF_FILE_JSON_TAG

            if (storageProvider.proofIdentifierExists(proofHash,identifier))
            {
                val jsonData = BufferedReader(InputStreamReader(storageProvider.getInputStream(proofHash,identifier))).readText();
                val jsonObject = JSONObject(jsonData)
                val itKeys = jsonObject.keys()
                while (itKeys.hasNext())
                {
                    while (itKeys.hasNext())
                    {
                        val key = itKeys.next()
                        val value = jsonObject.getString(key)
                        if (value.isNotEmpty()) {
                            hmap[key] = value
                        }
                    }
                }

            }

            return hmap
        }
    }
}