package org.witness.proofmode.storage.proofset

import android.content.Context
import android.net.Uri
import android.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.ProofMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofSetMembershipPolicyTest {
    private val hash = "abc123deadbeef"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun coreBasenames(): Set<String> = ProofSetMembershipPolicy.requiredCoreBasenames(hash)

    @Test
    fun isCoreArtifact_fiveCoreNames() {
        for (name in coreBasenames()) {
            assertTrue(name, ProofSetMembershipPolicy.isCoreArtifact(hash, name))
        }
        assertFalse(ProofSetMembershipPolicy.isCoreArtifact(hash, "$hash.ots"))
        assertFalse(ProofSetMembershipPolicy.isCoreArtifact(hash, "$hash.jpg"))
    }

    @Test
    fun isManifestMember_coreArtifacts_alwaysTrue() {
        assertTrue(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.proof.csv"))
        assertTrue(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.proof.json"))
        assertTrue(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.proof.csv.asc"))
        assertTrue(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.proof.json.asc"))
        assertTrue(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.asc"))
    }

    @Test
    fun isManifestMember_ots_requiresGlobalAndProviderPref() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .commit()
        assertTrue(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.ots"))

        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, false)
            .commit()
        assertFalse(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.ots"))

        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY, false)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .commit()
        assertFalse(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.ots"))
    }

    @Test
    fun isManifestMember_nostr_requiresGlobalAndProviderPref() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, true)
            .commit()
        assertTrue(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.nostr"))

        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, false)
            .commit()
        assertFalse(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.nostr"))

        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY, false)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, true)
            .commit()
        assertFalse(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.nostr"))
    }

    @Test
    fun globalNotaryOff_otsOnDisk_notAMember() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY, false)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .commit()
        val onDisk = coreBasenames() + "$hash.ots"
        val members = ProofSetMembershipPolicy.manifestMemberBasenames(context, hash, onDisk)
        assertFalse(members.contains("$hash.ots"))
        assertEquals(coreBasenames().sorted(), members)
    }

    @Test
    fun isFirstPassComplete_trueWithCoreAndMedia_evenWhenOtsPrefOnAndAbsent() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .commit()
        assertTrue(
            ProofSetMembershipPolicy.isFirstPassComplete(
                context,
                hash,
                coreBasenames(),
                mediaInclusion = MediaInclusion.INCLUDE_MEDIA,
                mediaBytesAvailable = true,
            ),
        )
    }

    @Test
    fun isFirstPassComplete_falseWhenMediaMissing() {
        assertFalse(
            ProofSetMembershipPolicy.isFirstPassComplete(
                context,
                hash,
                coreBasenames(),
                mediaInclusion = MediaInclusion.INCLUDE_MEDIA,
                mediaBytesAvailable = false,
            ),
        )
    }

    @Test
    fun isFirstPassComplete_falseWhenCoreMissing() {
        val incomplete = coreBasenames() - "$hash.proof.json"
        assertFalse(
            ProofSetMembershipPolicy.isFirstPassComplete(
                context,
                hash,
                incomplete,
                mediaInclusion = MediaInclusion.INCLUDE_MEDIA,
                mediaBytesAvailable = true,
            ),
        )
    }

    @Test
    fun firstPass_sidecarsOnly_completeWithCoreOnly_noMedia() {
        val hash = "abc"
        val onDisk = ProofSetMembershipPolicy.requiredCoreBasenames(hash)
        assertTrue(
            ProofSetMembershipPolicy.isFirstPassComplete(
                context, hash, onDisk,
                mediaInclusion = MediaInclusion.SIDECARS_ONLY,
                mediaBytesAvailable = false,
            ),
        )
    }

    @Test
    fun firstPass_includeMedia_incompleteWithoutMedia() {
        val hash = "abc"
        val onDisk = ProofSetMembershipPolicy.requiredCoreBasenames(hash)
        assertFalse(
            ProofSetMembershipPolicy.isFirstPassComplete(
                context, hash, onDisk,
                mediaInclusion = MediaInclusion.INCLUDE_MEDIA,
                mediaBytesAvailable = false,
            ),
        )
    }

    @Test
    fun firstPass_includeMedia_completeWithMedia() {
        val hash = "abc"
        val onDisk = ProofSetMembershipPolicy.requiredCoreBasenames(hash)
        assertTrue(
            ProofSetMembershipPolicy.isFirstPassComplete(
                context, hash, onDisk,
                mediaInclusion = MediaInclusion.INCLUDE_MEDIA,
                mediaBytesAvailable = true,
            ),
        )
    }

    @Test
    fun membership_excludesFilebaseImageUri() {
        assertTrue(ProofSetMembershipPolicy.isExcludedFromManifest("$hash.filebase.image.uri"))
        assertFalse(ProofSetMembershipPolicy.isManifestMember(context, hash, "$hash.filebase.image.uri"))
    }

    @Test
    fun excludes_uri_lp_andIpfsCids() {
        val excluded = listOf(
            "$hash.filebase.ipfs.uri",
            "$hash.filebase.uri",
            "$hash.uri",
            "$hash.lp.offchain.json",
            "$hash.lp.onchain.json",
            "$hash.lp.onchain.pending.json",
            "$hash.lp.json",
            "$hash.ipfs-cids.json",
            "$hash.ipfs-cids.late-1718820000000.json",
        )
        for (name in excluded) {
            assertTrue(name, ProofSetMembershipPolicy.isExcludedFromManifest(name))
            assertFalse(name, ProofSetMembershipPolicy.isManifestMember(context, hash, name))
        }
    }

    @Test
    fun fromProofSetUri_fileUri_returnsBasename() {
        val uri = Uri.fromFile(File("/tmp/h.proof.csv"))
        assertEquals("h.proof.csv", ProofSetMembershipPolicy.fromProofSetUri(uri))
    }

    @Test
    fun manifestLinkNameForMedia_jpeg_returnsHashDotJpg() {
        assertEquals("abc123.jpg", ProofSetMembershipPolicy.manifestLinkNameForMedia("abc123", "image/jpeg"))
        assertEquals("abc123.png", ProofSetMembershipPolicy.manifestLinkNameForMedia("abc123", "image/png"))
        assertEquals("abc123.mp4", ProofSetMembershipPolicy.manifestLinkNameForMedia("abc123", "video/mp4"))
        assertEquals("abc123.bin", ProofSetMembershipPolicy.manifestLinkNameForMedia("abc123", null))
    }

    @Test
    fun manifestMemberBasenames_filtersAndSorts() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .commit()
        val onDisk = listOf(
            "$hash.ipfs-cids.json",
            "$hash.ots",
            "$hash.proof.json",
            "$hash.lp.offchain.json",
            "$hash.proof.csv",
            "$hash.uri",
        )
        assertEquals(
            listOf("$hash.ots", "$hash.proof.csv", "$hash.proof.json"),
            ProofSetMembershipPolicy.manifestMemberBasenames(context, hash, onDisk),
        )
    }
}
