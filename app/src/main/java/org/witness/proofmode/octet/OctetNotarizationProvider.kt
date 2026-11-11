package org.witness.proofmode.org.witness.proofmode.octet

import android.content.Context
import androidx.preference.PreferenceManager
import com.octetproof.sdk.api.LatLon
import com.octetproof.sdk.api.OctetRegion
import com.octetproof.sdk.api.OctetSdk
import com.octetproof.sdk.api.OctetVerdict
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.witness.proofmode.ProofMode
import org.witness.proofmode.notarization.NotarizationListener
import org.witness.proofmode.notarization.NotarizationProvider
import org.witness.proofmode.service.MediaWatcher
import org.witness.proofmode.service.ProofModeV1Constants
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
        // We check the device's location, not the media bytes.
        try {
            `is`?.close()
        } catch (ignored: Exception) {
        }

        if (!isEnabled()) {
            Timber.d("Octet notarization disabled by user setting; skipping %s", hash)
            return
        }

        val claim = hash?.let { readLocationClaim(it) }
        if (claim == null) {
            // No location in the proof means there is no claim for Octet to verify.
            Timber.d("No location in proof for %s; skipping Octet", hash)
            listener?.notarizationFailed(-3, "no location in proof")
            return
        }

        val region = try {
            OctetRegion.disc(LatLon(claim.lat, claim.lon), RADIUS_METERS)
        } catch (e: IllegalArgumentException) {
            // The SDK validates regions eagerly and throws on bad input.
            Timber.w(e, "Octet rejected region for %s", hash)
            listener?.notarizationFailed(-4, "invalid region")
            return
        }

        runBlocking {
            val verdict = sdk!!.loc.isWithin(
                region = region,
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

    /** Reads the location recorded in `{hash}.proof.json`, or null if it has none. */
    private fun readLocationClaim(hash: String): LocationClaim? {
        val storage = MediaWatcher.getInstance(appContext)?.storageProvider ?: return null
        return try {
            val json = storage.getInputStream(hash, hash + ProofMode.PROOF_FILE_JSON_TAG)
                ?.use { it.bufferedReader().readText() }
                ?: return null
            val proof = JSONObject(json)
            parseLocationClaim { key -> proof.optString(key) }
        } catch (e: Exception) {
            Timber.w(e, "Could not read proof json for %s", hash)
            null
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

    internal data class LocationClaim(val lat: Double, val lon: Double)

    internal companion object {
        /** Radius of the circle around the recorded location that Octet is asked to confirm. */
        const val RADIUS_METERS = 5_000.0

        /** [field] looks up a proof value by key (e.g. `Location.Latitude`), null/"" if absent. */
        fun parseLocationClaim(field: (String) -> String?): LocationClaim? {
            val lat = field(ProofModeV1Constants.LOCATION_LATITUDE)?.toDoubleOrNull()
            val lon = field(ProofModeV1Constants.LOCATION_LONGITUDE)?.toDoubleOrNull()
            if (lat == null || lon == null) return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return LocationClaim(lat, lon)
        }
    }
}
