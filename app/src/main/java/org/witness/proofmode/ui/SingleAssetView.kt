package org.witness.proofmode.org.witness.proofmode.ui

import android.content.Context
import android.graphics.RectF
import android.location.Location
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.witness.proofmode.R
import org.witness.proofmode.service.ProofModeV1Constants
import org.witness.proofmode.storage.DefaultStorageProvider
import org.witness.proofmode.util.ProofModeUtil
import timber.log.Timber
import java.io.FileNotFoundException
import java.lang.Float.max
import java.lang.Float.min
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale


val LocalShowMetadata = compositionLocalOf<Boolean> { true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleAssetViewWithToolbar(initialItem: ProofableItem, onClose: () -> Unit) {

    var showMetadata by remember {
        mutableStateOf(true)
    }

    var title by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = title, color = Color.White)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onSecondary
                ),
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
                setTitle = { newTitle -> title = newTitle },

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
    var topPartWidth by remember {
        mutableStateOf(0f)
    }

    val allAssets = ArrayList<ProofableItem>()
    allAssets.add(initialItem)

    /***
    var relatedItems = Activities.getRelatedProofableItems(context,initialItem.id)
    for (relatedItem in relatedItems)
        allAssets.add(relatedItem)


    var selectedIndex by remember { mutableStateOf(allAssets.indexOfFirst { it.uri.toString() == initialItem.uri.toString() } .coerceAtLeast(0)) }
    **/


    var selectedIndex = 0

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
    val coroutineScope = rememberCoroutineScope()
    val previewItemCenterOffset = with(localDensity) {
        -((topPartWidth / 2).toInt() - 28.dp.roundToPx())
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex, initialFirstVisibleItemScrollOffset = previewItemCenterOffset)

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier
            .weight(1f, true)
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                topPartHeight = coordinates.size.height.toFloat()
                topPartWidth = coordinates.size.width.toFloat()
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
                    selectedIndex = selectedIndex,
                    selectIndex = { idx ->
                        selectedIndex = idx
                        coroutineScope.launch {
                            listState.scrollToItem(selectedIndex, previewItemCenterOffset)
                        }
                    },
                    setTitle = setTitle
                )
            }
            /**
            Box(
                modifier = Modifier
                    .alpha(1f - metadataOpacity)
                    .offset(y = (64 * metadataOpacity).dp)
                    .height(64.dp)
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                PreviewsView(allAssets = allAssets, listState = listState, selectedIndex = selectedIndex, selectIndex = { index ->
                    selectedIndex = index
                    coroutineScope.launch {
                        listState.scrollToItem(selectedIndex, previewItemCenterOffset)
                    }
                })
            }**/
            Column(
                modifier = Modifier
                    .alpha(metadataOpacity)
                    .height(with(localDensity) { (topPartHeight / 2).toDp() })
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(6.dp, 6.dp)
                    .background(Color.White)
                    .verticalScroll(rememberScrollState())
            ) {

                updateMetadata(initialItem.uri,context)

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
fun updateMetadata (itemUri : Uri, context : Context) {
    var hash = try {
        ProofModeUtil.getProofHash(itemUri,context)
    } catch (ex: FileNotFoundException) {
        Timber.e("FIle with uri ${itemUri.path} not found", ex)
        null
    } catch (ex: Exception) {
        Timber.e("Error getting hash for uri ${itemUri.path}", ex)
        null
    }
    if (hash != null) {

        val dfParse: DateFormat = SimpleDateFormat(
            ProofModeV1Constants.ISO_DATE_TIME_FORMAT,
            Locale.US
        ) // Quoted "Z" to indicate UTC, no timezone offset


        var df = SimpleDateFormat.getDateTimeInstance()

        //ProofMode app uses default storage provider
        var storageProvider = DefaultStorageProvider(context)
        var hmap: HashMap<String, String>? =
            ProofModeUtil.getProofHashMap(storageProvider, hash)

        if (hmap?.contains(ProofModeV1Constants.PROOF_GENERATED) == true)
            addRow(
                ProofModeV1Constants.PROOF_GENERATED,
                df.format(dfParse.parse(hmap?.get(ProofModeV1Constants.PROOF_GENERATED)))
            )


        addRow(ProofModeV1Constants.FILE_HASH_SHA_256, hmap)
        if (hmap?.contains(ProofModeV1Constants.FILE_CREATED) == true)
            addRow(
                ProofModeV1Constants.FILE_CREATED,
                df.format(dfParse.parse(hmap?.get(ProofModeV1Constants.FILE_CREATED)))
            )
        if (hmap?.contains(ProofModeV1Constants.FILE_MODIFIED) == true)
            addRow(
                ProofModeV1Constants.FILE_MODIFIED,
                df.format(dfParse.parse(hmap?.get(ProofModeV1Constants.FILE_MODIFIED)))
            )


        if (hmap?.contains(ProofModeV1Constants.LOCATION_LATITUDE) == true) {
            var lat = hmap?.get(ProofModeV1Constants.LOCATION_LATITUDE)?.toDouble()
            var loc = hmap?.get(ProofModeV1Constants.LOCATION_LONGITUDE)?.toDouble()

            lat?.let {
                if (loc != null) {
                    convertDegMinsSecs(lat, loc)?.let { it1 -> addRow("Location", it1) }
                }
            }
        }

        if (hmap?.contains(ProofModeV1Constants.HARDWARE) == true)
            addRow (ProofModeV1Constants.HARDWARE, hmap)

        if (hmap?.contains(ProofModeV1Constants.FILE_PATH) == true)
            addRow (ProofModeV1Constants.FILE_PATH, hmap)


    }
}
@Composable
fun addRow (key : String,  hmap: HashMap<String,String>?) {

    hmap?.get(key)?.let { it1 ->
        Row {
            Text(modifier = Modifier.padding(3.dp, 3.dp), text = "$key", fontWeight = FontWeight.Bold)
        }
        Row {
            SelectionContainer() { Text(modifier = Modifier.padding(3.dp, 3.dp), text = "${it1}") }
        }
        Row {
            Text(modifier = Modifier.padding(2.dp, 2.dp), text = "")

        }
    }


}

private fun convertDegMinsSecs(latitude: Double, longitude: Double): String? {
    val builder = StringBuilder()
    val latitudeDegrees = Location.convert(Math.abs(latitude), Location.FORMAT_SECONDS)
    val latitudeSplit = latitudeDegrees.split(":".toRegex()).dropLastWhile { it.isEmpty() }
        .toTypedArray()
    builder.append(latitudeSplit[0])
    builder.append("°")
    builder.append(latitudeSplit[1])
    builder.append("'")
    builder.append(latitudeSplit[2])
    builder.append("\"")
    if (latitude < 0) {
        builder.append("S ")
    } else {
        builder.append("N ")
    }
    builder.append("  ")
    val longitudeDegrees = Location.convert(Math.abs(longitude), Location.FORMAT_SECONDS)
    val longitudeSplit = longitudeDegrees.split(":".toRegex()).dropLastWhile { it.isEmpty() }
        .toTypedArray()
    builder.append(longitudeSplit[0])
    builder.append("°")
    builder.append(longitudeSplit[1])
    builder.append("'")
    builder.append(longitudeSplit[2])
    builder.append("\"")
    if (longitude < 0) {
        builder.append("W ")
    } else {
        builder.append("E ")
    }
    return builder.toString()
}

@Composable
fun addRow (key : String,  value: String) {

    Row {
        Text(modifier = Modifier.padding(3.dp, 3.dp), text = "$key", fontWeight = FontWeight.Bold)

    }
    Row {
        SelectionContainer() { Text(modifier = Modifier.padding(3.dp, 3.dp), text = "${value}") }
    }
    Row {
        Text(modifier = Modifier.padding(3.dp, 3.dp), text = "")

    }


}

@Composable
fun SingleItemView(itemWidth: Dp, allAssets: List<ProofableItem>, index: Int, selectedIndex: Int) {
    val context = LocalContext.current
    if (index >= 0 && index < allAssets.size) {
        val item = allAssets[index]
        Box(
            modifier = Modifier
                .requiredWidth(itemWidth)
                .fillMaxHeight()
        ) {

            val isVideo = remember(item) {
                item.uri.let {uri->
                    context.contentResolver.getType(uri)?.contains("video") ?:false
                }
            }
            if (isVideo) {
                VideoItemView(item = item,modifier=Modifier.fillMaxSize())
            }
            else {

                ProofableItemView(
                        item = item,
                        modifier = Modifier.fillMaxSize(),
                        contain = !LocalSelectionHandler.current.anySelected(),
                        corners = RectF(0f, 0f, 0f, 0f),
                        showSelectionBorder = false
                )
                if (LocalSelectionHandler.current.isSelected(item)) {
                    Box(modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)) {
                        Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = Color(0.3f, 0.6f, 1.0f),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .width(16.dp)
                                    .height(16.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                        )
                    }
                }

            }
        }
    } else {
        Box(
            modifier = Modifier
                .width(itemWidth)
        )
    }
}

@Composable
fun SingleAssetItemView(width: Dp, height: Dp, allAssets: List<ProofableItem>, selectedIndex: Int, selectIndex: (Int) -> Unit, setTitle: (String) -> Unit) {
    var dragOffset by remember {
        mutableStateOf(0f)
    }
    var dragging by remember { mutableStateOf(false) }
    var animateFromItem by remember { mutableStateOf(selectedIndex.toFloat()) }
    val animatedOffset: Float by animateFloatAsState(targetValue =
        if (!dragging && animateFromItem != selectedIndex.toFloat()) selectedIndex.toFloat()
        else animateFromItem
    , animationSpec = tween(
            durationMillis = if (!dragging && animateFromItem != selectedIndex.toFloat()) 300 else 0,
            easing = FastOutSlowInEasing
        )
    )
    val localDensity = LocalDensity.current
    val itemSizeMultiplier by animateFloatAsState(targetValue = if (LocalSelectionHandler.current.anySelected()) 0.7f else 1f)
    val itemWidth = width.times(itemSizeMultiplier)

    if (selectedIndex >= 0 && selectedIndex < allAssets.size) {
        val formatter = SimpleDateFormat(stringResource(id = R.string.date_display_single_item))
        Activities.dateForItem(item = allAssets[selectedIndex], context = LocalContext.current) { date ->
            val dateFormatted = formatter.format(date)
            setTitle(dateFormatted)
        }
    }
    Row(
        modifier = Modifier
            .height(height)
            .requiredWidth(
                10.dp
                    .plus(itemWidth)
                    .times(5)
                    .minus(10.dp)
            )
            .offset(
                x = itemWidth
                    .plus(10.dp)
                    .times(selectedIndex.toFloat() - animatedOffset)
            )
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    dragOffset += delta
                    animateFromItem = selectedIndex.toFloat() - dragOffset / with(localDensity) {
                        itemWidth
                            .plus(10.dp)
                            .toPx()
                    }
                },
                onDragStarted = {
                    dragOffset = 0f
                    dragging = true
                },
                onDragStopped = { _ ->
                    if (dragOffset > 50 && selectedIndex > 0) {
                        animateFromItem =
                            selectedIndex.toFloat() - dragOffset / with(localDensity) {
                                itemWidth
                                    .plus(10.dp)
                                    .toPx()
                            }
                        selectIndex(selectedIndex - 1)
                    } else if (dragOffset < -50 && selectedIndex < (allAssets.size - 1)) {
                        animateFromItem =
                            selectedIndex.toFloat() - dragOffset / with(localDensity) {
                                itemWidth
                                    .plus(10.dp)
                                    .toPx()
                            }
                        selectIndex(selectedIndex + 1)
                    }
                    dragOffset = 0f
                    dragging = false
                }
            )
    , horizontalArrangement = Arrangement.spacedBy(10.dp)
    , verticalAlignment = Alignment.CenterVertically
    ) {
        SingleItemView(itemWidth = itemWidth, allAssets = allAssets, index = selectedIndex - 2, selectedIndex = selectedIndex)
        SingleItemView(itemWidth = itemWidth, allAssets = allAssets, index = selectedIndex - 1, selectedIndex = selectedIndex)
        SingleItemView(itemWidth = itemWidth, allAssets = allAssets, index = selectedIndex, selectedIndex = selectedIndex)
        SingleItemView(itemWidth = itemWidth, allAssets = allAssets, index = selectedIndex + 1, selectedIndex = selectedIndex)
        SingleItemView(itemWidth = itemWidth, allAssets = allAssets, index = selectedIndex + 2, selectedIndex = selectedIndex)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreviewsView(allAssets: List<ProofableItem>, listState: LazyListState, selectedIndex: Int, selectIndex: (Int) -> Unit) {
    LazyRow (
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp))
        //.horizontalScroll(scrollState))
    {
        allAssets.forEachIndexed { index, item ->
            item {
                val isSelected = selectedIndex == index
                ProofableItemView(
                    item = item,
                    contain = false,
                    showSelectionBorder = false,
                    corners = RectF(0f, 0f, 0f, 0f),
                    modifier = Modifier
                        .width(if (isSelected) 56.dp else 40.dp)
                        .combinedClickable(
                            onClick = {
                                selectIndex(index)
                            },
                            onLongClick = {
                                // Ignore, but override default handling in ProofableItemView
                            }
                        )
                        .padding(
                            start = if (isSelected) 8.dp else 0.dp,
                            end = if (isSelected) 8.dp else 0.dp
                        )
                        .background(Color.Black)
                )
            }
        }
    }
}

/**
 * A view for displaying a video
 */
@Composable
fun VideoItemView(item: ProofableItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val videoView = remember {
        VideoView(context)
    }
    val mediaController = remember {
        MediaController(context)
    }

    LaunchedEffect(key1 = item.uri) {
        videoView.apply {
            setMediaController(mediaController)
            mediaController.setAnchorView(this)
            setVideoURI(item.uri)
            post {
                seekTo(100)
            }
            //start()
        }
    }

    DisposableEffect(key1 = videoView) {
        onDispose {
            if (videoView.isPlaying) {
                videoView.stopPlayback()
            }
            mediaController.hide()
        }
    }

    AndroidView(
            factory = { videoView },
            modifier = modifier
    )
}

@Preview
@Composable
fun SingleAssetViewPreview() {
    SingleAssetView(initialItem = ProofableItem(id = "1", uri = Uri.parse("")), setShowMetadata = {}, setTitle = {} )
}
