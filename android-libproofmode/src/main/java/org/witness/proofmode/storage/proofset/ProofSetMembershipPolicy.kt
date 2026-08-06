package org.witness.proofmode.storage.proofset

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.preference.PreferenceManager
import org.witness.proofmode.ProofMode

/**
 * First-pass Filebase directory membership for proof sets.
 *
 * **Maintainer entry point:** add or change artifacts in [RULES] only. Public helpers
 * ([isManifestMember], [requiredCoreBasenames], [isFirstPassComplete], etc.) are derived
 * from that registry so core / pref-gated / exclusion lists stay consistent.
 */
object ProofSetMembershipPolicy {

    /**
     * Declarative membership rules. Add new artifact kinds here — do not fork logic across
     * [isCoreArtifact] / [isManifestMember] / [isExcludedFromManifest] by hand.
     */
    sealed class ArtifactRule {
        /** Always required for first-pass completeness when expected on disk. */
        data class RequiredCore(val suffix: String) : ArtifactRule()

        /**
         * Included in the upload set only when the file is on disk and both global + provider
         * prefs are enabled. Never blocks first-pass.
         */
        data class PrefGated(
            val suffix: String,
            val globalPrefKey: String,
            val globalDefault: Boolean,
            val providerPrefKey: String,
            val providerDefault: Boolean,
        ) : ArtifactRule()

        /** Never a manifest member (any basename matching [matches]). */
        data class AlwaysExclude(val matches: (String) -> Boolean) : ArtifactRule()
    }


    // RequiredCore: Always required for first-pass completeness when expected on disk.
    // PrefGated: Included in the upload set only when the file is on disk and both global + provider prefs are enabled. Never blocks first-pass.
    //            GlobalPrefKey: The key for the global preference that controls whether this artifact type is included.
    //            GlobalDefault: The default value for the global preference.
    //            ProviderPrefKey: The key for the provider-specific preference that controls whether this artifact type is included.
    //            ProviderDefault: The default value for the provider-specific preference.
    // AlwaysExclude: Never a manifest member (any basename matching [matches]).
    private val RULES: List<ArtifactRule> = listOf(
        ArtifactRule.RequiredCore(ProofMode.PROOF_FILE_TAG),
        ArtifactRule.RequiredCore(ProofMode.PROOF_FILE_JSON_TAG),
        ArtifactRule.RequiredCore(ProofMode.PROOF_FILE_TAG + ProofMode.OPENPGP_FILE_TAG),
        ArtifactRule.RequiredCore(ProofMode.PROOF_FILE_JSON_TAG + ProofMode.OPENPGP_FILE_TAG),
        ArtifactRule.RequiredCore(ProofMode.OPENPGP_FILE_TAG),
        ArtifactRule.PrefGated(
            suffix = ".ots",
            globalPrefKey = ProofMode.PREF_OPTION_NOTARY,
            globalDefault = ProofMode.PREF_OPTION_NOTARY_DEFAULT,
            providerPrefKey = ProofMode.PREF_OPTION_NOTARY_OTS,
            providerDefault = ProofMode.PREF_OPTION_NOTARY_OTS_DEFAULT,
        ),
        ArtifactRule.PrefGated(
            suffix = ".nostr",
            globalPrefKey = ProofMode.PREF_OPTION_NOTARY,
            globalDefault = ProofMode.PREF_OPTION_NOTARY_DEFAULT,
            providerPrefKey = ProofMode.PREF_OPTION_NOTARY_NOSTR,
            providerDefault = ProofMode.PREF_OPTION_NOTARY_NOSTR_DEFAULT,
        ),
        ArtifactRule.AlwaysExclude { it.endsWith(".uri") },
        ArtifactRule.AlwaysExclude { it.endsWith(".ipfs-cids.json") },
        ArtifactRule.AlwaysExclude { it.contains(".ipfs-cids.late-") && it.endsWith(".json") },
        ArtifactRule.AlwaysExclude { it.endsWith(".lp.offchain.json") },
        ArtifactRule.AlwaysExclude { it.endsWith(".lp.onchain.json") },
        ArtifactRule.AlwaysExclude { it.endsWith(".lp.onchain.pending.json") },
        ArtifactRule.AlwaysExclude { it.endsWith(".lp.json") },
    )

    private val requiredCoreRules: List<ArtifactRule.RequiredCore> =
        RULES.filterIsInstance<ArtifactRule.RequiredCore>()

    private val prefGatedRules: List<ArtifactRule.PrefGated> =
        RULES.filterIsInstance<ArtifactRule.PrefGated>()

    private val excludeRules: List<ArtifactRule.AlwaysExclude> =
        RULES.filterIsInstance<ArtifactRule.AlwaysExclude>()

    fun isExcludedFromManifest(artifactBasename: String): Boolean =
        excludeRules.any { it.matches(artifactBasename) }

    fun isCoreArtifact(proofSetHash: String, basename: String): Boolean =
        requiredCoreRules.any { basename == proofSetHash + it.suffix }

    fun isManifestMember(context: Context, proofSetHash: String, basename: String): Boolean {
        if (isExcludedFromManifest(basename)) return false
        if (isCoreArtifact(proofSetHash, basename)) return true
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefGatedRules.any { rule ->
            basename == proofSetHash + rule.suffix && rule.isEnabled(prefs)
        }
    }

    /** First-pass: all [RequiredCore] basenames present; media required only under [MediaInclusion.INCLUDE_MEDIA]. */
    @Suppress("UNUSED_PARAMETER")
    fun isFirstPassComplete(
        context: Context,
        proofSetHash: String,
        onDiskIdentifiers: Collection<String>,
        mediaInclusion: MediaInclusion,
        mediaAvailable: Boolean,
    ): Boolean {
        if (mediaInclusion == MediaInclusion.INCLUDE_MEDIA && !mediaAvailable) return false
        return requiredCoreBasenames(proofSetHash).all { it in onDiskIdentifiers }
    }

    fun requiredCoreBasenames(hash: String): Set<String> =
        requiredCoreRules.map { hash + it.suffix }.toSet()

    fun manifestMemberBasenames(
        context: Context,
        hash: String,
        onDisk: Collection<String>,
    ): List<String> = onDisk.filter { isManifestMember(context, hash, it) }.sorted()

    internal fun fromProofSetUri(uri: Uri): String? {
        val path = uri.path ?: return uri.lastPathSegment
        return java.io.File(path).name
    }

    fun extensionFromMimeType(mimeType: String?): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "video/mp4" -> "mp4"
        else -> "bin"
    }

    fun manifestLinkNameForMedia(proofSetHash: String, mimeType: String?): String =
        "$proofSetHash.${extensionFromMimeType(mimeType)}"

    private fun ArtifactRule.PrefGated.isEnabled(prefs: SharedPreferences): Boolean {
        val global = prefs.getBoolean(globalPrefKey, globalDefault)
        if (!global) return false
        return prefs.getBoolean(providerPrefKey, providerDefault)
    }
}
