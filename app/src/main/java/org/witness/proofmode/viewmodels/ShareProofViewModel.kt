package org.witness.proofmode.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.witness.proofmode.ProofMode
import org.witness.proofmode.crypto.HashUtils
import timber.log.Timber
import java.io.FileNotFoundException

/**
 * This will be associated with [org.witness.proofmode.ShareProofActivity]
 * and the code from the activity moved to here
 */
class ShareProofViewModel(val app:Application):AndroidViewModel(app) {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private val _hashCache:MutableLiveData<HashMap<String,String>> = MutableLiveData()
    val hashCache:LiveData<HashMap<String,String>>
    get() = _hashCache

    fun generateProof(mediaUris:List<Uri>) {
        scope.launch {
            mediaUris.forEach{ uri ->
                try {

                    val proofHash = HashUtils.getSHA256FromFileContent(
                        app.applicationContext.contentResolver.openInputStream(uri)
                    )

                    _hashCache.value?.put(uri.toString(),proofHash)

                    val genProofHash =
                        ProofMode.generateProof(app.applicationContext, uri, proofHash)

                    if (genProofHash != null && genProofHash == proofHash) {
                        //all good
                    } else {
                        //error occurred
                    }


                } catch (fe:FileNotFoundException)
                {
                    Timber.d("FileNotFound: %s", uri)
                }
            }

        }

    }
}