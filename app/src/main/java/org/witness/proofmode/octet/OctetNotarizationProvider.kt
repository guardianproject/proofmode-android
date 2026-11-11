package org.witness.proofmode.org.witness.proofmode.octet

import android.content.Context
import androidx.preference.PreferenceManager
import com.octetproof.sdk.api.OctetRegion
import com.octetproof.sdk.api.OctetSdk
import com.octetproof.sdk.api.OctetVerdict
import kotlinx.coroutines.runBlocking
import org.witness.proofmode.ProofMode
import org.witness.proofmode.notarization.NotarizationListener
import org.witness.proofmode.notarization.NotarizationProvider
import timber.log.Timber
import java.io.InputStream
import java.time.Instant

class OctetNotarizationProvider(
    context: Context,
    private val sdk: OctetSdk?
) : NotarizationProvider {

    private val appContext = context.applicationContext

    override fun notarize(
        hash: String?,
        mimeType: String?,
        `is`: InputStream?,
        listener: NotarizationListener?
    ) {

        if (!isEnabled()) {
            Timber.d("Octet notarization disabled by user setting; skipping %s", hash)
            return
        }

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

    /** Whether the user has the Octet notarization provider enabled (in addition to the global toggle). */
    private fun isEnabled(): Boolean =
        PreferenceManager.getDefaultSharedPreferences(appContext).getBoolean(
            ProofMode.PREF_OPTION_NOTARY_OCTET,
            ProofMode.PREF_OPTION_NOTARY_OCTET_DEFAULT
        )

    override fun getProof(hash: String?): String? {
        TODO("Not yet implemented")
    }

    override fun getNotarizationFileExtension(): String? {
        return ProofMode.OCTET_FILE_TAG
    }
}