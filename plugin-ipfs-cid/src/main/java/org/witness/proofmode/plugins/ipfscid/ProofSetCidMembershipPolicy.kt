package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import androidx.preference.PreferenceManager
import org.witness.proofmode.ProofMode

/**
 * CID directory membership for proof sets. Denylist suffixes mirror
 * [org.witness.proofmode.plugins.lp.LocationProtocolArtifactStore] — duplicated
 * intentionally to avoid plugin-ipfs-cid → plugin-location-protocol dependency.
 */
object ProofSetCidMembershipPolicy {
    private const val LP_OFFCHAIN = ".lp.offchain.json"
    private const val LP_ONCHAIN = ".lp.onchain.json"
    private const val LP_ONCHAIN_PENDING = ".lp.onchain.pending.json"
    private const val LP_LEGACY = ".lp.json"
    private const val OTS_SUFFIX = ".ots"
    private const val NOSTR_SUFFIX = ".nostr"

    fun isExcludedFromManifest(artifactBasename: String): Boolean {
        if (artifactBasename.endsWith(IpfsCidSidecar.SIDECAR_SUFFIX)) return true
        if (IpfsCidSidecar.isLateSnapshotBasename(artifactBasename)) return true
        return artifactBasename.endsWith(LP_OFFCHAIN)
            || artifactBasename.endsWith(LP_ONCHAIN)
            || artifactBasename.endsWith(LP_ONCHAIN_PENDING)
            || artifactBasename.endsWith(LP_LEGACY)
    }

    fun isManifestMember(context: Context, proofSetHash: String, artifactBasename: String): Boolean {
        if (isExcludedFromManifest(artifactBasename)) return false
        if (isCoreArtifact(proofSetHash, artifactBasename)) return true
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        if (artifactBasename == proofSetHash + OTS_SUFFIX) {
            return prefs.getBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, ProofMode.PREF_OPTION_NOTARY_OTS_DEFAULT)
        }
        if (artifactBasename == proofSetHash + NOSTR_SUFFIX) {
            return prefs.getBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, ProofMode.PREF_OPTION_NOTARY_NOSTR_DEFAULT)
        }
        return false
    }

    fun triggersSidecarRefresh(context: Context, artifactBasename: String): Boolean {
        if (isExcludedFromManifest(artifactBasename)) return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        if (artifactBasename.endsWith(OTS_SUFFIX)) {
            return prefs.getBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, ProofMode.PREF_OPTION_NOTARY_OTS_DEFAULT)
        }
        if (artifactBasename.endsWith(NOSTR_SUFFIX)) {
            return prefs.getBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, ProofMode.PREF_OPTION_NOTARY_NOSTR_DEFAULT)
        }
        return false
    }

    fun manifestMemberBasenames(
        context: Context,
        proofSetHash: String,
        onDiskIdentifiers: Collection<String>,
    ): List<String> = onDiskIdentifiers
        .filter { isManifestMember(context, proofSetHash, it) }
        .sorted()

    internal fun isCoreArtifactForTest(proofSetHash: String, artifactBasename: String): Boolean =
        isCoreArtifact(proofSetHash, artifactBasename)

    private fun isCoreArtifact(proofSetHash: String, artifactBasename: String): Boolean {
        if (artifactBasename == proofSetHash + ProofMode.PROOF_FILE_TAG) return true
        if (artifactBasename == proofSetHash + ProofMode.PROOF_FILE_JSON_TAG) return true
        if (artifactBasename == proofSetHash + ProofMode.PROOF_FILE_TAG + ProofMode.OPENPGP_FILE_TAG) return true
        if (artifactBasename == proofSetHash + ProofMode.PROOF_FILE_JSON_TAG + ProofMode.OPENPGP_FILE_TAG) return true
        if (artifactBasename == proofSetHash + ProofMode.OPENPGP_FILE_TAG) return true
        return false
    }
}
