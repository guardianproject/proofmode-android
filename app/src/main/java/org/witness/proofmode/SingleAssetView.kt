package org.witness.proofmode

import android.graphics.RectF
import android.net.Uri
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.lang.Float.max
import java.lang.Float.min
import java.text.SimpleDateFormat

val LocalShowMetadata = compositionLocalOf<Boolean> { error("Not set") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleAssetViewWithToolbar(initialItem: ProofableItem, onClose: () -> Unit) {

    var showMetadata by remember {
        mutableStateOf(false)
    }
    var title by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = title)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        onClose()
                    }) {
                        Icon(Icons.Filled.ArrowBack, "")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        showMetadata = !showMetadata
                    }) {
                        Icon(if (showMetadata) Icons.Filled.Info else Icons.Outlined.Info, "")
                    }
                }
            )
        }, content = { padding ->
            CompositionLocalProvider(LocalShowMetadata provides showMetadata) {
            SingleAssetView(
                initialItem = initialItem,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                setShowMetadata = { show -> showMetadata = show},
                setTitle = { newTitle -> title = newTitle }
            )
            }
        })
}

@Composable
fun SingleAssetView(initialItem: ProofableItem, modifier: Modifier = Modifier, setShowMetadata: (Boolean) -> Unit, setTitle: (String) -> Unit) {
    val context = LocalContext.current
    var dragOffset by remember {
        mutableStateOf(0f)
    }
    var topPartHeight by remember {
        mutableStateOf(0f)
    }
    val allAssets by remember {
        mutableStateOf(
            Activities.getAllCapturedAndImportedItems(context).reversed()
        )
    }
    val showMetadata = LocalShowMetadata.current
    val metadataOpacity: Float by animateFloatAsState(
        targetValue =
        if (dragOffset != 0f)
            max(min(1f, (if (showMetadata) 1f else 0f) - (dragOffset / (topPartHeight / 3))), 0f)
        else if (showMetadata)
            1f
        else
            0f, animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    val localDensity = LocalDensity.current
    val itemViewHeight = with(localDensity) {
        val dpHalfHeight = (0.5f * topPartHeight).toDp()
        dpHalfHeight.plus(
            dpHalfHeight
                .minus(64.dp)
                .times(1 - metadataOpacity)
        )
    }


    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier
            .weight(1f, true)
            .onGloballyPositioned { coordinates ->
                topPartHeight = coordinates.size.height.toFloat()
            }
        ) {
            BoxWithConstraints(modifier = Modifier
                .fillMaxWidth()
                .height(itemViewHeight)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        dragOffset += delta
                    },
                    onDragStopped = { _ ->
                        if (metadataOpacity > 0.2 && !showMetadata) {
                            setShowMetadata(true)
                        } else if (metadataOpacity < 0.8 && showMetadata) {
                            setShowMetadata(false)
                        }
                        dragOffset = 0f
                    }
                )
            ) {
                SingleAssetItemView(
                    width = maxWidth,
                    height = itemViewHeight,
                    allAssets = allAssets,
                    selectedAsset = initialItem,
                    setTitle = setTitle
                )
            }
            Box(
                modifier = Modifier
                    .alpha(1f - metadataOpacity)
                    .offset(y = (64 * metadataOpacity).dp)
                    .height(64.dp)
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
            }
            Box(
                modifier = Modifier
                    .alpha(metadataOpacity)
                    .height(with(localDensity) { (topPartHeight / 2).toDp() })
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Text("Metadata here")
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(10.dp)
        ) {
            val items =
                if (LocalSelectionHandler.current.anySelected()) LocalSelectionHandler.current.selectedItems() else listOf(
                    initialItem
                )
            IconButton(
                //enabled = LocalSelectionHandler.current.anySelected(),
                modifier =
                Modifier
                    .width(32.dp)
                    .height(32.dp),
                onClick = {
                    (context as? ActivitiesViewDelegate)?.shareItems(
                        items,
                        fileName = null,
                        shareText = null
                    )
                }) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share"
                )
            }
        }
    }
}

@Composable
fun SingleAssetItemView(width: Dp, height: Dp, allAssets: List<ProofableItem>, selectedAsset: ProofableItem, setTitle: (String) -> Unit) {
    var dragOffset by remember {
        mutableStateOf(0f)
    }
    var dragging by remember { mutableStateOf(false) }
    val animatedDragOffset: Float by animateFloatAsState(targetValue =
        if (dragging)
            dragOffset
        else 0f
    , animationSpec = tween(
            durationMillis = if (dragging) 0 else 500,
            easing = FastOutLinearInEasing
        )
    )

    val itemSizeMultiplier by animateFloatAsState(targetValue = if (LocalSelectionHandler.current.anySelected()) 0.7f else 1f)

    var selectedIndex by remember { mutableStateOf(allAssets.indexOfFirst { it.uri.toString() == selectedAsset.uri.toString() } .coerceAtLeast(0)) }
    val itemWidth = width.times(itemSizeMultiplier)

    val localDensity = LocalDensity.current
    val itemWidthPixels = with(localDensity) {
        itemWidth.toPx()
    }

    val numItems = 5
    val idxSelected: Int = ((numItems - 1) / 2).toInt()
    val items = (0 until numItems).map { Pair(it, it + selectedIndex - idxSelected) }

    if (selectedIndex >= 0 && selectedIndex < allAssets.size) {
        val formatter = SimpleDateFormat(stringResource(id = R.string.date_display_single_item))
        Activities.dateForItem(item = allAssets[selectedIndex], context = LocalContext.current) { date ->
            val dateFormatted = formatter.format(date)
            setTitle(dateFormatted)
        }
    }
    //val selectedItemOffset = (20.dp.plus(itemWidth).times(idxSelected)).minus( (width.minus(itemWidth).div(2)))
    Row(
        modifier = Modifier
            .height(height)
            .requiredWidth(
                4.dp
                    .plus(itemWidth)
                    .times(numItems)
                    .minus(4.dp)
            )
            .offset(x = with(localDensity) { if (dragging) dragOffset.toDp() else -animatedDragOffset.toDp() })
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    dragOffset += delta
                },
                onDragStarted = {
                    dragging = true
                    dragOffset = 0f } ,
                onDragStopped = { _ ->
                    if (dragOffset > 50 && selectedIndex > 0) {
                        selectedIndex -= 1
                        dragOffset -= itemWidthPixels
                    } else if (dragOffset < -50 && selectedIndex < (allAssets.size + 1)) {
                        selectedIndex += 1
                        dragOffset += itemWidthPixels
                    }
                    dragging = false
                }
            )
    , horizontalArrangement = Arrangement.spacedBy(4.dp)
    , verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { tuple ->
            val delta = idxSelected - tuple.first
            val idxThis = selectedIndex - delta
            if (idxThis >= 0 && idxThis < allAssets.size) {
                val item = allAssets[idxThis]
                Box(modifier = Modifier
                    .requiredWidth(itemWidth)
                    .fillMaxHeight()) {
                    ProofableItemView(item = item,
                        modifier = Modifier.fillMaxSize(),
                        contain = !LocalSelectionHandler.current.anySelected(),
                        corners = RectF(0f,0f,0f,0f),
                        showSelectionBorder = false
                    )
                    if (LocalSelectionHandler.current.isSelected(item)) {
                        Icon(imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selected",
                            tint = Color.Blue,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
                        )
                    }
                }
            } else {
                Box(modifier = Modifier
                    .width(itemWidth)
                    .height(if (width < height) width else height)
                )
            }
        }
    }
}

@Preview
@Composable
fun SingleAssetViewPreview() {
    SingleAssetView(initialItem = ProofableItem(id = "1", uri = Uri.parse("")), setShowMetadata = {}, setTitle = {} )
}
