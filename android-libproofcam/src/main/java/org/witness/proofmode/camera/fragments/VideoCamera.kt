package org.witness.proofmode.camera.fragments

import androidx.activity.compose.BackHandler
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.VideoSettings
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.takeOrElse
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.witness.proofmode.camera.R
import org.witness.proofmode.camera.utils.getName
import org.witness.proofmode.camera.utils.toIconRes
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoCamera(modifier: Modifier = Modifier,cameraViewModel: CameraViewModel = viewModel(),
                lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
                onNavigateToPhotoCamera: () -> Unit,
                onNavigateBack: (() -> Unit)? = null,
                onNavigateToPreview: () -> Unit,
                onClose:()-> Unit = {}) {
    val surfaceRequest by cameraViewModel.surfaceRequest.collectAsStateWithLifecycle()
    var showGridLines:Boolean by remember {
        mutableStateOf(false)
    }
    val recordingState by cameraViewModel.recordingState.collectAsStateWithLifecycle()
    val torchOn by cameraViewModel.torchOn.collectAsStateWithLifecycle()
    val settingsSheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var showBSettingsBottomSheet by remember { mutableStateOf(false) }
    val cameraQualities by cameraViewModel.cameraQualities.collectAsStateWithLifecycle()
    val selectedQuality by cameraViewModel.selectedQuality.collectAsStateWithLifecycle()
    val previewAlpha by cameraViewModel.previewAlpha.collectAsStateWithLifecycle()
   var zoom by remember { mutableStateOf(1f) }

    val ranges by cameraViewModel.supportedFrameRates.collectAsStateWithLifecycle()

    var autofocusRequest by remember {
        mutableStateOf(UUID.randomUUID() to Offset.Unspecified)
    }
    val autoRequestId = autofocusRequest.first
    // Show autofocus indicator only if the offset is specified
    val showAutoFocusIndicator = autofocusRequest.second.isSpecified

    // Ensure that initial coordinates for each auto focus request are cached
    val autofocusCoordinates = remember(autoRequestId) {
        autofocusRequest.second
    }
    val thumbPreviewUri by cameraViewModel.thumbPreviewUri.collectAsStateWithLifecycle()

    // Queue hiding the request for each unique autofocus tap
    if (showAutoFocusIndicator){
        LaunchedEffect(autoRequestId) {
            delay(1000)
            // Clear the offset to finish the request and hide the indicator
            autofocusRequest = autoRequestId to Offset.Unspecified
        }
    }
    val elapsedTime by cameraViewModel.elapsedTime.asFlow().collectAsStateWithLifecycle("")

    surfaceRequest?.let { newRequest->

        val coordinateTransformer = remember {
            MutableCoordinateTransformer()
        }

        BackHandler(enabled = onNavigateBack != null) {
            when(recordingState){
                RecordingState.Recording,RecordingState.Paused -> {
                    cameraViewModel.stopRecording()
                    onNavigateBack?.invoke()
                }
                else -> {
                    onNavigateBack?.invoke()
                }
            }

        }
        Scaffold(modifier = modifier.fillMaxSize()){ paddingValues->
            Box(modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()){
                CameraXViewfinder(surfaceRequest = newRequest, modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(cameraViewModel, coordinateTransformer, zoom) {
                    var currentZoom = zoom

                    forEachGesture {
                        awaitPointerEventScope {
                            // Get the initial down event with at least one pointer
                            val firstDown = awaitFirstDown(requireUnconsumed = false)

                            // Track position for drag detection
                            var drag = Offset.Zero
                            var pastTouchSlop = false
                            val touchSlop = viewConfiguration.touchSlop

                            // Wait for additional pointer or movement
                            do {
                                val event = awaitPointerEvent()
                                val currentPointers = event.changes.size

                                // Check if we have multiple pointers for zoom gesture
                                if (currentPointers >= 2) {
                                    // Handle pinch-to-zoom
                                    // Cancel drag detection
                                    pastTouchSlop = false
                                    drag = Offset.Zero

                                    try {
                                        // Handle the zoom gesture using the built-in transform detection
                                        scope.launch {
                                            detectTransformGestures(
                                                onGesture = { _, _, gestureZoom, _ ->
                                                    currentZoom *= gestureZoom
                                                    cameraViewModel.pinchZoom(currentZoom)
                                                }
                                            )
                                            // If we reach here, zoom completed successfully
                                            return@launch
                                        }
                                    } catch (e: CancellationException) {
                                        // Transform gesture got canceled, continue with detection
                                    }
                                } else if (currentPointers == 1) {
                                    // Single pointer - could be tap or drag
                                    val pointer = event.changes[0]

                                    // Update accumulated drag
                                    if (pointer.id == firstDown.id) {
                                        drag += pointer.positionChange()

                                        // Check if we've exceeded the touch slop threshold
                                        if (!pastTouchSlop && abs(drag.x) > touchSlop) {
                                            pastTouchSlop = true
                                        }

                                        // If we're in drag mode, consume the events
                                        if (pastTouchSlop) {
                                            pointer.consume()
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })

                            // After all pointers are up, decide what gesture it was
                            if (pastTouchSlop && abs(drag.x) > abs(drag.y)) {
                                // This was a horizontal drag
                                val dragAmount = drag.x
                                if (dragAmount < 0) {
                                    // Left swipe
                                    // check if camera is still recording
                                    if (recordingState == RecordingState.Recording) {
                                        cameraViewModel.stopRecording()
                                        onNavigateToPhotoCamera()
                                    } else {
                                        onNavigateToPhotoCamera()
                                    }
                                }
                            } else if (!pastTouchSlop && drag.getDistance() < touchSlop) {
                                // This was a tap (minimal movement)
                                val tapCoordinates = firstDown.position
                                with(coordinateTransformer) {
                                    cameraViewModel.tapToFocus(tapCoordinates.transform())
                                }
                                autofocusRequest = UUID.randomUUID() to tapCoordinates
                            }
                        }
                    }
                }
                    .alpha(previewAlpha)
                )



                ConstraintLayout(modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)) {
                    val (topBAr, bottomBg,recordButton,cameraSwitcher,galleryPreview,videoText,cameraText,chipTimer) = createRefs()
                    // Define guidelines for the grid (1/3 and 2/3 positions)
                    val vertical1 = createGuidelineFromStart(0.33f)
                    val vertical2 = createGuidelineFromStart(0.66f)
                    val horizontal1 = createGuidelineFromTop(0.33f)
                    val horizontal2 = createGuidelineFromTop(0.66f)

                    // Common modifier for grid lines
                    val lineModifier = Modifier
                        .alpha(if (showGridLines) 1f else 0f)
                        .background(Color.White.copy(alpha = 0.5f))

                        AnimatedVisibility(visible = (recordingState == RecordingState.Idle || recordingState == RecordingState.Stopped),
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier
                                .constrainAs(topBAr) {
                                    top.linkTo(parent.top)
                                    start.linkTo(parent.start)
                                    end.linkTo(parent.end)
                                }
                                .fillMaxWidth()
                        ){
                            Row(modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.4f))
                                .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly) {
                                IconButton(onClick = {
                                    onClose()
                                }) {
                                    Icon(Icons.Filled.Close,
                                        tint = Color.White,
                                        contentDescription = null)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        showBSettingsBottomSheet = !showBSettingsBottomSheet
                                    }
                                }) {
                                    Icon(Icons.Outlined.VideoSettings, contentDescription = null, tint = Color.White)
                                }
                                IconButton(onClick = {
                                    showGridLines = !showGridLines
                                }) {
                                    Icon(imageVector = if (showGridLines) Icons.Outlined.GridOff else Icons.Outlined.GridOn,
                                        tint = Color.White,contentDescription = if (showGridLines) stringResource(
                                            R.string.grid_lines_hide_description
                                        ) else stringResource(R.string.show_grid_lines_description)
                                    )
                                }
                                IconButton(onClick = {
                                    cameraViewModel.toggleTorchForVideo()

                                }) {
                                    Icon(imageVector = if (torchOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                                        tint = Color.White,contentDescription = if (torchOn) stringResource(
                                            R.string.turn_flash_off
                                        ) else stringResource(R.string.turn_flash_on)
                                    )
                                }
                            }
                        }

                    AnimatedVisibility(visible = elapsedTime.isNotEmpty(),
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.constrainAs(chipTimer){
                            bottom.linkTo(bottomBg.top, margin = 8.dp)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                        }) {
                        ElevatedAssistChip(onClick = {}, label = { Text(elapsedTime) })
                    }

                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .height(dimensionResource(R.dimen.transparent_view_height))
                        .constrainAs(bottomBg) {
                            bottom.linkTo(parent.bottom)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)

                        }
                        .background(Color.Black.copy(alpha = 0.4f))
                    )



                    IconButton(onClick = {
                        if (recordingState == RecordingState.Idle || recordingState == RecordingState.Stopped){
                            cameraViewModel.startRecording()
                        } else {
                            cameraViewModel.stopRecording()
                        }

                    },
                        modifier = Modifier
                            .constrainAs(recordButton) {
                                top.linkTo(bottomBg.top)
                                bottom.linkTo(bottomBg.bottom)
                                start.linkTo(bottomBg.start)
                                end.linkTo(bottomBg.end)
                                verticalBias = 0.3f
                            }
                            .size(64.dp)
                            .background(Color.Red, CircleShape)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = if (recordingState == RecordingState.Idle || recordingState == RecordingState.Stopped) Icons.Filled.Videocam else Icons.Filled.Stop,
                            tint = Color.White,
                            contentDescription = if (recordingState == RecordingState.Idle) stringResource(
                                R.string.record_video
                            ) else stringResource(R.string.stop_recording)
                        )
                    }

                    AnimatedVisibility(visible = recordingState != RecordingState.Recording,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.constrainAs(galleryPreview) {
                            top.linkTo(recordButton.top)
                            bottom.linkTo(recordButton.bottom)
                            start.linkTo(recordButton.end)
                            end.linkTo(parent.end)
                        } ){
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF444444), CircleShape)
                                .clip(CircleShape)
                                .border(width = 2.dp, color = Color.White, shape = CircleShape)

                        ) {
                            AnimatedContent(targetState = thumbPreviewUri,
                                transitionSpec = {
                                    fadeIn() togetherWith fadeOut()
                                },
                                modifier = Modifier.matchParentSize()) { media->

                                if (media != null) {
                                    ItemPreview(modifier = Modifier
                                        .matchParentSize()
                                        .clickable {
                                            onNavigateToPreview()
                                        }
                                        , media = media )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Photo,
                                        contentDescription = "No media",
                                        tint = Color.Gray,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                    )
                                }
                            }

                        }
                    }

                    AnimatedVisibility(visible = recordingState != RecordingState.Recording,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.constrainAs(cameraSwitcher) {
                            top.linkTo(recordButton.top)
                            bottom.linkTo(recordButton.bottom)
                            end.linkTo(recordButton.start)
                            start.linkTo(parent.start)
                        }
                    ){
                        IconButton(onClick = {
                            scope.launch {
                                cameraViewModel.switchLensFacing(lifecycleOwner,CameraMode.VIDEO)
                            }

                        },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF444444), CircleShape)
                                .clip(CircleShape)
                        ) {
                            Icon(imageVector = Icons.Outlined.Cameraswitch,
                                tint = Color.White,
                                contentDescription = null)
                        }
                    }

                    Button(onClick = {},modifier = Modifier
                        .constrainAs(videoText){
                            start.linkTo(recordButton.start)
                            end.linkTo(recordButton.end)
                            top.linkTo(recordButton.bottom, margin = 8.dp)

                        }) {
                        Text(stringResource(R.string.video))
                    }

                    Text(text = stringResource(R.string.camera),
                        modifier = Modifier
                            .constrainAs(cameraText) {
                                end.linkTo(videoText.start)
                                top.linkTo(videoText.top)
                                bottom.linkTo(videoText.bottom)
                                start.linkTo(parent.start)
                                horizontalBias = 0.6f

                            }
                            .clickable {
                                onNavigateToPhotoCamera()
                            },
                        style = MaterialTheme.typography.labelLarge.copy(color = Color.White)
                    )

                    // 1/3 vertical grid line
                    Box(
                        modifier = lineModifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .constrainAs(createRef()) {
                                start.linkTo(vertical1)
                            }
                    )
                    // 2/3 vertical grid line
                    Box(
                        modifier = lineModifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .constrainAs(createRef()) {
                                start.linkTo(vertical2)
                            }
                    )

                    // Horizontal grid lines
                    // 1/3 horizontal grid line
                    Box(
                        modifier = lineModifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .constrainAs(createRef()) {
                                top.linkTo(horizontal1)
                            }
                    )
                    // 2/3 horizontal grid line
                    Box(
                        modifier = lineModifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .constrainAs(createRef()) {
                                top.linkTo(horizontal2)
                            }
                    )



                }
            }

            AnimatedVisibility(visible = showAutoFocusIndicator,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .offset { autofocusCoordinates.takeOrElse { Offset.Zero }.round() }
                    .offset((-24).dp, (-24).dp)
            ) {
                Spacer(modifier = Modifier
                    .border(2.dp, Color.White, CircleShape)
                    .size(48.dp)
                    .shadow(elevation = 8.dp))
            }

            if (showBSettingsBottomSheet){
                ModalBottomSheet(onDismissRequest = {
                    showBSettingsBottomSheet = false
                }, sheetState = settingsSheetState) {

                    Text(stringResource(R.string.video_settings), style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box{
                            Column{
                                Text(stringResource(R.string.resolution), style = MaterialTheme.typography.bodySmall)
                                selectedQuality?.let {
                                    Text(it.getName(), style = MaterialTheme.typography.labelMedium)
                                }
                            }

                        }
                        Spacer(modifier = Modifier.weight(1f))
                        cameraQualities.reversed().forEach {
                            IconButton(onClick = {
                                scope.launch {
                                    cameraViewModel.changeQuality(quality = it, lifecycleOwner = lifecycleOwner)
                                }

                            }, modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF444444), CircleShape)) {
                                Icon(painter = painterResource(it.toIconRes()), contentDescription = it.getName(),
                                    tint = if (selectedQuality == it) Color.Red else Color.White )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                        }



                    }

                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
        }
    }

}











