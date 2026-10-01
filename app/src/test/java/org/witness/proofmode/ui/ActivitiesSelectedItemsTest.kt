package org.witness.proofmode.ui

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.witness.proofmode.TestProofModeApplication
import java.util.Date

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestProofModeApplication::class)
class ActivitiesSelectedItemsTest {

    @Before
    fun snapshotActivities() {
        Activities.activities.clear()
    }

    @After
    fun restoreActivities() {
        Activities.activities.clear()
    }

    @Test
    fun selectedItems_returnsInMemoryProofHash_notActivityId() {
        val hash = "proof-hash-from-gallery-item"
        val uri = Uri.parse("content://media/external/images/media/42")
        val proofItem = ProofableItem(hash, uri, ProofStatus.GENERATED)
        Activities.activities.add(
            Activity(
                "activity-uuid-not-a-hash",
                ActivityType.MediaCaptured(mutableStateListOf(proofItem)),
                Date(),
            ),
        )

        val selected = Activities.selectedItems(
            RuntimeEnvironment.getApplication(),
            listOf(uri.toString()),
        )

        assertEquals(listOf(hash), selected.map { it.id })
        assertEquals(listOf(uri), selected.map { it.uri })
    }

    @Test
    fun selectedItems_matchesMultipleUrisInOneActivity() {
        val first = ProofableItem("hash-a", Uri.parse("content://a"), ProofStatus.GENERATED)
        val second = ProofableItem("hash-b", Uri.parse("content://b"), ProofStatus.GENERATED)
        Activities.activities.add(
            Activity(
                "batch-activity",
                ActivityType.MediaCaptured(mutableStateListOf(first, second)),
                Date(),
            ),
        )

        val selected = Activities.selectedItems(
            RuntimeEnvironment.getApplication(),
            listOf(first.uri.toString(), second.uri.toString()),
        )

        assertEquals(listOf("hash-a", "hash-b"), selected.map { it.id })
    }
}
