package org.witness.proofmode

import android.annotation.SuppressLint
import android.graphics.RectF
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.res.painterResource
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
    abstract fun shareItems(media: List<Uri>)
}

sealed class CapturedAssetRow {
    class OneItem(val item: CameraItem) : CapturedAssetRow()
    class TwoItems(val items: List<CameraItem>) : CapturedAssetRow()
    class ThreeItems(val items: List<CameraItem>) : CapturedAssetRow()
    class FourItems(val items: List<CameraItem>) : CapturedAssetRow()
}

@Composable
fun AssetView(asset: CameraItem, modifier: Modifier = Modifier, contain: Boolean = false, corners: RectF = RectF(
    ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS)
) {

        AsyncImage(
            model = if (asset.uri != null) asset.uri.toString() else "",
            contentDescription = "Asset view",
            alignment = Alignment.Center,
            contentScale = if (contain) ContentScale.Fit else ContentScale.Crop,
            modifier = modifier
                .clip(
                    RoundedCornerShape(
                        corners.left.dp,
                        corners.top.dp,
                        corners.right.dp,
                        corners.bottom.dp
                    )

                )
                .background(ASSETS_BACKGROUND)
        )
}

// Custom extension
fun Constraints.exact(width: Int, height: Int): Constraints {
    return this.copy(minWidth = width, maxWidth = width, minHeight = height, maxHeight = height)
}

@Composable
fun OneItemAssetRowView(asset: CameraItem) {
    AssetView(asset = asset, modifier = Modifier.aspectRatio(ratio = 16 / 9f))
}

@Composable
fun TwoItemsAssetRowView(assets: List<CameraItem>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.SpaceBetween) {
        AssetView(
            asset = assets[0],
            modifier = Modifier
                .weight(0.5F)
                .aspectRatio(ratio = 1f),
        )
        Spacer(modifier = Modifier
            .width(ASSETS_GUTTER_SIZE.dp)
            .fillMaxHeight())
        AssetView(
            asset = assets[1],
            modifier = Modifier
                .weight(0.5F)
                .aspectRatio(ratio = 1f),
        )
    }
}

@Composable
fun ThreeItemsAssetRowView(assets: List<CameraItem>) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            AssetView(asset = assets[0])
            AssetView(asset = assets[1])
            AssetView(asset = assets[2])
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
fun FourItemsAssetRowView(assets: List<CameraItem>) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            AssetView(asset = assets[0])
            AssetView(asset = assets[1], corners = RectF(ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, 0f, 0f))
            AssetView(asset = assets[2], corners = RectF(0f, 0f, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS))
            AssetView(asset = assets[3])
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
fun MediaCapturedOrImportedActivityView(items: SnapshotStateList<CameraItem>, capturedItems: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(ASSETS_GUTTER_SIZE.dp)) {

        Text(
            text = pluralStringResource(id = R.plurals.you_captured_n_items, count = items.size, items.size),
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
fun SnapshotStateList<CameraItem>.deletedItemsString(): String? {
    val countDeleted = this.filter { it.isDeleted(LocalContext.current)} .size
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
fun layoutRows(items: SnapshotStateList<CameraItem>): MutableList<CapturedAssetRow> {
    var array = items.withDeletedItemsRemoved().toList()
    val rows: MutableList<CapturedAssetRow> = mutableListOf()
    while (array.isNotEmpty()) {
        array = if (array.size >= 7) {
            rows.add(CapturedAssetRow.ThreeItems(array.slice(IntRange(0, 2))))
            rows.add(CapturedAssetRow.FourItems(array.slice(IntRange(3,6))))
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

@Composable
fun ActivityView(activity: Activity) {
    Row {
        when (activity.type) {
            is ActivityType.MediaCaptured -> MediaCapturedOrImportedActivityView(activity.type.items, false)
            is ActivityType.MediaImported -> Text("Media Imported")
            is ActivityType.MediaShared -> Text("Media Shared")
            is ActivityType.PublicKeyShared -> Text("Public Key Shared")
        }
    }
}
@Composable
fun ActivityDateView(date: Date, menu: (@Composable() (BoxScope.() -> Unit))? = null) {
    Row(modifier = Modifier
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivitiesView() {
    MaterialTheme() {
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(ASSETS_GUTTER_SIZE.dp)) {
            item {
                val context = LocalContext.current

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Activities", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1.0f))
                    IconButton(
                        modifier =
                        Modifier
                            .width(32.dp)
                            .height(32.dp),
                        onClick = {
                            (context as? ActivitiesViewDelegate)?.openCamera()
                        }) {
                        Icon(painter = painterResource(id = R.drawable.ic_camera), contentDescription = "Open camera")
                    }
                }
            }
            Activities.activities.reversed().forEach { activity ->
                stickyHeader {
                    ActivityDateView(date = activity.startTime, menu = activityMenu(activity))
                }
                item(key = activity.id) {
                    ActivityView(activity = activity)
                }
            }
        }
    }
    }
}

@Composable
fun activityMenu(activity: Activity):  (@Composable() (BoxScope.() -> Unit))? {
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
                            (context as? ActivitiesViewDelegate)?.shareItems(activity.type.items.map { it.uri }.filterNotNull())
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