package org.witness.proofmode.camera.fragments

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.VideoFrameDecoder
import coil.load
import coil.request.ImageRequest
import coil.size.Size
import org.witness.proofmode.camera.R
import org.witness.proofmode.camera.adapter.Media
import org.witness.proofmode.camera.utils.share

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPreview(viewModel: CameraViewModel, modifier: Modifier = Modifier,
                 onNavigateBack: (() -> Unit)? = null){
    val mediaItems by viewModel.mediaFiles.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = {
        mediaItems.size
    })
    val context = LocalContext.current
    var showDeleteDialog by remember {
        mutableStateOf(false)
    }

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
        },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = ColorPrimary,
                navigationIconContentColor = Color.White,
                titleContentColor = Color.White))
    }, bottomBar = {
        BottomAppBar(containerColor = MaterialTheme.colorScheme.onSurface) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = {
                    val currentItem = mediaItems.getOrNull(pagerState.currentPage)
                    currentItem?.let {
                        context.share(it)
                    }
                }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share media", tint = Color.White)
                }

                IconButton(onClick = {
                    showDeleteDialog = true

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

        if (showDeleteDialog) {
            DeleteMediaDialog(onDismiss = { showDeleteDialog = false }, onConfirmDelete = {
                viewModel.deleteMedia(mediaItems[pagerState.currentPage])
                showDeleteDialog = false
                onNavigateBack?.invoke()
            })
        }

    }

}


@Composable
fun ImagePreview(modifier: Modifier,media: Media){
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
fun DeleteMediaDialog(onDismiss: () -> Unit, onConfirmDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = {
            Text("Delete Media",style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Text(stringResource(R.string.delete_media_confirmation))
        },
        confirmButton = {
            TextButton(onClick = onConfirmDelete) {
                Text(text = stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
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

@Composable
fun ItemPreview(modifier: Modifier,uri: Uri?) {

}