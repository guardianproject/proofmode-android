package org.witness.proofmode.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class ShareProofViewModelModelFactory(private val app: Application) : ViewModelProvider.Factory {

    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ShareProofViewModel::class.java)) {
            return ShareProofViewModel(app) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}