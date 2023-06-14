package org.witness.proofmode

import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.modifier.modifierLocalConsumer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import java.util.Date

const val ASSETS_GUTTER_SIZE = 10F
const val ASSETS_CORNER_RADIUS = 20F
val ASSETS_BACKGROUND = Color.Black.copy(0.1F)

sealed class CapturedAssetRow {
    class OneItem(val item: CameraItem) : CapturedAssetRow()
    class TwoItems(val items: Array<CameraItem>) : CapturedAssetRow()
    class ThreeItems(val items: Array<CameraItem>) : CapturedAssetRow()
    class FourItems(val items: Array<CameraItem>) : CapturedAssetRow()
}

@Composable
fun AssetView(asset: CameraItem, modifier: Modifier = Modifier, contain: Boolean = false, corners: RectF = RectF(
    ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS)
) {
    Image(
        painter = painterResource(id = R.drawable.ic_img_home_off),
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
fun TwoItemsAssetRowView(assets: Array<CameraItem>) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.SpaceBetween) {
        AssetView(asset = assets[0],
            modifier = Modifier
                .weight(0.5F)
                .aspectRatio(ratio = 1f),
        )
        Spacer(modifier = Modifier
            .width(ASSETS_GUTTER_SIZE.dp)
            .fillMaxHeight())
        AssetView(asset = assets[1],
            modifier = Modifier
                .weight(0.5F)
                .aspectRatio(ratio = 1f),
        )
    }
}

@Composable
fun ThreeItemsAssetRowView(assets: Array<CameraItem>) {
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
fun FourItemsAssetRowView(assets: Array<CameraItem>) {
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
fun MediaCapturedOrImportedActivityView(items: Array<CameraItem>, capturedItems: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(ASSETS_GUTTER_SIZE.dp)) {
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
//    @Environment(\.isPreview) var isPreview
//
//    @State var proxy:GeometryProxy
//    @State var items: [CameraItem]
//    @State var capturedItems: Bool = true
//
//    var body: some View {
//        VStack(spacing: 0) {
//        Text(capturedItems ? "You captured \(items.count) items" : "You imported \(items.count) items")
//        .font(.custom(Font.bodyFont, size: 15))
//        .fontWeight(.regular)
//        .foregroundColor(Color("colorDarkGray"))
//        .multilineTextAlignment(.leading)
//        .frame(maxWidth: .infinity, alignment: .leading)
//        .padding(EdgeInsets(top: 0, leading: 0, bottom: 8, trailing: 0))
//
//        if let deletedItemsString = items.deletedItemsString() {
//            Text(deletedItemsString)
//                .font(.custom(Font.bodyFont, size: 12))
//            .fontWeight(.regular)
//            .foregroundColor(Color("colorDarkGray"))
//            .multilineTextAlignment(.leading)
//            .frame(maxWidth: .infinity, alignment: .leading)
//            .padding(EdgeInsets(top: 0, leading: 0, bottom: 8, trailing: 0))
//        }
//
//        VStack(spacing: assetsGutterSize) {
//            let rows = layoutRows()
//            ForEach((0..<rows.count), id: \.self) { index in
//                switch rows[index] {
//            case .oneItem(let asset):
//            OneItemAssetRowView(asset: asset, proxy: proxy)
//            case .twoItems(let assets):
//            TwoItemsAssetRowView(assets: assets, proxy: proxy)
//            case .threeItems(let assets):
//            ThreeItemsAssetRowView(assets: assets, proxy: proxy)
//            case .fourItems(let assets):
//            FourItemsAssetRowView(assets: assets, proxy: proxy)
//        }
//        }
//        }
//    }
//        .onDisappear {
//            print("RELEASE ALL IMAGES!!!")
//        }
//
//    }
//
//    func layoutRows() -> [CapturedAssetRow] {
//        var array = Array(self.items).withDeletedItemsRemoved()
//
//        var rows: [CapturedAssetRow] = []
//        while (array.count > 0) {
//            if array.count >= 7 {
//                rows.append(.threeItems(assets: [] + array[0...2]))
//                rows.append(.fourItems(assets: [] + array[3...6]))
//                array = [] + array.dropFirst(7)
//            } else if array.count >= 3 {
//                rows.append(.threeItems(assets: [] + array[0...2]))
//                array = [] + array.dropFirst(3)
//            } else if array.count >= 2 {
//                rows.append(.twoItems(assets: [] + array[0...1]))
//                array = [] + array.dropFirst(2)
//            } else {
//                rows.append(.oneItem(asset: array[0]))
//                array = [] + array.dropFirst(1)
//            }
//        }
//        return rows
//    }
}

fun Array<CameraItem>.withDeletedItemsRemoved(): Array<CameraItem> {
    return this // TODO
}

fun layoutRows(items: Array<CameraItem>): MutableList<CapturedAssetRow> {
    var array = items.withDeletedItemsRemoved()
    val rows: MutableList<CapturedAssetRow> = mutableListOf()
    while (array.isNotEmpty()) {
        if (array.size >= 7) {
            rows.add(CapturedAssetRow.ThreeItems(array.sliceArray(IntRange(0, 2))))
            rows.add(CapturedAssetRow.FourItems(array.sliceArray(IntRange(3,6))))
            array = array.drop(7).toTypedArray()
        } else if (array.size >= 3) {
            rows.add(CapturedAssetRow.ThreeItems(array.sliceArray(IntRange(0, 2))))
            array = array.drop(3).toTypedArray()
        } else if (array.size >= 2) {
            rows.add(CapturedAssetRow.TwoItems(array.sliceArray(IntRange(0, 1))))
            array = array.drop(2).toTypedArray()
        } else {
            rows.add(CapturedAssetRow.OneItem(array.get(0)))
            array = array.drop(1).toTypedArray()
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
//Text(text = "Size ${activity.items.size}")
}

@Composable
fun ActivitiesView(onShowCamera: (() -> Unit)? = null) {
    MaterialTheme() {
    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(ASSETS_GUTTER_SIZE.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Activities", style = MaterialTheme.typography.headlineLarge)
                    Spacer(modifier = Modifier.weight(1.0f))
                    IconButton(
                        modifier =
                        Modifier
                            .width(32.dp)
                            .height(32.dp),
                        onClick = {
                            if (onShowCamera != null) {
                                onShowCamera()
                            }
                        }) {
                        Icon(painter = painterResource(id = R.drawable.ic_camera), contentDescription = "hej")
                    }
                }
            }
            items(Activities.activities) { activity ->
                ActivityView(activity = activity)
            }
        }
    }
    }
}

@Preview
@Composable
fun ActivityViewPreview() {
    ActivitiesView()
}