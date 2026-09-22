package org.witness.proofmode.ui.media

import android.content.ContentResolver
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import org.witness.proofmode.MediaType
import org.witness.proofmode.R
import org.witness.proofmode.getMediaTypeFromFileUri
import org.witness.proofmode.ui.ASSETS_CORNER_RADIUS
import org.witness.proofmode.ui.ProofableItem

internal fun isVideoItem(uri: Uri, contentResolver: ContentResolver): Boolean {
    return if (uri.scheme == "content") {
        contentResolver.getType(uri)?.contains("video") ?: false
    } else {
        getMediaTypeFromFileUri(uri) == MediaType.VIDEO
    }
}

@Composable
internal fun BoxScope.ProofItemMedia(
    item: ProofableItem,
    modifier: Modifier = Modifier,
    contain: Boolean = false,
    corners: RectF = RectF(
        ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS, ASSETS_CORNER_RADIUS
    ),
    zoomable: Boolean = false,
) {
    val context = LocalContext.current
    val isVideo = remember(item) {
        isVideoItem(item.uri, context.contentResolver)
    }

    val imageModifier = Modifier
        .clip(
            RoundedCornerShape(
                corners.left.dp,
                corners.top.dp,
                corners.right.dp,
                corners.bottom.dp
            )
        )
        //.background(ASSETS_BACKGROUND)
        .then(modifier)

    val assetImage: @Composable (Modifier) -> Unit = { imgMod ->
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.uri).apply {
                    if (isVideo) {
                        decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
                    }
                }.build(),
            contentDescription = "Asset view",
            alignment = Alignment.Center,
            contentScale = if (contain) ContentScale.Fit else ContentScale.Crop,
            modifier = imgMod
        )
    }

    if (zoomable) {
        ZoomableBox(modifier = imageModifier) {
            assetImage(Modifier.fillMaxSize())
        }
    } else {
        assetImage(imageModifier)
    }

    if (isVideo) {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.videocam),
            contentDescription = "Video",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(2.dp)
                .background(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(2.dp)
                )
                .padding(1.dp)
                .size(24.dp)
        )
    }
}
