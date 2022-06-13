package org.witness.proofmode.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.witness.proofmode.crypto.PgpUtils
import org.witness.proofmode.models.OpenPGPUploadBody
import org.witness.proofmode.web.OpenPGPRepository
import timber.log.Timber

class MainViewModel(val app: Application):AndroidViewModel(app) {
    private val openPGPRepo = OpenPGPRepository()
    private val pgpUtils = PgpUtils.getInstance(app.applicationContext)
    private val job = Job()
    private val coroutineScope = CoroutineScope(Dispatchers.Main + job)

    private val _error:MutableLiveData<String?> = MutableLiveData()
    val error:LiveData<String?>
    get() = _error

    private val _successMessage:MutableLiveData<String> = MutableLiveData("")
    val successMessage:LiveData<String>
    get() = _successMessage


    fun publishPublicKey() {
       val publicKey = pgpUtils.retrievePublicKeyToBePublished()
        coroutineScope.launch {

            try {
                val publishResponse = openPGPRepo.publishPublicKey(OpenPGPUploadBody(publicKey))
                if (publishResponse.isSuccess) {
                    _successMessage.value = "Publishing public key was successful"
                    Timber.d("Publishing key success")
                } else {
                    _error.value = "There was an error publishing your public key. Please try again"
                    Timber.e("publishPublickKey:error ")
                }
            } catch (ex:Exception) {
                _error.value = ex.message
                Timber.e("Error publishing key with exception ${ex.message}")
            }
        }

    }
}