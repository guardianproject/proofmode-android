package org.witness.proofmode.storage.filebase

import android.content.SharedPreferences
import org.witness.proofmode.storage.proofset.MediaInclusion

/** Result of applying a settings draft via [FilebaseConfig.commitDraft]. */
sealed class CommitDraftResult {
    data object Unchanged : CommitDraftResult()
    data object Committed : CommitDraftResult()
    data object DiscardedInvalid : CommitDraftResult()
}

/**
 * Filebase credentials and auto-upload prefs.
 *
 * Prefer [fromPrefs] / [commitDraft] over constructing by hand. Capability helpers
 * ([hasS3Access], [hasIpfsAccess], [resolveUploadMode]) decide capture and Share paths.
 */
data class FilebaseConfig(
    val accessKey: String,
    val secretKey: String,
    val bucketName: String,
    val endpoint: String = "https://s3.filebase.com",
    val region: String = "us-east-1",
    val enabled: Boolean = false,
    val ipfsBearerToken: String = "",
    /** When true and [isConfigured], MediaWatcher wraps primary in a deferring Composite. */
    val autoUpload: Boolean = true,
    /** Auto path only: include injected media leaf vs sidecars-only in membership uploads. */
    val autoIncludeMedia: Boolean = true,
) {
    /** How an assembled proof set should reach Filebase (IPFS directory preferred over S3). */
    enum class UploadMode { IPFS_DIRECTORY, S3_MEMBERS, NONE }

    fun hasS3Access(): Boolean =
        accessKey.isNotBlank() && secretKey.isNotBlank() &&
            bucketName.isNotBlank() && endpoint.isNotBlank()

    /** @deprecated Prefer [hasS3Access]; kept as S3-field alias. */
    fun isValid(): Boolean = hasS3Access()

    /**
     * Settings / Share "Filebase is ready" gate: master [enabled] plus either S3 triple
     * or a non-blank IPFS bearer token (bearer-only and S3-only both qualify).
     */
    fun isConfigured(): Boolean = enabled && (hasS3Access() || hasIpfsAccess())

    /** IPFS directory RPC path: [enabled] and a non-blank bearer token. */
    fun hasIpfsAccess(): Boolean = enabled && ipfsBearerToken.isNotBlank()

    /**
     * Prefer IPFS directory when a bearer token is present; otherwise S3 members;
     * [UploadMode.NONE] when neither path has credentials (even if [enabled]).
     */
    fun resolveUploadMode(): UploadMode = when {
        hasIpfsAccess() -> UploadMode.IPFS_DIRECTORY
        hasS3Access() -> UploadMode.S3_MEMBERS
        else -> UploadMode.NONE
    }

    /** Maps [autoIncludeMedia] for Composite auto flush / membership stamps. */
    fun resolveMediaInclusionForAuto(): MediaInclusion =
        if (autoIncludeMedia) MediaInclusion.INCLUDE_MEDIA else MediaInclusion.SIDECARS_ONLY

    companion object {
        const val PREF_FILEBASE_ENABLED = "filebase_enabled"
        const val PREF_FILEBASE_ACCESS_KEY = "filebase_access_key"
        const val PREF_FILEBASE_SECRET_KEY = "filebase_secret_key"
        const val PREF_FILEBASE_BUCKET_NAME = "filebase_bucket_name"
        const val PREF_FILEBASE_ENDPOINT = "filebase_endpoint"
        const val PREF_FILEBASE_REGION = "filebase_region"
        const val PREF_FILEBASE_IPFS_BEARER_TOKEN = "filebase_ipfs_bearer_token"
        const val PREF_FILEBASE_AUTO_UPLOAD = "filebase_auto_upload"
        const val PREF_FILEBASE_AUTO_INCLUDE_MEDIA = "filebase_auto_include_media"
        const val PREF_FILEBASE_PREFS_SCHEMA_VERSION = "filebase_prefs_schema_version"
        const val FILEBASE_PREFS_SCHEMA_VERSION = 2

        /** Media-object-only Filebase free-tier size limit (never proofset/sidecar aggregate). */
        const val FILEBASE_MEDIA_MAX_BYTES: Long = 25L * 1024 * 1024

        /**
         * True when [lengthBytes] is a known positive media size within [FILEBASE_MEDIA_MAX_BYTES].
         * Unknown/non-positive lengths are outside the limit (callers must not invent oversize force
         * from unresolved media — Auto Sync keeps the existing "Media unavailable" non-flush).
         */
        @JvmStatic
        fun isWithinFilebaseMediaLimit(lengthBytes: Long): Boolean =
            lengthBytes > 0L && lengthBytes <= FILEBASE_MEDIA_MAX_BYTES

        /**
         * One-shot wipe of Filebase keys when stored schema is older than
         * [FILEBASE_PREFS_SCHEMA_VERSION]. Prevents half-migrated credential shapes from
         * enabling auto-upload with invalid state. Called from [fromPrefs].
         */
        fun ensurePrefsSchema(prefs: SharedPreferences) {
            val stored = prefs.getInt(PREF_FILEBASE_PREFS_SCHEMA_VERSION, 0)
            if (stored >= FILEBASE_PREFS_SCHEMA_VERSION) return
            prefs.edit()
                .remove(PREF_FILEBASE_ENABLED)
                .remove(PREF_FILEBASE_ACCESS_KEY)
                .remove(PREF_FILEBASE_SECRET_KEY)
                .remove(PREF_FILEBASE_BUCKET_NAME)
                .remove(PREF_FILEBASE_ENDPOINT)
                .remove(PREF_FILEBASE_REGION)
                .remove(PREF_FILEBASE_IPFS_BEARER_TOKEN)
                .remove(PREF_FILEBASE_AUTO_UPLOAD)
                .remove(PREF_FILEBASE_AUTO_INCLUDE_MEDIA)
                .putInt(PREF_FILEBASE_PREFS_SCHEMA_VERSION, FILEBASE_PREFS_SCHEMA_VERSION)
                .apply()
        }

        /**
         * Load config from SharedPreferences. Missing [PREF_FILEBASE_AUTO_UPLOAD] /
         * [PREF_FILEBASE_AUTO_INCLUDE_MEDIA] default to `true` so existing installs keep
         * capture-time upload behavior.
         */
        fun fromPrefs(prefs: SharedPreferences): FilebaseConfig {
            ensurePrefsSchema(prefs)
            return FilebaseConfig(
            accessKey = prefs.getString(PREF_FILEBASE_ACCESS_KEY, "") ?: "",
            secretKey = prefs.getString(PREF_FILEBASE_SECRET_KEY, "") ?: "",
            bucketName = prefs.getString(PREF_FILEBASE_BUCKET_NAME, "") ?: "",
            endpoint = prefs.getString(PREF_FILEBASE_ENDPOINT, "https://s3.filebase.com") ?: "https://s3.filebase.com",
            region = prefs.getString(PREF_FILEBASE_REGION, "us-east-1") ?: "us-east-1",
            enabled = prefs.getBoolean(PREF_FILEBASE_ENABLED, false),
            ipfsBearerToken = prefs.getString(PREF_FILEBASE_IPFS_BEARER_TOKEN, "") ?: "",
            autoUpload = prefs.getBoolean(PREF_FILEBASE_AUTO_UPLOAD, true),
            autoIncludeMedia = prefs.getBoolean(PREF_FILEBASE_AUTO_INCLUDE_MEDIA, true),
            )
        }

        /**
         * Persist a settings-screen draft relative to [baseline].
         *
         * - [CommitDraftResult.Unchanged] — draft equals baseline; prefs untouched.
         * - [CommitDraftResult.DiscardedInvalid] — partial credentials (e.g. access key
         *   without secret). Does not write the draft; if baseline also had no usable
         *   credentials, forces enabled/auto toggles off.
         * - [CommitDraftResult.Committed] — cleared credentials (disables Filebase) or
         *   a complete S3 and/or bearer set (sets [PREF_FILEBASE_ENABLED] true).
         */
        fun commitDraft(
            prefs: SharedPreferences,
            draft: FilebaseSettingsDraft,
            baseline: FilebaseConfig,
        ): CommitDraftResult {
            val baselineDraft = FilebaseSettingsDraft(
                baseline.accessKey,
                baseline.secretKey,
                baseline.bucketName,
                baseline.endpoint,
                baseline.region,
                baseline.ipfsBearerToken,
                baseline.autoUpload,
                baseline.autoIncludeMedia,
            )
            if (draft == baselineDraft) {
                return CommitDraftResult.Unchanged
            }

            if (draft.isIncompleteCredentials()) {
                if (!baseline.hasUsableCredentials()) {
                    prefs.edit()
                        .putBoolean(PREF_FILEBASE_ENABLED, false)
                        .putBoolean(PREF_FILEBASE_AUTO_UPLOAD, false)
                        .putBoolean(PREF_FILEBASE_AUTO_INCLUDE_MEDIA, false)
                        .apply()
                }
                return CommitDraftResult.DiscardedInvalid
            }

            val editor = prefs.edit()
            if (draft.isClearedCredentials()) {
                editor
                    .putString(PREF_FILEBASE_ACCESS_KEY, "")
                    .putString(PREF_FILEBASE_SECRET_KEY, "")
                    .putString(PREF_FILEBASE_BUCKET_NAME, "")
                    .putString(PREF_FILEBASE_IPFS_BEARER_TOKEN, "")
                    .putString(PREF_FILEBASE_ENDPOINT, draft.endpoint)
                    .putString(PREF_FILEBASE_REGION, draft.region)
                    .putBoolean(PREF_FILEBASE_ENABLED, false)
                    .putBoolean(PREF_FILEBASE_AUTO_UPLOAD, false)
                    .putBoolean(PREF_FILEBASE_AUTO_INCLUDE_MEDIA, false)
                    .apply()
                return CommitDraftResult.Committed
            }

            editor
                .putString(PREF_FILEBASE_ACCESS_KEY, draft.accessKey)
                .putString(PREF_FILEBASE_SECRET_KEY, draft.secretKey)
                .putString(PREF_FILEBASE_BUCKET_NAME, draft.bucketName)
                .putString(PREF_FILEBASE_ENDPOINT, draft.endpoint)
                .putString(PREF_FILEBASE_REGION, draft.region)
                .putString(PREF_FILEBASE_IPFS_BEARER_TOKEN, draft.ipfsBearerToken)
                .putBoolean(PREF_FILEBASE_AUTO_UPLOAD, draft.autoUpload)
                .putBoolean(PREF_FILEBASE_AUTO_INCLUDE_MEDIA, draft.autoIncludeMedia)
                .putBoolean(PREF_FILEBASE_ENABLED, true)
                .apply()
            return CommitDraftResult.Committed
        }

        /**
         * Settings long-press helper: flip [PREF_FILEBASE_AUTO_UPLOAD] only when
         * [isConfigured]. Returns false so the UI can open Filebase settings instead.
         */
        fun toggleAutoUploadIfConfigured(prefs: SharedPreferences): Boolean {
            val cfg = fromPrefs(prefs)
            if (!cfg.isConfigured()) return false
            prefs.edit().putBoolean(PREF_FILEBASE_AUTO_UPLOAD, !cfg.autoUpload).apply()
            return true
        }

        /** Settings Auto Sync cell checked state: configured and auto-upload on. */
        fun autoSyncIndicatorChecked(cfg: FilebaseConfig): Boolean =
            cfg.isConfigured() && cfg.autoUpload
    }
}

/**
 * Credential + auto toggles as edited on the Filebase settings screen.
 * Does not carry [FilebaseConfig.enabled] — commit sets enabled from credential completeness.
 */
data class FilebaseSettingsDraft(
    val accessKey: String,
    val secretKey: String,
    val bucketName: String,
    val endpoint: String,
    val region: String,
    val ipfsBearerToken: String,
    val autoUpload: Boolean,
    val autoIncludeMedia: Boolean,
) {
    /** Complete enough to enable Filebase: full S3 triple or non-blank bearer. */
    fun hasUsableCredentials(): Boolean =
        hasS3Triple() || ipfsBearerToken.isNotBlank()

    fun hasS3Triple(): Boolean =
        accessKey.isNotBlank() && secretKey.isNotBlank() &&
            bucketName.isNotBlank() && endpoint.isNotBlank()

    /** Any of access/secret/bucket/bearer non-blank (endpoint/region alone do not count). */
    fun hasAnyCredentialInput(): Boolean =
        accessKey.isNotBlank() || secretKey.isNotBlank() ||
            bucketName.isNotBlank() || ipfsBearerToken.isNotBlank()

    /** User cleared identity fields — commit disables Filebase. */
    fun isClearedCredentials(): Boolean = !hasAnyCredentialInput()

    /** Partial fill (some identity input but neither S3 triple nor bearer) — reject draft. */
    fun isIncompleteCredentials(): Boolean =
        hasAnyCredentialInput() && !hasUsableCredentials()
}

/** True when S3 and/or bearer credentials are present (ignores [FilebaseConfig.enabled]). */
fun FilebaseConfig.hasUsableCredentials(): Boolean =
    hasS3Access() || ipfsBearerToken.isNotBlank()
