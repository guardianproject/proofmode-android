package org.witness.proofmode.plugins.ipfscid

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.witness.proofmode.ProofMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProofSetCidSidecarWriterLateRecomputeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun composeLeafBackedManifest_reusesCachedMediaJpgAndAddsOts() {
        val hash = "latehash0001"
        val mediaLink = "$hash.jpg"
        val sidecar = SidecarSnapshot(
            rootCid = "bafyOLD",
            files = mapOf(
                mediaLink to "bafkMEDIA",
                "$hash.proof.csv" to "bafkCSV",
            ),
            tsizes = mapOf(
                mediaLink to 100L,
                "$hash.proof.csv" to 50L,
            ),
        )
        val otsBytes = byteArrayOf(1, 2, 3)
        val manifestMembers = ProofSetCidMembershipPolicy.manifestMemberBasenames(
            context, hash, listOf("$hash.proof.csv", "$hash.ots"),
        )
        val leaves = ProofSetCidManifest.composeLeafBackedManifest(
            proofSetHash = hash,
            manifestMemberBasenames = manifestMembers,
            sidecar = sidecar,
            newLeafBytesByBasename = mapOf("$hash.ots" to otsBytes),
            computeLeafFromBytes = { _ -> "bafkOTS" to 3L },
        )
        assertEquals("bafkMEDIA", leaves.first { it.name == mediaLink }.leafCid)
        assertTrue(leaves.any { it.name == "$hash.ots" && it.leafCid == "bafkOTS" })
    }

    @Test
    fun composeLeafBackedManifest_addsOtsAndNostr_whenBothOnDisk() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, true)
            .commit()
        val hash = "notaryboth0001"
        val mediaLink = "$hash.bin"
        val sidecar = SidecarSnapshot(
            rootCid = "bafyOLD",
            files = mapOf(mediaLink to "bafkMEDIA", "$hash.proof.csv" to "bafkCSV"),
            tsizes = mapOf(mediaLink to 10L, "$hash.proof.csv" to 5L),
        )
        val manifestMembers = ProofSetCidMembershipPolicy.manifestMemberBasenames(
            context, hash, listOf("$hash.proof.csv", "$hash.ots", "$hash.nostr"),
        )
        val leaves = ProofSetCidManifest.composeLeafBackedManifest(
            proofSetHash = hash,
            manifestMemberBasenames = manifestMembers,
            sidecar = sidecar,
            newLeafBytesByBasename = mapOf("$hash.ots" to byteArrayOf(1), "$hash.nostr" to byteArrayOf(2)),
            computeLeafFromBytes = { bytes -> "bafk${bytes.size}" to bytes.size.toLong() },
        )
        assertTrue(leaves.any { it.name == "$hash.ots" })
        assertTrue(leaves.any { it.name == "$hash.nostr" })
        assertEquals(4, leaves.size)
    }

    @Test
    fun composeLeafBackedManifest_includesOtsAndNostrWhenPrefsEnabled() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_OTS, true)
            .putBoolean(ProofMode.PREF_OPTION_NOTARY_NOSTR, true)
            .commit()
        val hash = "notaryboth0001"
        val mediaLink = "$hash.bin"
        val sidecar = SidecarSnapshot(
            rootCid = "bafyOLD",
            files = mapOf(mediaLink to "bafkMEDIA", "$hash.proof.csv" to "bafkCSV"),
            tsizes = mapOf(mediaLink to 10L, "$hash.proof.csv" to 5L),
        )
        val manifestMembers = ProofSetCidMembershipPolicy.manifestMemberBasenames(
            context, hash, listOf("$hash.proof.csv", "$hash.ots", "$hash.nostr"),
        )
        val leaves = ProofSetCidManifest.composeLeafBackedManifest(
            proofSetHash = hash,
            manifestMemberBasenames = manifestMembers,
            sidecar = sidecar,
            newLeafBytesByBasename = mapOf("$hash.ots" to byteArrayOf(1), "$hash.nostr" to byteArrayOf(2)),
            computeLeafFromBytes = { bytes -> "bafk${bytes.size}" to bytes.size.toLong() },
        )
        assertTrue(leaves.any { it.name == "$hash.ots" })
        assertTrue(leaves.any { it.name == "$hash.nostr" })
        assertEquals(4, leaves.size)
    }
}
