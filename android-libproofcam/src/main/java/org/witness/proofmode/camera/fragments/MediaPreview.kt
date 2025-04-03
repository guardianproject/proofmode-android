package org.witness.proofmode.camera.fragments

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.VideoFrameDecoder
import coil.load
import coil.request.ImageRequest
import coil.size.Size
import org.witness.proofmode.camera.adapter.Media
import org.witness.proofmode.camera.utils.ThumbSize
import org.witness.proofmode.camera.utils.getVideoThumbnail

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPreview(viewModel: CameraViewModel, modifier: Modifier = Modifier,
                 onNavigateBack: (() -> Unit)? = null){
    val mediaItems by viewModel.mediaFiles.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = {
        mediaItems.size
    })

    BackHandler(enabled = onNavigateBack != null) {
        onNavigateBack?.invoke()

    }
    Scaffold(modifier = modifier.fillMaxSize(), topBar = {
        TopAppBar(title = {}, navigationIcon = {
            IconButton(onClick = {
                onNavigateBack?.invoke()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back")
            }
        }, modifier = Modifier.height(24.dp).background(Color.Transparent))
    }, bottomBar = {
        BottomAppBar(containerColor = MaterialTheme.colorScheme.onSurface) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Share, contentDescription = "Share media", tint = Color.White)
                }

                IconButton(onClick = {
                    //viewModel.deleteMedia()
                    if (mediaItems.isNotEmpty()){
                        val currentItem = mediaItems.getOrNull(pagerState.currentPage)
                        currentItem?.let {
                            viewModel.deleteMedia(it)
                        }
                    }

                }) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Share media", tint = Color.White)
                }
            }
        }
    }) {

        HorizontalPager(state = pagerState) { itemIdx->
            when(mediaItems[itemIdx].isVideo) {
                true -> SimpleVideoView(videoUri = mediaItems[itemIdx].uri, modifier = Modifier.fillMaxSize())
                false -> ImagePreview(modifier = Modifier.fillMaxSize(), media = mediaItems[itemIdx])
            }
            //ImagePreview(modifier = Modifier.fillMaxSize(), media = mediaItems[it])

            //ItemPreview(media = mediaItems[it], modifier = Modifier.fillMaxSize())
        }

    }

}


@Composable
fun ImagePreview(modifier: Modifier,media: Media, onPlayClick: (() -> Unit)? = null){
    Box(modifier = modifier,
        contentAlignment = Alignment.Center){
        AndroidView(factory = { context->

            val imageView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
                //setImageBitmap(getVideoThumbnail(context = context, videoUri = mediaItems[it].uri, size = ThumbSize.LARGE))
            }
            imageView.load(media.uri) {
                if (media.isVideo) {
                    decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
                }
            }
            imageView

        }, modifier = Modifier.fillMaxSize())
        if (media.isVideo) {
            Icon(imageVector = Icons.Filled.PlayCircle, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp)
                .clickable(enabled = onPlayClick != null){
                    onPlayClick?.invoke()
                }
            )
        }
    }

}


@Composable
fun SimpleVideoView(videoUri:Uri,modifier: Modifier = Modifier) {
    AndroidView(modifier = modifier, factory = {context->
        VideoView(context).also { videoView->
            val mediaController = MediaController(context).also{ controller->
                controller.setAnchorView(videoView)
            }
            videoView.apply {
                setMediaController(mediaController)
                setVideoURI(videoUri)
                setOnPreparedListener { mediaPlayer->
                    mediaPlayer.isLooping = false
                }
                start()
            }

        }


    })
}

@Composable
fun ItemPreview(modifier: Modifier = Modifier, media: Media){
    val context = LocalContext.current
    Box(modifier = modifier, contentAlignment = Alignment.Center){
        val painter = rememberAsyncImagePainter(
            model = ImageRequest.Builder(context)
                .data(media.uri).apply {
                    if (media.isVideo) {
                        decoderFactory { result, options, _ -> VideoFrameDecoder(result.source, options) }
                    }
                    size(Size.ORIGINAL)
                }.build()
        )

        Image(painter = painter, contentDescription = null,
            contentScale = ContentScale.Fit,)
        if (painter.state is AsyncImagePainter.State.Loading) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp),
                strokeWidth = 2.dp)
        }
    }
}