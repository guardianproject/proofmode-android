package org.witness.proofmode.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.witness.proofmode.ui.media.GalleryItemThumb
import java.util.Date

/**
 * These tests make sure that touch input on media items works correctly.
 *
 * 1. The tests use the real composables. They use fake items and a recording
 *    [SelectionHandler] in place of the activity.
 * 2. A tap on a gallery thumbnail sends the item to the handler.
 * 3. A long press on a gallery thumbnail sends the item to the handler through the overlay.
 * 4. A gallery thumbnail uses the size that its parent sets.
 * 5. A swipe left in the single-asset viewer shows the next item.
 * 6. A drag down past the threshold hides the metadata panel.
 * 7. Reason: a layout change can break touch input without an error message.
 *    These tests find this fault before a user does.
 * 8. The viewer reads its items from the global [Activities] list. The tests put items
 *    in this list. [resetSharedActivityState] removes them after each test.
 */
@RunWith(AndroidJUnit4::class)
class ProofableItemInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun resetSharedActivityState() {
        composeRule.runOnUiThread { Activities.activities.clear() }
        DeletedStatusCache.clear()
    }

    @Test
    fun thumbnailHost_appliesExternalSizeAndClickReachesSelectionHandler() {
        val handler = RecordingSelectionHandler()
        val pendingItem = pendingItem("thumbnail")

        composeRule.setContent {
            CompositionLocalProvider(LocalSelectionHandler provides handler) {
                GalleryItemThumb(
                    item = pendingItem,
                    modifier = Modifier.testTag("thumbnail-host").size(64.dp),
                )
            }
        }

        composeRule.onNodeWithTag("thumbnail-host").assertIsDisplayed()
        composeRule.onNodeWithTag("thumbnail-host").assertWidthIsEqualTo(64.dp)
        composeRule.onNodeWithTag("thumbnail-host").performTouchInput { click() }

        assertEquals(listOf(pendingItem), handler.clicked)
    }

    @Test
    fun thumbnailLongClick_reachesSelectionHandlerThroughOverlay() {
        val handler = RecordingSelectionHandler()
        val pendingItem = pendingItem("long-click")

        composeRule.setContent {
            CompositionLocalProvider(LocalSelectionHandler provides handler) {
                GalleryItemThumb(
                    item = pendingItem,
                    modifier = Modifier.size(64.dp),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Asset view")
            .performTouchInput { longClick() }

        assertEquals(listOf(pendingItem), handler.longClicked)
    }

    @Test
    fun unzoomedDetailSwipeLeft_pagesToNextItem() {
        val handler = RecordingSelectionHandler()
        val first = pendingItem("first")
        val second = pendingItem("second")

        // SingleAssetView pages through Activities.activities (not a parameter), and
        // drops items whose media can't be opened; mark the fake URIs as present.
        composeRule.runOnUiThread {
            listOf(first, second).forEach { DeletedStatusCache.put(it.uri, false) }
            Activities.activities.add(
                Activity(
                    id = "swipe-activity",
                    type = ActivityType.MediaCaptured(mutableStateListOf(first, second)),
                    startTime = Date(),
                )
            )
        }

        composeRule.setContent {
            CompositionLocalProvider(
                LocalSelectionHandler provides handler,
                LocalShowMetadata provides false,
            ) {
                Box(
                    modifier = Modifier
                        .testTag("detail-host")
                        .size(400.dp, 800.dp),
                ) {
                    SingleAssetView(
                        initialItem = first,
                        modifier = Modifier.fillMaxSize(),
                        setShowMetadata = {},
                        setTitle = {},
                    )
                }
            }
        }

        // The pager composes only the visible page, and SingleItemView asks the
        // selection handler about each item it draws; that is how we see the page.
        composeRule.waitForIdle()
        assertTrue(first in handler.queried)
        assertFalse(second in handler.queried)

        composeRule.onNodeWithTag("detail-host")
            .performTouchInput { swipeLeft() }

        composeRule.waitForIdle()
        assertTrue(second in handler.queried)
    }

    @Test
    fun unzoomedVerticalDrag_pastMetadataThresholdHidesMetadata() {
        val handler = RecordingSelectionHandler()
        val metadataVisibility = mutableListOf<Boolean>()

        composeRule.setContent {
            CompositionLocalProvider(
                LocalSelectionHandler provides handler,
                LocalShowMetadata provides true,
            ) {
                Box(
                    modifier = Modifier
                        .testTag("detail-host")
                        .size(400.dp, 800.dp),
                ) {
                    SingleAssetView(
                        initialItem = pendingItem("metadata"),
                        modifier = Modifier.fillMaxSize(),
                        setShowMetadata = metadataVisibility::add,
                        setTitle = {},
                    )
                }
            }
        }

        // Drag down far enough for metadataOpacity < 0.8 at onDragStopped
        // (SingleAssetView.kt:260-265).
        composeRule.onNodeWithTag("detail-host")
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, 250f))
                up()
            }

        composeRule.runOnIdle {
            assertEquals(listOf(false), metadataVisibility)
        }
    }

    private fun pendingItem(id: String) = ProofableItem(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        proofStatus = ProofStatus.PENDING,
    )

    private class RecordingSelectionHandler : SelectionHandler {
        val clicked = mutableListOf<ProofableItem>()
        val longClicked = mutableListOf<ProofableItem>()
        val queried = mutableListOf<ProofableItem>()

        override fun onProofableItemClick(item: ProofableItem) {
            clicked += item
        }

        override fun onProofableItemLongClick(item: ProofableItem) {
            longClicked += item
        }

        override fun isSelected(item: ProofableItem): Boolean {
            queried += item
            return false
        }

        override fun anySelected(): Boolean = false

        @Composable
        override fun selectedItems(): List<ProofableItem> = emptyList()
    }
}
