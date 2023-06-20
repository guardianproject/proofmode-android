package org.witness.proofmode

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import java.lang.Float.max
import java.lang.Float.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleAssetViewWithToolbar(onClose: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "")
                },
                navigationIcon = {
                    IconButton(onClick = {
                        onClose()
                    }) {
                        Icon(Icons.Filled.ArrowBack, "")
                    }
                },
            )
        }, content = { padding ->
            SingleAssetView(modifier = Modifier.fillMaxSize().padding(padding))
        })
}

@Composable
fun SingleAssetView(modifier: Modifier = Modifier) {
    var dragOffset by remember {
        mutableStateOf(0f)
    }
    var topPartHeight by remember {
        mutableStateOf(0f)
    }
    var showMetadata by remember {
        mutableStateOf(false)
    }
    val metadataOpacity: Float by animateFloatAsState(targetValue =
        if (dragOffset != 0f)
            max(min(1f, (if (showMetadata) 1f else 0f) - (dragOffset / (topPartHeight / 3))), 0f)
        else if (showMetadata)
            1f
        else
            0f
        , animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    val localDensity = LocalDensity.current

    MaterialTheme() {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier
                    .weight(1f, true)
                    .background(Color.Red)
                    .onGloballyPositioned { coordinates ->
                        topPartHeight = coordinates.size.height.toFloat()
                    }
                ) {
                    BoxWithConstraints(modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            with(localDensity) {
                                val dpHalfHeight = (0.5f * topPartHeight).toDp()
                                dpHalfHeight.plus(dpHalfHeight.minus (64.dp).times(1 - metadataOpacity))
                            }
                        )
                        .background(Color.Blue)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                dragOffset += delta
                            },
                            onDragStopped = { _ ->
                                if (metadataOpacity > 0.2 && !showMetadata) {
                                    showMetadata = true
                                } else if (metadataOpacity < 0.8 && showMetadata) {
                                    showMetadata = false
                                }
                                dragOffset = 0f
                            }
                        )
                    ) {
                    }
                    Box(modifier = Modifier
                        .alpha(1f - metadataOpacity)
                        .offset(y = (64 * metadataOpacity).dp)
                        .height(64.dp)
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Green)) {
                    }
                    Box(modifier = Modifier
                        .alpha(metadataOpacity)
                        .height(with(localDensity) { (topPartHeight / 2).toDp() })
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Yellow)) {
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(10.dp)
                ) {
                    IconButton(
                        modifier =
                        Modifier
                            .width(32.dp)
                            .height(32.dp),
                        onClick = {
                        }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share"
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun SingleAssetViewPreview() {
    SingleAssetView()
}
