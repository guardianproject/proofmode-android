package org.witness.proofmode.org.witness.proofmode.octet

import android.app.Activity
import android.content.Context
import org.witness.proofmode.notarization.NotarizationListener
import org.witness.proofmode.notarization.NotarizationProvider
import java.io.InputStream

class OctetNotarizationProvider : NotarizationProvider {


    constructor(activity: Activity) {

        lifecycleScope.launch {
            val config = OctetConfig(licenseKey = "")   // required by the type, ignored by start
            val sdk = Octet.start(context = applicationContext, config = config)
            // sdk is ready
        }

    }

    override fun notarize(
        hash: String?,
        mimeType: String?,
        `is`: InputStream?,
        listener: NotarizationListener?
    ) {
        TODO("Not yet implemented")
    }

    override fun getProof(hash: String?): String? {
        TODO("Not yet implemented")
    }

    override fun getNotarizationFileExtension(): String? {
        return ".oct"
    }
}