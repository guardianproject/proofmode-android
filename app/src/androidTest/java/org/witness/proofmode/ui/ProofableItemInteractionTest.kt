package org.witness.proofmode.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.witness.proofmode.ui.media.GalleryItemThumb

@RunWith(AndroidJUnit4::class)
class ProofableItemInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

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
    fun unzoomedDetailSwipeLeft_selectsNextItem() {
        val handler = RecordingSelectionHandler()
        val items = listOf(pendingItem("first"), pendingItem("second"))
        val selectedIndexes = mutableListOf<Int>()

        composeRule.setContent {
            CompositionLocalProvider(LocalSelectionHandler provides handler) {
                Box(
                    modifier = Modifier
                        .testTag("carousel-host")
                        .size(240.dp),
                ) {
                    SingleAssetItemView(
                        width = 240.dp,
                        height = 240.dp,
                        allAssets = items,
                        selectedIndex = 0,
                        selectIndex = selectedIndexes::add,
                        setTitle = {},
                    )
                }
            }
        }

        // Drag past SingleAssetItemView's 50px threshold; host tag avoids ambiguous
        // "Asset view" nodes from adjacent carousel slots.
        composeRule.onNodeWithTag("carousel-host")
            .performTouchInput {
                down(center)
                moveBy(Offset(-120f, 0f))
                up()
            }

        composeRule.runOnIdle {
            assertEquals(listOf(1), selectedIndexes)
        }
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

        override fun onProofableItemClick(item: ProofableItem) {
            clicked += item
        }

        override fun onProofableItemLongClick(item: ProofableItem) {
            longClicked += item
        }

        override fun isSelected(item: ProofableItem): Boolean = false

        override fun anySelected(): Boolean = false

        @Composable
        override fun selectedItems(): List<ProofableItem> = emptyList()
    }
}
