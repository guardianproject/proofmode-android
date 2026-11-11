package org.witness.proofmode.org.witness.proofmode.octet

import android.app.Activity
import com.octetproof.sdk.api.OctetRegion
import com.octetproof.sdk.api.OctetSdk
import com.octetproof.sdk.api.OctetVerdict
import kotlinx.coroutines.runBlocking
import org.witness.proofmode.notarization.NotarizationListener
import org.witness.proofmode.notarization.NotarizationProvider
import timber.log.Timber
import java.io.InputStream
import java.time.Instant

class OctetNotarizationProvider (private val sdk: OctetSdk?) : NotarizationProvider {

    override fun notarize(
        hash: String?,
        mimeType: String?,
        `is`: InputStream?,
        listener: NotarizationListener?
    ) {

        runBlocking {
            val verdict = sdk!!.loc.isWithin(
                region = OctetRegion.country("US"),
                atTime = Instant.now()
            )

            when (verdict.result) {
                OctetVerdict.Result.YES -> {
                    Timber.d("YES, proof attached: ${verdict.proof != null}")
                    listener?.notarizationSuccessful(hash, verdict.proof?.proofBytes)
                }

                OctetVerdict.Result.NO -> {
                    Timber.d("NO, provable negative")
                    listener?.notarizationFailed(-1, "negative")
                }

                OctetVerdict.Result.INDETERMINATE -> {
                    Timber.d("INDETERMINATE, reason: ${verdict.reason}")
                    listener?.notarizationFailed(-2, "indeterminate")
                }
            }
        }
    }

    override fun getProof(hash: String?): String? {
        TODO("Not yet implemented")
    }

    override fun getNotarizationFileExtension(): String? {
        return ".oct"
    }
}