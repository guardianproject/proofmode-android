package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import androidx.preference.PreferenceManager
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofSetCidMembershipPolicyTest {
    private val hash = "abc123deadbeef"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun isExcludedFromManifest_sidecarIdentifier() {
        assertTrue(ProofSetCidMembershipPolicy.isExcludedFromManifest("$hash.ipfs-cids.json"))
        assertTrue(ProofSetCidMembershipPolicy.isExcludedFromManifest("$hash.ipfs-cids.late-1718820000000.json"))
    }

    @Test
    fun isExcludedFromManifest_lpArtifacts() {
        assertTrue(ProofSetCidMembershipPolicy.isExcludedFromManifest("$hash.lp.offchain.json"))
        assertTrue(ProofSetCidMembershipPolicy.isExcludedFromManifest("$hash.lp.onchain.json"))
        assertTrue(ProofSetCidMembershipPolicy.isExcludedFromManifest("$hash.lp.onchain.pending.json"))
        assertTrue(ProofSetCidMembershipPolicy.isExcludedFromManifest("$hash.lp.json"))
    }

    @Test
    fun isExcludedFromManifest_coreProofFiles_false() {
        assertFalse(ProofSetCidMembershipPolicy.isExcludedFromManifest("$hash.proof.csv"))
        assertFalse(ProofSetCidMembershipPolicy.isExcludedFromManifest("$hash.ots"))
    }

    @Test
    fun isManifestMember_coreArtifacts_alwaysTrue() {
        assertTrue(ProofSetCidMembershipPolicy.isManifestMember(context, hash, "$hash.proof.csv"))
        assertTrue(ProofSetCidMembershipPolicy.isManifestMember(context, hash, "$hash.proof.json"))
        assertTrue(ProofSetCidMembershipPolicy.isManifestMember(context, hash, "$hash.proof.csv.asc"))
        assertTrue(ProofSetCidMembershipPolicy.isManifestMember(context, hash, "$hash.proof.json.asc"))
        assertTrue(ProofSetCidMembershipPolicy.isManifestMember(context, hash, "$hash.asc"))
    }

    @Test
    fun isManifestMember_ots_whenPrefEnabledAndExists() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true).commit()
        assertTrue(ProofSetCidMembershipPolicy.isManifestMember(context, hash, "$hash.ots"))
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, false).commit()
        assertFalse(ProofSetCidMembershipPolicy.isManifestMember(context, hash, "$hash.ots"))
    }

    @Test
    fun isManifestMember_nostr_whenPrefEnabled() {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, true).commit()
        assertTrue(ProofSetCidMembershipPolicy.isManifestMember(context, hash, "$hash.nostr"))
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, false).commit()
        assertFalse(ProofSetCidMembershipPolicy.isManifestMember(context, hash, "$hash.nostr"))
    }

    @Test
    fun triggersSidecarRefresh_otsAndNostrOnly() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, true)
            .commit()
        assertTrue(ProofSetCidMembershipPolicy.triggersSidecarRefresh(context, "$hash.ots"))
        assertTrue(ProofSetCidMembershipPolicy.triggersSidecarRefresh(context, "$hash.nostr"))
        assertFalse(ProofSetCidMembershipPolicy.triggersSidecarRefresh(context, "$hash.proof.csv"))
        assertFalse(ProofSetCidMembershipPolicy.triggersSidecarRefresh(context, "$hash.lp.offchain.json"))
        assertFalse(ProofSetCidMembershipPolicy.triggersSidecarRefresh(context, "$hash.ipfs-cids.json"))
    }

    @Test
    fun manifestMemberBasenames_excludesDenylistedEvenIfPresentInGetProofSet() {
        val diskIds = listOf(
            "$hash.proof.csv",
            "$hash.ipfs-cids.json",
            "$hash.lp.offchain.json",
            "$hash.proof.json",
        )
        val included = ProofSetCidMembershipPolicy.manifestMemberBasenames(context, hash, diskIds)
        assertEquals(listOf("$hash.proof.csv", "$hash.proof.json"), included)
    }

    @Test
    fun manifestAndRefreshRules_tableDriven() {
        data class Row(
            val basename: String,
            val expectMember: Boolean,
            val expectRefresh: Boolean,
        )
        val rows = listOf(
            Row("$hash.proof.csv", expectMember = true, expectRefresh = false),
            Row("$hash.ots", expectMember = true, expectRefresh = true),
            Row("$hash.ipfs-cids.json", expectMember = false, expectRefresh = false),
            Row("$hash.lp.json", expectMember = false, expectRefresh = false),
        )
        for (row in rows) {
            assertEquals(
                row.basename,
                row.expectMember,
                ProofSetCidMembershipPolicy.isManifestMember(context, hash, row.basename),
            )
            assertEquals(
                row.basename,
                row.expectRefresh,
                ProofSetCidMembershipPolicy.triggersSidecarRefresh(context, row.basename),
            )
        }
    }
}
