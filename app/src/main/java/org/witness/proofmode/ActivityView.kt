package org.witness.proofmode

import android.annotation.SuppressLint
import android.graphics.RectF
import android.text.format.DateUtils
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date


const val ASSETS_GUTTER_SIZE = 10F
const val ASSETS_CORNER_RADIUS = 20F
val ASSETS_BACKGROUND = Color.Black.copy(0.1F)

interface ActivitiesViewDelegate {
    abstract fun openCamera()
    abstract fun shareItems(media: List<ProofableItem>, fileName: String?, shareText: String?)
    abstract fun sharePublicKey(key: String)
    abstract fun clearItems(activity: Activity)

}

sealed class CapturedAssetRow {
    class OneItem(val item: ProofableItem) : CapturedAssetRow()
    class TwoItems(val items: List<ProofableItem>) : CapturedAssetRow()
    class ThreeItems(val items: List<ProofableItem>) : CapturedAssetRow()
    class FourItems(val items: List<ProofableItem>) : CapturedAssetRow()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProofableItemView(
    item: ProofableItem,
    modifier: Modifier = Modifier,
    contain: Boolean = false,
    corners: RectF = RectF(
        ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS
    ),
    showSelectionBorder: Boolean = true
) {
    val selectionHandler = LocalSelectionHandler.current
    val context = LocalContext.current
    // Verify if Uri MIME type is image or video and if it is a video,get the thumbnail
    val isVideo = remember(item) {
        item.uri.let { uri ->
            context.contentResolver.getType(uri)?.contains("video") ?: false
        }
    }

    AsyncImage(
        model = if (!isVideo) item.uri.toString() else getVideoThumbnail(context, item.uri),
        contentDescription = "Asset view",
        alignment = Alignment.Center,
        contentScale = if (contain) ContentScale.Fit else ContentScale.Crop,
        modifier = Modifier
            .combinedClickable(
                onClick = {
                    selectionHandler.onProofableItemClick(item)
                },
                onLongClick = {
                    selectionHandler.onProofableItemLongClick(item)
                }
            )
            .clip(
                RoundedCornerShape(
                    corners.left.dp,
                    corners.top.dp,
                    corners.right.dp,
                    corners.bottom.dp
                )

            )
            //.background(ASSETS_BACKGROUND)
            .border(
                width = 4.dp,
                color = if (showSelectionBorder && selectionHandler.isSelected(item)) Color.Blue else Color.Transparent,
                shape = RoundedCornerShape(
                    corners.left.dp,
                    corners.top.dp,
                    corners.right.dp,
                    corners.bottom.dp
                )
            )
            .then(modifier)
    )
}

// Custom extension
fun Constraints.exact(width: Int, height: Int): Constraints {
    return this.copy(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height)
}

@Composable
fun OneItemAssetRowView(asset: ProofableItem) {
    ProofableItemView(item = asset, modifier = Modifier.aspectRatio(ratio = 16 / 9f))
}

@Composable
fun TwoItemsAssetRowView(assets: List<ProofableItem>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        ProofableItemView(
            item = assets[0],
            modifier = Modifier
                .weight(0.5F)
                .aspectRatio(ratio = 1f),
        )
        Spacer(
            modifier = Modifier
                .width(ASSETS_GUTTER_SIZE.dp)
                .fillMaxHeight()
        )
        ProofableItemView(
            item = assets[1],
            modifier = Modifier
                .weight(0.5F)
                .aspectRatio(ratio = 1f),
        )
    }
}

@Composable
fun ThreeItemsAssetRowView(assets: List<ProofableItem>) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            ProofableItemView(item = assets[0])
            ProofableItemView(item = assets[1])
            ProofableItemView(item = assets[2])
        }
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val column2Width = (w - 2 * ASSETS_GUTTER_SIZE.dp.roundToPx()) / 3
        val column2Height = 2 * column2Width + ASSETS_GUTTER_SIZE.dp.roundToPx()
        val column1 = w - column2Width - ASSETS_GUTTER_SIZE.dp.roundToPx()

        val placeable1 = measurables[0].measure(constraints.exact(column1, column2Height))
        val placeable2 = measurables[1].measure(constraints.exact(column2Width, column2Width))
        val placeable3 = measurables[2].measure(constraints.exact(column2Width, column2Width))

        layout(w, column2Height) {
            placeable1.place(x = 0, y = 0)
            placeable2.place(x = w - column2Width, y = 0)
            placeable3.place(x = w - column2Width, y = column2Height - column2Width)
        }
    }
}

@Composable
fun FourItemsAssetRowView(assets: List<ProofableItem>) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            ProofableItemView(item = assets[0])
            ProofableItemView(
                item = assets[1],
                corners = RectF(ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, 0f, 0f)
            )
            ProofableItemView(
                item = assets[2],
                corners = RectF(0f, 0f, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS)
            )
            ProofableItemView(item = assets[3])
        }
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val columnWidth = (w - 2 * ASSETS_GUTTER_SIZE.dp.roundToPx()) / 3

        val columnHeight = columnWidth
        val assetsGutterSize = ASSETS_GUTTER_SIZE.dp.roundToPx()
        val smallSize = (columnHeight - assetsGutterSize) / 2

        val placeable1 = measurables[0].measure(constraints.exact(columnWidth, columnHeight))
        val placeable2 = measurables[1].measure(constraints.exact(columnWidth, smallSize))
        val placeable3 = measurables[2].measure(constraints.exact(columnWidth, smallSize))
        val placeable4 = measurables[3].measure(constraints.exact(columnWidth, columnHeight))

        layout(w, columnHeight) {
            placeable1.place(x = 0, y = 0)
            placeable2.place(x = columnWidth + assetsGutterSize, y = 0)
            placeable3.place(x = columnWidth + assetsGutterSize, y = columnHeight - smallSize)
            placeable4.place(x = w - columnWidth, y = 0)
        }
    }
}

@Composable
fun MediaCapturedOrImportedActivityView(
    items: SnapshotStateList<ProofableItem>,
    capturedItems: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(ASSETS_GUTTER_SIZE.dp)) {

        var stringId = R.plurals.you_captured_n_items
        if (!capturedItems)
            stringId = R.plurals.you_imported_n_items

        Text(
            text = pluralStringResource(
                id = stringId,
                count = items.size,
                items.size
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.DarkGray,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val deletedItemsString = items.deletedItemsString()
        if (deletedItemsString != null) {
            Text(
                text = deletedItemsString,
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        val rows = layoutRows(items)
        rows.forEach { row ->
            when (row) {
                is CapturedAssetRow.OneItem ->
                    OneItemAssetRowView(row.item)

                is CapturedAssetRow.TwoItems ->
                    TwoItemsAssetRowView(row.items)

                is CapturedAssetRow.ThreeItems ->
                    ThreeItemsAssetRowView(row.items)

                is CapturedAssetRow.FourItems ->
                    FourItemsAssetRowView(row.items)
            }
        }
    }
}

@Composable
fun SnapshotStateList<ProofableItem>.deletedItemsString(): String? {
    val countDeleted = this.filter { it.isDeleted(LocalContext.current) }.size
    if (countDeleted > 0) {
        return pluralStringResource(
            id = R.plurals.n_items_have_been_deleted_or_are_not_accessible,
            count = countDeleted,
            countDeleted
        )
    }
    return null
}

@Composable
fun layoutRows(items: SnapshotStateList<ProofableItem>): MutableList<CapturedAssetRow> {
    var array = items.withDeletedItemsRemoved().toList().reversed()
    val rows: MutableList<CapturedAssetRow> = mutableListOf()
    while (array.isNotEmpty()) {
        array = if (array.size >= 7) {
            rows.add(CapturedAssetRow.ThreeItems(array.slice(IntRange(0, 2))))
            rows.add(CapturedAssetRow.FourItems(array.slice(IntRange(3, 6))))
            array.drop(7)
        } else if (array.size >= 3) {
            rows.add(CapturedAssetRow.ThreeItems(array.slice(IntRange(0, 2))))
            array.drop(3)
        } else if (array.size >= 2) {
            rows.add(CapturedAssetRow.TwoItems(array.slice(IntRange(0, 1))))
            array.drop(2)
        } else {
            rows.add(CapturedAssetRow.OneItem(array.get(0)))
            array.drop(1)
        }
    }
    return rows
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaSharedActivityView(items: SnapshotStateList<ProofableItem>, fileName: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(ASSETS_GUTTER_SIZE.dp)) {

        Text(
            text = pluralStringResource(
                id = R.plurals.you_shared_n_items,
                count = items.size,
                items.size
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.DarkGray,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val deletedItemsString = items.deletedItemsString()
        if (deletedItemsString != null) {
            Text(
                text = deletedItemsString,
                style = MaterialTheme.typography.bodySmall,
                color = Color.DarkGray,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        val validItems = items.withDeletedItemsRemoved().toList()
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            validItems.forEach { asset ->
                ProofableItemView(
                    item = asset,
                    corners = RectF(30f, 30f, 30f, 30f),
                    modifier = Modifier
                        .width(60.dp)
                        .height(60.dp)
                )
            }
        }
    }
}

@Composable
fun PublicKeySharedActivityView(key: String) {
    Text(
        text = stringResource(id = R.string.you_shared_your_public_key),
        style = MaterialTheme.typography.bodyLarge,
        color = Color.DarkGray
    )
}

@Composable
fun ActivityView(activity: Activity) {
    Row {
        when (activity.type) {
            is ActivityType.MediaCaptured -> MediaCapturedOrImportedActivityView(
                activity.type.items,
                true
            )

            is ActivityType.MediaImported -> MediaCapturedOrImportedActivityView(
                activity.type.items,
                false
            )

            is ActivityType.MediaShared -> MediaSharedActivityView(
                activity.type.items,
                fileName = activity.type.fileName
            )

            is ActivityType.PublicKeyShared -> PublicKeySharedActivityView(key = activity.type.key)
        }
    }
}

@Composable
fun ActivityDateView(date: Date, menu: (@Composable() (BoxScope.() -> Unit))? = null) {
    Row(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.6f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = date.displayFormatted(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp)
        )
        Spacer(modifier = Modifier.weight(1f))
        if (menu != null) {
            Box(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .fillMaxSize()
                    .wrapContentSize(Alignment.TopEnd),
                content = menu
            )
        }
    }
}

interface SelectionHandler {
    abstract fun onProofableItemClick(item: ProofableItem)
    abstract fun onProofableItemLongClick(item: ProofableItem)
    abstract fun isSelected(item: ProofableItem): Boolean
    abstract fun anySelected(): Boolean

    @Composable
    abstract fun selectedItems(): List<ProofableItem>
}

val LocalSelectionHandler =
    compositionLocalOf<SelectionHandler> { error("Selection handler not set") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivitiesView(onAnyItemSelected: ((Boolean) -> Unit)? = null) {
    var showSingleAssetView: ProofableItem? by remember { mutableStateOf(null) }

    val selectedAssets = remember {
        mutableStateListOf<String>()
    }
    val backPressedDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val selectionHandler = object : SelectionHandler {
        override fun onProofableItemClick(item: ProofableItem) {
            val uriString = item.uri.toString()
            if (selectedAssets.size > 0) {
                if (selectedAssets.contains(uriString)) {
                    selectedAssets.remove(uriString)
                } else {
                    selectedAssets.add(uriString)
                }

            } else {
                showSingleAssetView = item
            }


            onAnyItemSelected?.invoke(anySelected())
        }

        override fun onProofableItemLongClick(item: ProofableItem) {
            val uriString = item.uri.toString()
            if (!selectedAssets.contains(uriString)) {
                selectedAssets.add(uriString)
            }

            onAnyItemSelected?.invoke(anySelected())
        }

        override fun isSelected(item: ProofableItem): Boolean {
            return selectedAssets.contains(item.uri.toString())
        }

        override fun anySelected(): Boolean {
            return selectedAssets.size > 0
        }

        @Composable
        override fun selectedItems(): List<ProofableItem> {
            return Activities.selectedItems(
                context = LocalContext.current,
                selection = selectedAssets
            )
        }
    }
    CompositionLocalProvider(LocalSelectionHandler provides selectionHandler) {
        MaterialTheme() {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 56.dp)
                        .background(Color.White)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .padding(10.dp)
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(ASSETS_GUTTER_SIZE.dp)
                    ) {
                        item {
                            val context = LocalContext.current

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(id = R.string.title_activity),
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.weight(1.0f))
                                /**
                                IconButton(
                                modifier =
                                Modifier
                                .width(32.dp)
                                .height(32.dp),
                                onClick = {
                                (context as? ActivitiesViewDelegate)?.openCamera()
                                }) {
                                Icon(
                                painter = painterResource(id = R.drawable.ic_camera),
                                contentDescription = "Open camera"
                                )
                                }**/
                            }
                        }
                        Activities.activities.reversed().forEach { activity ->
                            stickyHeader {
                                ActivityDateView(
                                    date = activity.startTime,
                                    menu = activityMenu(activity)
                                )
                            }
                            item(key = activity.id) {
                                ActivityView(activity = activity)
                            }
                        }
                    }

                    if (selectedAssets.size > 0) {
                        // Selection footer
                        //
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(10.dp)
                        ) {

                            /**Text(
                                    text = pluralStringResource(
                                            id = R.plurals.n_items_selected,
                                            count = selectedAssets.size,
                                            selectedAssets.size
                                    ),
                                    color = Color.DarkGray,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold

                            )
                            Spacer(modifier = Modifier.weight(1.0f))
                             **/
                            val context = LocalContext.current
                            val selectedItems =
                                Activities.selectedItems(context = context, selectedAssets)
                            IconButton(

                                    modifier =
                                    Modifier
                                            .width(48.dp)
                                            .height(48.dp),
                                    onClick = {
                                        (context as? ActivitiesViewDelegate)?.shareItems(selectedItems, fileName = null, shareText = null)
                                        selectedAssets.clear()


                                    onAnyItemSelected?.invoke(false)
                                }) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share"
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(

                                    modifier =
                                    Modifier
                                            .width(48.dp)
                                            .height(48.dp),
                                    onClick = {
                                        selectedAssets.clear()


                                    onAnyItemSelected?.invoke(false)
                                }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel"
                                )
                            }
                        }
                    }
                }

                if (showSingleAssetView != null) {
                    SingleAssetViewWithToolbar(initialItem = showSingleAssetView!!) {
                        selectedAssets.clear()
                        showSingleAssetView = null
                    }
                }
            }
        }
    }
    val activity = LocalContext.current as android.app.Activity

    BackHandler {
        if (showSingleAssetView != null) {
            selectedAssets.clear()
            showSingleAssetView = null
        } else {
            // If there is no single asset view, then let the back press go through
            // When you hit back button and some items were selected, then clear the selection
            if (selectedAssets.size > 0) {
                selectedAssets.clear()
                onAnyItemSelected?.invoke(false)
            } else {
              //  backPressedDispatcher?.onBackPressed()
                activity.finish()

            }

        }
    }
}

@Composable
fun activityMenu(activity: Activity): (@Composable() (BoxScope.() -> Unit))? {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    return {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Activity action menu",
                tint = Color.Gray
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            when (activity.type) {
                is ActivityType.MediaCaptured -> {
                    val count = activity.type.items.withDeletedItemsRemoved().size
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = pluralStringResource(
                                    id = R.plurals.share_these_n_items,
                                    count = count,
                                    count
                                )
                            )
                        },
                        onClick = {
                            expanded = false
                            (context as? ActivitiesViewDelegate)?.shareItems(
                                activity.type.items,
                                fileName = null,
                                shareText = null
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = pluralStringResource(
                                    id = R.plurals.clear_these_n_items,
                                    count = count,
                                    count
                                )
                            )
                        },
                        onClick = {
                            expanded = false
                            (context as? ActivitiesViewDelegate)?.clearItems(
                                activity
                            )
                        }
                    )
                }

                is ActivityType.MediaImported -> {
                    val count = activity.type.items.withDeletedItemsRemoved().size
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = pluralStringResource(
                                    id = R.plurals.share_these_n_items,
                                    count = count,
                                    count
                                )
                            )
                        },
                        onClick = {
                            expanded = false
                            (context as? ActivitiesViewDelegate)?.shareItems(
                                activity.type.items,
                                fileName = null,
                                shareText = null
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = pluralStringResource(
                                    id = R.plurals.clear_these_n_items,
                                    count = count,
                                    count
                                )
                            )
                        },
                        onClick = {
                            expanded = false
                            (context as? ActivitiesViewDelegate)?.clearItems(
                                activity
                            )
                        }
                    )
                }

                is ActivityType.MediaShared -> {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(id = R.string.re_share)
                            )
                        },
                        onClick = {
                            expanded = false
                            (context as? ActivitiesViewDelegate)?.shareItems(
                                activity.type.items,
                                activity.type.fileName,
                                activity.type.shareText
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = pluralStringResource(
                                    id = R.plurals.clear_these_n_items,
                                    count = 1
                                )
                            )
                        },
                        onClick = {
                            expanded = false
                            (context as? ActivitiesViewDelegate)?.clearItems(
                                activity
                            )
                        }
                    )
                }

                is ActivityType.PublicKeyShared -> {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(id = R.string.re_share)
                            )
                        },
                        onClick = {
                            expanded = false
                            (context as? ActivitiesViewDelegate)?.sharePublicKey(activity.type.key)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = pluralStringResource(
                                    id = R.plurals.clear_these_n_items,
                                    count = 1
                                )
                            )
                        },
                        onClick = {
                            expanded = false
                            (context as? ActivitiesViewDelegate)?.clearItems(
                                activity
                            )
                        }
                    )

                }


                else -> {}
            }
        }
    }
}

@Preview
@Composable
fun ActivityViewPreview() {
    ActivitiesView()
}

@SuppressLint("SimpleDateFormat")
@Composable
fun Date.displayFormatted(): String {
    val formatter =
        if (DateUtils.isToday(this.time)) {
            SimpleDateFormat(stringResource(id = R.string.date_display_days_since_today))
        } else if (DateUtils.isToday(this.time + 24 * 60 * 60000)) {
            SimpleDateFormat(stringResource(id = R.string.date_display_days_since_yesterday))
        } else {
            SimpleDateFormat(stringResource(id = R.string.date_display_days_since_other))
        }
    return formatter.format(this)
}