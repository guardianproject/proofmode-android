package org.witness.proofmode.storage.filebase

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.witness.proofmode.storage.proofset.MediaInclusion
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FilebaseConfigTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun hasIpfsAccess_requiresEnabledAndBearerToken() {
        assertFalse(FilebaseConfig("a", "s", "b", enabled = false, ipfsBearerToken = "tok").hasIpfsAccess())
        assertFalse(FilebaseConfig("a", "s", "b", enabled = true, ipfsBearerToken = "").hasIpfsAccess())
        assertTrue(FilebaseConfig("a", "s", "b", enabled = true, ipfsBearerToken = "tok").hasIpfsAccess())
    }

    @Test
    fun isConfigured_isEnabledAndValid() {
        assertFalse(FilebaseConfig("", "s", "b", enabled = true).isConfigured())
        assertTrue(FilebaseConfig("a", "s", "b", enabled = true).isConfigured())
        assertTrue(
            FilebaseConfig("", "", "", enabled = true, ipfsBearerToken = "tok").isConfigured()
        )
    }

    @Test
    fun hasS3Access_matchesFormerIsValid() {
        assertTrue(FilebaseConfig("a", "s", "b").hasS3Access())
        assertFalse(FilebaseConfig("", "s", "b").hasS3Access())
        assertFalse(FilebaseConfig("a", "s", "b", endpoint = "").hasS3Access())
    }

    @Test
    fun isConfigured_bearerOnlyOrS3() {
        assertTrue(
            FilebaseConfig("", "", "", enabled = true, ipfsBearerToken = "tok").isConfigured()
        )
        assertTrue(FilebaseConfig("a", "s", "b", enabled = true).isConfigured())
        assertFalse(FilebaseConfig("", "", "", enabled = true).isConfigured())
        assertFalse(
            FilebaseConfig("a", "s", "b", enabled = false, ipfsBearerToken = "tok").isConfigured()
        )
    }

    @Test
    fun resolveUploadMode_prefersIpfsWhenBoth() {
        val both = FilebaseConfig("a", "s", "b", enabled = true, ipfsBearerToken = "tok")
        assertEquals(FilebaseConfig.UploadMode.IPFS_DIRECTORY, both.resolveUploadMode())
        val s3Only = FilebaseConfig("a", "s", "b", enabled = true)
        assertEquals(FilebaseConfig.UploadMode.S3_MEMBERS, s3Only.resolveUploadMode())
        val neither = FilebaseConfig("", "", "", enabled = true)
        assertEquals(FilebaseConfig.UploadMode.NONE, neither.resolveUploadMode())
    }

    @Test
    fun isValid_aliasesHasS3Access() {
        val c = FilebaseConfig("a", "s", "b")
        assertEquals(c.hasS3Access(), c.isValid())
    }

    @Test
    fun autoUpload_ctorDefaultsTrue() {
        assertTrue(FilebaseConfig("a", "s", "b").autoUpload)
        assertTrue(FilebaseConfig("a", "s", "b").autoIncludeMedia)
    }

    @Test
    fun fromPrefs_missingAutoUpload_defaultsTrue() {
        val prefs = emptyPrefs()
        // schema wipe may run; after wipe keys absent → true
        assertTrue(FilebaseConfig.fromPrefs(prefs).autoUpload)
    }

    @Test
    fun fromPrefs_missingIncludeMedia_defaultsTrue() {
        val prefs = emptyPrefs()
        assertTrue(FilebaseConfig.fromPrefs(prefs).autoIncludeMedia)
        assertEquals(
            MediaInclusion.INCLUDE_MEDIA,
            FilebaseConfig.fromPrefs(prefs).resolveMediaInclusionForAuto(),
        )
    }

    @Test
    fun fromPrefs_explicitFalse_preserved() {
        val prefs = prefsWith(
            FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD to false,
            FilebaseConfig.PREF_FILEBASE_AUTO_INCLUDE_MEDIA to false,
            schema = FilebaseConfig.FILEBASE_PREFS_SCHEMA_VERSION,
        )
        val cfg = FilebaseConfig.fromPrefs(prefs)
        assertFalse(cfg.autoUpload)
        assertFalse(cfg.autoIncludeMedia)
        assertEquals(MediaInclusion.SIDECARS_ONLY, cfg.resolveMediaInclusionForAuto())
    }

    @Test
    fun resolveMediaInclusionForAuto_trueMapsToIncludeMedia() {
        assertEquals(
            MediaInclusion.INCLUDE_MEDIA,
            FilebaseConfig("a", "s", "b", autoUpload = true, autoIncludeMedia = true).resolveMediaInclusionForAuto(),
        )
    }

    @Test
    fun fromPrefs_readsAllKeys_withLockedDefaults() {
        val cfg = FilebaseConfig.fromPrefs(emptyPrefs())
        assertTrue(cfg.autoUpload)
        assertTrue(cfg.autoIncludeMedia)
        assertEquals("us-east-1", cfg.region)
        assertEquals("https://s3.filebase.com", cfg.endpoint)
        assertFalse(cfg.enabled)
        assertEquals("", cfg.accessKey)
        assertEquals("", cfg.secretKey)
        assertEquals("", cfg.bucketName)
        assertEquals("", cfg.ipfsBearerToken)
    }

    @Test
    fun ensurePrefsSchema_clearsLegacy_thenMissingAutoDefaultsTrue() {
        // pre-v2 keys present → wipe → missing auto keys → true (U3), enabled false
        val prefs = prefsWith(
            FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD to true,
            FilebaseConfig.PREF_FILEBASE_ENABLED to true,
            FilebaseConfig.PREF_FILEBASE_ACCESS_KEY to "ak",
            FilebaseConfig.PREF_FILEBASE_SECRET_KEY to "sk",
            FilebaseConfig.PREF_FILEBASE_BUCKET_NAME to "bucket",
            FilebaseConfig.PREF_FILEBASE_ENDPOINT to "https://legacy.example.com",
            FilebaseConfig.PREF_FILEBASE_IPFS_BEARER_TOKEN to "token",
            FilebaseConfig.PREF_FILEBASE_AUTO_INCLUDE_MEDIA to true,
        )
        val cfg = FilebaseConfig.fromPrefs(prefs)
        assertFalse(cfg.enabled)
        assertEquals("", cfg.accessKey)
        assertTrue(cfg.autoUpload)
        assertTrue(cfg.autoIncludeMedia)
        assertEquals(2, prefs.getInt(FilebaseConfig.PREF_FILEBASE_PREFS_SCHEMA_VERSION, -1))
    }

    @Test
    fun ensurePrefsSchema_idempotentAfterVersion2() {
        val prefs = prefsWith(
            FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD to true,
            FilebaseConfig.PREF_FILEBASE_ENABLED to true,
            FilebaseConfig.PREF_FILEBASE_ACCESS_KEY to "ak",
            schema = FilebaseConfig.FILEBASE_PREFS_SCHEMA_VERSION,
        )
        val cfg = FilebaseConfig.fromPrefs(prefs)
        assertTrue(cfg.autoUpload)
        assertTrue(cfg.enabled)
        assertEquals("ak", cfg.accessKey)
    }

    @Test
    fun ensurePrefsSchema_directCall_isIdempotent() {
        val prefs = prefsWith(
            FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD to true,
            FilebaseConfig.PREF_FILEBASE_ENABLED to true,
            FilebaseConfig.PREF_FILEBASE_ACCESS_KEY to "legacy",
        )
        FilebaseConfig.ensurePrefsSchema(prefs)
        prefs.edit()
            .putBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, true)
            .putString(FilebaseConfig.PREF_FILEBASE_ACCESS_KEY, "ak")
            .putBoolean(FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD, true)
            .commit()
        FilebaseConfig.ensurePrefsSchema(prefs)
        assertEquals(2, prefs.getInt(FilebaseConfig.PREF_FILEBASE_PREFS_SCHEMA_VERSION, -1))
        assertTrue(prefs.getBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, false))
        assertEquals("ak", prefs.getString(FilebaseConfig.PREF_FILEBASE_ACCESS_KEY, ""))
        assertTrue(prefs.getBoolean(FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD, false))
    }

    @Test
    fun hasUsableCredentials_s3OrBearer_withoutEnabled() {
        assertTrue(FilebaseConfig("a", "s", "b", enabled = false).hasUsableCredentials())
        assertTrue(
            FilebaseConfig("", "", "", enabled = false, ipfsBearerToken = "tok").hasUsableCredentials()
        )
        assertFalse(FilebaseConfig("", "", "", enabled = false).hasUsableCredentials())
        assertFalse(FilebaseConfig("a", "", "b", enabled = true).hasUsableCredentials()) // incomplete S3
    }

    @Test
    fun draft_incompleteVsClearedVsUsable() {
        val cleared = FilebaseSettingsDraft("", "", "", "https://s3.filebase.com", "us-east-1", "", true, true)
        assertTrue(cleared.isClearedCredentials())
        assertFalse(cleared.hasUsableCredentials())

        val partial = cleared.copy(accessKey = "ak")
        assertTrue(partial.isIncompleteCredentials())
        assertFalse(partial.hasUsableCredentials())

        val bearer = cleared.copy(ipfsBearerToken = "tok")
        assertTrue(bearer.hasUsableCredentials())
        assertFalse(bearer.isIncompleteCredentials())
    }

    @Test
    fun isConfigured_stillRequiresEnabled_u7() {
        assertFalse(
            FilebaseConfig("a", "s", "b", enabled = false, autoUpload = true).isConfigured()
        )
        assertTrue(FilebaseConfig("a", "s", "b", enabled = true).hasUsableCredentials())
        assertTrue(FilebaseConfig("a", "s", "b", enabled = true).isConfigured())
    }

    @Test
    fun fromPrefs_readsExplicitValues() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putInt(FilebaseConfig.PREF_FILEBASE_PREFS_SCHEMA_VERSION, FilebaseConfig.FILEBASE_PREFS_SCHEMA_VERSION)
            .putString(FilebaseConfig.PREF_FILEBASE_ACCESS_KEY, "mykey")
            .putString(FilebaseConfig.PREF_FILEBASE_SECRET_KEY, "mysecret")
            .putString(FilebaseConfig.PREF_FILEBASE_BUCKET_NAME, "mybucket")
            .putString(FilebaseConfig.PREF_FILEBASE_ENDPOINT, "https://custom.example.com")
            .putString(FilebaseConfig.PREF_FILEBASE_REGION, "eu-west-1")
            .putBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, true)
            .putString(FilebaseConfig.PREF_FILEBASE_IPFS_BEARER_TOKEN, "mytoken")
            .putBoolean(FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD, false)
            .commit()
        val cfg = FilebaseConfig.fromPrefs(prefs)
        assertEquals("mykey", cfg.accessKey)
        assertEquals("mysecret", cfg.secretKey)
        assertEquals("mybucket", cfg.bucketName)
        assertEquals("https://custom.example.com", cfg.endpoint)
        assertEquals("eu-west-1", cfg.region)
        assertTrue(cfg.enabled)
        assertEquals("mytoken", cfg.ipfsBearerToken)
        assertFalse(cfg.autoUpload)
    }

    private fun baselineConfigured(): FilebaseConfig =
        FilebaseConfig("a", "s", "b", enabled = true, ipfsBearerToken = "", autoUpload = true, autoIncludeMedia = true)

    private fun draftFrom(cfg: FilebaseConfig) = FilebaseSettingsDraft(
        cfg.accessKey, cfg.secretKey, cfg.bucketName, cfg.endpoint, cfg.region,
        cfg.ipfsBearerToken, cfg.autoUpload, cfg.autoIncludeMedia,
    )

    @Test
    fun commitDraft_unchanged_noWrites() {
        val prefs = prefsWith(
            FilebaseConfig.PREF_FILEBASE_ACCESS_KEY to "a",
            FilebaseConfig.PREF_FILEBASE_SECRET_KEY to "s",
            FilebaseConfig.PREF_FILEBASE_BUCKET_NAME to "b",
            FilebaseConfig.PREF_FILEBASE_ENABLED to true,
            FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD to true,
            FilebaseConfig.PREF_FILEBASE_AUTO_INCLUDE_MEDIA to true,
            schema = 2,
        )
        val baseline = FilebaseConfig.fromPrefs(prefs)
        assertEquals(CommitDraftResult.Unchanged, FilebaseConfig.commitDraft(prefs, draftFrom(baseline), baseline))
    }

    @Test
    fun commitDraft_usable_setsEnabledTrue() {
        val prefs = prefsWith(schema = 2)
        val baseline = FilebaseConfig.fromPrefs(prefs)
        val draft = FilebaseSettingsDraft("a", "s", "b", "https://s3.filebase.com", "us-east-1", "", true, true)
        assertEquals(CommitDraftResult.Committed, FilebaseConfig.commitDraft(prefs, draft, baseline))
        val after = FilebaseConfig.fromPrefs(prefs)
        assertTrue(after.enabled)
        assertTrue(after.hasUsableCredentials())
        assertTrue(after.isConfigured())
    }

    @Test
    fun commitDraft_cleared_forcesEnableAndAutosOff() {
        val prefs = prefsWith(
            FilebaseConfig.PREF_FILEBASE_ACCESS_KEY to "a",
            FilebaseConfig.PREF_FILEBASE_SECRET_KEY to "s",
            FilebaseConfig.PREF_FILEBASE_BUCKET_NAME to "b",
            FilebaseConfig.PREF_FILEBASE_ENABLED to true,
            FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD to true,
            FilebaseConfig.PREF_FILEBASE_AUTO_INCLUDE_MEDIA to true,
            schema = 2,
        )
        val baseline = FilebaseConfig.fromPrefs(prefs)
        val draft = FilebaseSettingsDraft("", "", "", "https://s3.filebase.com", "us-east-1", "", true, true)
        assertEquals(CommitDraftResult.Committed, FilebaseConfig.commitDraft(prefs, draft, baseline))
        val after = FilebaseConfig.fromPrefs(prefs)
        assertFalse(after.enabled)
        assertEquals("", after.accessKey)
        assertFalse(after.autoUpload)
        assertFalse(after.autoIncludeMedia)
    }

    @Test
    fun commitDraft_incomplete_discards_keepsPriorWhenUsable() {
        val prefs = prefsWith(
            FilebaseConfig.PREF_FILEBASE_ACCESS_KEY to "a",
            FilebaseConfig.PREF_FILEBASE_SECRET_KEY to "s",
            FilebaseConfig.PREF_FILEBASE_BUCKET_NAME to "b",
            FilebaseConfig.PREF_FILEBASE_ENABLED to true,
            FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD to true,
            schema = 2,
        )
        val baseline = FilebaseConfig.fromPrefs(prefs)
        val draft = draftFrom(baseline).copy(accessKey = "only-ak", secretKey = "", bucketName = "")
        assertEquals(CommitDraftResult.DiscardedInvalid, FilebaseConfig.commitDraft(prefs, draft, baseline))
        val after = FilebaseConfig.fromPrefs(prefs)
        assertEquals("a", after.accessKey)
        assertTrue(after.enabled)
        assertTrue(after.autoUpload)
    }

    @Test
    fun toggleAutoUploadIfConfigured_togglesOnlyWhenConfigured() {
        val prefs = prefsWith(
            FilebaseConfig.PREF_FILEBASE_ACCESS_KEY to "a",
            FilebaseConfig.PREF_FILEBASE_SECRET_KEY to "s",
            FilebaseConfig.PREF_FILEBASE_BUCKET_NAME to "b",
            FilebaseConfig.PREF_FILEBASE_ENABLED to true,
            FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD to true,
            schema = 2,
        )
        assertTrue(FilebaseConfig.toggleAutoUploadIfConfigured(prefs))
        assertFalse(FilebaseConfig.fromPrefs(prefs).autoUpload)
        assertTrue(FilebaseConfig.toggleAutoUploadIfConfigured(prefs))
        assertTrue(FilebaseConfig.fromPrefs(prefs).autoUpload)
    }

    @Test
    fun toggleAutoUploadIfConfigured_noopWhenNotConfigured() {
        val prefs = prefsWith(schema = 2)
        assertFalse(FilebaseConfig.toggleAutoUploadIfConfigured(prefs))
        // must not invent auto-upload on
        assertFalse(FilebaseConfig.fromPrefs(prefs).isConfigured())
    }

    @Test
    fun autoSyncIndicatorChecked_requiresConfiguredAndAuto() {
        assertFalse(
            FilebaseConfig.autoSyncIndicatorChecked(
                FilebaseConfig("a", "s", "b", enabled = false, autoUpload = true)
            )
        )
        assertFalse(
            FilebaseConfig.autoSyncIndicatorChecked(
                FilebaseConfig("a", "s", "b", enabled = true, autoUpload = false)
            )
        )
        assertTrue(
            FilebaseConfig.autoSyncIndicatorChecked(
                FilebaseConfig("a", "s", "b", enabled = true, autoUpload = true)
            )
        )
    }

    @Test
    fun commitDraft_incomplete_forcesOffWhenPriorUnusable() {
        val prefs = prefsWith(
            FilebaseConfig.PREF_FILEBASE_ENABLED to true, // inconsistent / empty creds
            FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD to true,
            FilebaseConfig.PREF_FILEBASE_AUTO_INCLUDE_MEDIA to true,
            schema = 2,
        )
        val baseline = FilebaseConfig.fromPrefs(prefs)
        assertFalse(baseline.hasUsableCredentials())
        val draft = FilebaseSettingsDraft("partial", "", "", "https://s3.filebase.com", "us-east-1", "", true, true)
        assertEquals(CommitDraftResult.DiscardedInvalid, FilebaseConfig.commitDraft(prefs, draft, baseline))
        assertFalse(prefs.getBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, true))
        assertFalse(prefs.getBoolean(FilebaseConfig.PREF_FILEBASE_AUTO_UPLOAD, true))
        assertFalse(prefs.getBoolean(FilebaseConfig.PREF_FILEBASE_AUTO_INCLUDE_MEDIA, true))
    }

    @Test
    fun isWithinFilebaseMediaLimit_trueAtExact25MiB() {
        assertEquals(25L * 1024 * 1024, FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES)
        assertTrue(
            FilebaseConfig.isWithinFilebaseMediaLimit(
                FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES,
            ),
        )
    }

    @Test
    fun isWithinFilebaseMediaLimit_falseJustOver25MiB() {
        assertFalse(
            FilebaseConfig.isWithinFilebaseMediaLimit(
                FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES + 1L,
            ),
        )
    }

    @Test
    fun isWithinFilebaseMediaLimit_falseZeroOrNegative() {
        assertFalse(FilebaseConfig.isWithinFilebaseMediaLimit(0L))
        assertFalse(FilebaseConfig.isWithinFilebaseMediaLimit(-1L))
    }

    @Test
    fun isWithinFilebaseMediaLimit_ignoresProofsetTotals_byApiShape() {
        // Predicate takes media length only; callers must not sum sidecars or proofset totals.
        val method =
            FilebaseConfig::class.java.getMethod(
                "isWithinFilebaseMediaLimit",
                Long::class.javaPrimitiveType,
            )
        assertEquals(1, method.parameterTypes.size)
        assertEquals(Long::class.javaPrimitiveType, method.parameterTypes[0])

        assertTrue(FilebaseConfig.isWithinFilebaseMediaLimit(1L))
        assertFalse(
            FilebaseConfig.isWithinFilebaseMediaLimit(
                FilebaseConfig.FILEBASE_MEDIA_MAX_BYTES + 1L,
            ),
        )
    }

    private fun emptyPrefs() = PreferenceManager.getDefaultSharedPreferences(context)

    private fun prefsWith(vararg pairs: Pair<String, Any>, schema: Int? = null): SharedPreferences {
        val prefs = emptyPrefs()
        val editor = prefs.edit()
        for ((key, value) in pairs) {
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
            }
        }
        if (schema != null) {
            editor.putInt(FilebaseConfig.PREF_FILEBASE_PREFS_SCHEMA_VERSION, schema)
        }
        editor.commit()
        return prefs
    }
}
