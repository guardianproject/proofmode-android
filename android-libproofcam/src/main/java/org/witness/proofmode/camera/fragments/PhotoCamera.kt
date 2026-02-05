package org.witness.proofmode.camera.fragments

import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.ImageCapture
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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.witness.proofmode.camera.R
import org.witness.proofmode.camera.utils.flashModeToIconRes
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoCamera(modifier: Modifier = Modifier, cameraViewModel: CameraViewModel = viewModel(),
                lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
                onNavigateToVideo: () -> Unit,
                onNavigateToPreview: () -> Unit,
                onClose:()-> Unit = {}) {

    val thumbPreviewUri by cameraViewModel.thumbPreviewUri.collectAsStateWithLifecycle()
    val surfaceRequest by cameraViewModel.surfaceRequest.collectAsStateWithLifecycle()
    var showGridLines:Boolean by remember {
        mutableStateOf(false)
    }

    val settingsSheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var showBSettingsBottomSheet by remember { mutableStateOf(false) }
    val previewAlpha by cameraViewModel.previewAlpha.collectAsStateWithLifecycle()
    var zoom by remember { mutableFloatStateOf(1f) }

    val exposureState by cameraViewModel.exposureState.collectAsStateWithLifecycle()
    var showExposureIndicator by remember { mutableStateOf(false) }
    val flashMode by cameraViewModel.flashMode.collectAsStateWithLifecycle()
    var showFlashModes by remember { mutableStateOf(false) }
    val ultraHdrOn by cameraViewModel.ultraHdr.collectAsStateWithLifecycle()
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

    val lowerSliderRange:Float = exposureState?.exposureCompensationRange?.lower?.toFloat() ?:0F
    val upperSliderRange:Float = exposureState?.exposureCompensationRange?.upper?.toFloat() ?:0F
    val steps = (upperSliderRange - lowerSliderRange).toInt()
    val currentExposureIndex = exposureState?.exposureCompensationIndex ?: 0F
    var currentSliderPosition by remember(currentExposureIndex) {
        mutableStateOf(currentExposureIndex)
    }

    var countDownState:CountDownState by remember { mutableStateOf(CountDownState.Idle) }
    val cameraDelay by cameraViewModel.cameraDelay.collectAsStateWithLifecycle()

    // Queue hiding the request for each unique autofocus tap
    if (showAutoFocusIndicator){
        LaunchedEffect(autoRequestId) {
            delay(1000)
            // Clear the offset to finish the request and hide the indicator
            autofocusRequest = autoRequestId to Offset.Unspecified
        }
    }

    //var zoomScale by remember { mutableStateOf(1f) }

    surfaceRequest?.let { newRequest->

        val coordinateTransformer = remember {
            MutableCoordinateTransformer()
        }

        Scaffold(modifier = modifier.fillMaxSize()){ paddingValues->
                ConstraintLayout(modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)) {
                    val (viewFinder,topBAr, cancelButton,countDownStateView, bottomBg,captureButton,cameraSwitcher,galleryPreview,videoText,cameraText,
                        flashModeRow) = createRefs()
                    // Define guidelines for the grid (1/3 and 2/3 positions)
                    val vertical1 = createGuidelineFromStart(0.33f)
                    val vertical2 = createGuidelineFromStart(0.66f)
                    val horizontal1 = createGuidelineFromTop(0.33f)
                    val horizontal2 = createGuidelineFromTop(0.66f)

                    // Common modifier for grid lines
                    val lineModifier = Modifier
                        .alpha(if (showGridLines) 1f else 0f)
                        .background(Color.White.copy(alpha = 0.5f))

                    CameraXViewfinder(surfaceRequest = newRequest, modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize()
                        .constrainAs(viewFinder) {
                            top.linkTo(parent.top)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                            bottom.linkTo(parent.bottom)
                        }
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
                                            // Check if there is a count down state
                                            if (countDownState == CountDownState.Running){
                                                countDownState = CountDownState.Cancelled
                                                onNavigateToVideo()
                                            } else{
                                                onNavigateToVideo()
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

                    AnimatedVisibility(visible = !showFlashModes && countDownState != CountDownState.Running,
                        modifier = Modifier
                            .fillMaxWidth()
                            .constrainAs(topBAr) {
                                top.linkTo(parent.top, margin = 60.dp)
                                start.linkTo(parent.start)
                                end.linkTo(parent.end)
                            }){
                        Row(modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.4f))
                            .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = onClose) {

                                Icon(
                                    Icons.Filled.Close,
                                    tint = Color.White,
                                    contentDescription = null)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    showBSettingsBottomSheet = !showBSettingsBottomSheet
                                }
                            }) {
                                Icon(Icons.Outlined.Settings, contentDescription = null, tint = Color.White)
                            }
                            IconButton(onClick = {
                                showGridLines = !showGridLines
                            }) {
                                Icon(imageVector = if (showGridLines) ImageVector.vectorResource(R.drawable.ic_grid_off)
                                else ImageVector.vectorResource(R.drawable.ic_grid_on) ,
                                    tint = Color.White,contentDescription = if (showGridLines) stringResource(
                                        R.string.grid_lines_hide_description
                                    ) else stringResource(R.string.show_grid_lines_description)
                                )
                            }
                            IconButton(onClick = {
                                showFlashModes = true

                            }) {
                                Icon(imageVector = flashModeToIconRes(flashMode),
                                    tint = Color.White,contentDescription = stringResource(R.string.change_flash_mode_content_description)
                                )
                            }

                            IconButton(onClick = {
                                showExposureIndicator = true
                            }) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_exposure) , tint = Color.White,contentDescription = stringResource(
                                    R.string.change_exposure_compensation
                                )
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = showFlashModes,
                        modifier = Modifier.constrainAs(flashModeRow){
                            top.linkTo(parent.top, margin = 60.dp)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)

                        }){
                        Row(modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.4f)),
                            horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = {
                                cameraViewModel.toggleFlashMode(ImageCapture.FLASH_MODE_OFF,lifecycleOwner)
                                showFlashModes = false
                            }) {
                                Icon(
                                    ImageVector.vectorResource(R.drawable.ic_flash_off) ,
                                    tint = if (flashMode == ImageCapture.FLASH_MODE_OFF) Color.Red else Color.White,
                                    contentDescription = stringResource(R.string.turn_off_flash_description)
                                )

                            }
                            IconButton(onClick = {
                                cameraViewModel.toggleFlashMode(ImageCapture.FLASH_MODE_AUTO,lifecycleOwner)
                                showFlashModes = false
                            }) {
                                Icon(
                                    ImageVector.vectorResource(R.drawable.ic_flash_auto) ,
                                    tint = if (flashMode == ImageCapture.FLASH_MODE_AUTO) Color.Red else Color.White,
                                    contentDescription = stringResource(R.string.turn_flash_on_auto_description)
                                )

                            }

                            IconButton(onClick = {
                                cameraViewModel.toggleFlashMode(ImageCapture.FLASH_MODE_ON,lifecycleOwner)
                                showFlashModes = false
                            }) {
                                Icon(
                                    ImageVector.vectorResource(R.drawable.ic_flash_on) ,
                                    tint = if (flashMode == ImageCapture.FLASH_MODE_ON) Color.Red else Color.White,
                                    contentDescription = stringResource(R.string.turn_flash_on_description)
                                )

                            }
                        }
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

                    AnimatedVisibility(modifier = Modifier.constrainAs(captureButton) {
                        top.linkTo(bottomBg.top)
                        bottom.linkTo(bottomBg.bottom)
                        start.linkTo(bottomBg.start)
                        end.linkTo(bottomBg.end)
                        verticalBias = 0.2f
                    }, visible = countDownState == CountDownState.Idle || countDownState == CountDownState.Completed){
                        IconButton(onClick = {
                            if(cameraDelay == CameraDelay.Zero){
                                cameraViewModel.captureImage()
                            } else {
                                countDownState = CountDownState.Running
                            }

                        },
                            modifier = Modifier
                                .size(64.dp)
                                .background(Color.White, CircleShape)
                                .clip(CircleShape)
                        ) {
                            if (cameraDelay !=CameraDelay.Zero){
                                Text(text = "${cameraDelay.value}", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                            }

                        }
                    }

                    AnimatedVisibility(modifier = Modifier .constrainAs(galleryPreview) {
                        top.linkTo(captureButton.top)
                        bottom.linkTo(captureButton.bottom)
                        start.linkTo(captureButton.end)
                        end.linkTo(parent.end)
                    }, visible = countDownState == CountDownState.Idle || countDownState == CountDownState.Completed){
                        Box(
                            modifier = Modifier

                                .size(48.dp)
                                .background(Color(0xFF444444), CircleShape)
                                .clip(CircleShape)
                                .border(width = 2.dp, color = Color.White, shape = CircleShape)


                        ) {
                            AnimatedContent(
                                targetState = thumbPreviewUri,
                                transitionSpec = {
                                    fadeIn() togetherWith fadeOut()
                                },
                                modifier = Modifier.matchParentSize()
                            ) { media ->
                                if (media != null) {
                                    ItemPreview(modifier = Modifier
                                        .matchParentSize()
                                        .clickable {
                                            onNavigateToPreview()
                                        }, media = media)
                                } else {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(R.drawable.ic_no_picture) ,
                                        contentDescription = "No media",
                                        tint = Color.Gray,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        modifier = Modifier.constrainAs(cameraSwitcher) {
                            top.linkTo(captureButton.top)
                            bottom.linkTo(captureButton.bottom)
                            end.linkTo(captureButton.start)
                            start.linkTo(parent.start)
                        }, visible = countDownState == CountDownState.Idle || countDownState == CountDownState.Completed) {
                        IconButton(onClick = {
                            scope.launch {
                                cameraViewModel.switchLensFacing(lifecycleOwner,CameraMode.IMAGE)
                            }

                        },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF444444), CircleShape)
                                .clip(CircleShape)
                        ) {
                            Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_switch),
                                tint = Color.White,
                                contentDescription = null)
                        }
                    }

                    AnimatedVisibility(modifier = Modifier
                        .constrainAs(cancelButton) {

                            bottom.linkTo(bottomBg.top, margin = 16.dp)
                            start.linkTo(bottomBg.start)
                            end.linkTo(bottomBg.end)


                        }
                        .shadow(8.dp, CircleShape), visible = countDownState == CountDownState.Running) {
                        IconButton(onClick = {
                            countDownState = CountDownState.Cancelled
                        }, modifier = Modifier
                            .size(64.dp)
                            .background(Color.Black, CircleShape)
                            .clip(CircleShape)
                        ) {
                            Icon(Icons.Filled.Close, tint = Color.White, contentDescription = null)
                        }
                    }

                    Button(onClick = {},modifier = Modifier
                        .constrainAs(cameraText){
                            start.linkTo(captureButton.start)
                            end.linkTo(captureButton.end)
                            top.linkTo(captureButton.bottom, margin = 8.dp)

                        }) {
                        Text(stringResource(R.string.camera))
                    }

                    AnimatedVisibility(visible = countDownState == CountDownState.Idle || countDownState == CountDownState.Completed,
                        modifier = Modifier.
                        constrainAs(videoText) {
                            end.linkTo(cameraText.start)
                            top.linkTo(cameraText.top)
                            bottom.linkTo(cameraText.bottom)
                            start.linkTo(parent.start)
                            horizontalBias = 0.6f

                        }

                    ){
                        Text(text = stringResource(R.string.video),
                            modifier = Modifier
                                .clickable {
                                    onNavigateToVideo()
                                },
                            style = MaterialTheme.typography.labelLarge.copy(color = Color.White),
                            textAlign = TextAlign.Center
                        )
                    }

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

                    CountDownTimerUI(
                        modifier = Modifier.constrainAs(countDownStateView){
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)

                        },
                        initialDelay = cameraDelay.value, countDownState = countDownState,
                        onStateChange = {countDownState = it}) {
                        cameraViewModel.captureImage()
                        countDownState = CountDownState.Idle // reset the UI
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

                    Text("Camera Settings", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(modifier = Modifier.weight(1f)){
                            Column{
                                Text(stringResource(R.string.ultra_hdr), style = MaterialTheme.typography.bodyLarge)
                                Text(text = stringResource(R.string.ultra_hdr_description,ultraHdrOn.description), style = MaterialTheme.typography.bodySmall)
                            }

                        }

                        Switch(checked = ultraHdrOn == UltraHDRAvailabilityState.ON, onCheckedChange = {
                            scope.launch {
                                cameraViewModel.toggleUltraHdr(lifecycleOwner)
                            }
                        })


                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row (modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ){
                            Column {
                                Text(stringResource(R.string.timer),style = MaterialTheme.typography.bodyLarge)
                                Text(cameraDelay.toSecondsString())
                            }

                        Spacer(Modifier.weight(1f))
                        CameraDelay.entries.forEach { theDelay->
                            IconButton(onClick = {
                                cameraViewModel.updateCameraDelay(theDelay)
                            }, modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF444444), CircleShape)
                                .clip(CircleShape)
                            ){
                                Icon(
                                    theDelay.toVectorIcon(),
                                    contentDescription = "${theDelay.value} seconds delay",
                                    tint = if (cameraDelay == theDelay) Color.Red else Color.White,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))

                        }
                    }

                    Spacer(modifier = Modifier.height(40.dp))

                }
            }

            if (showExposureIndicator){
                BasicAlertDialog(onDismissRequest = {
                    showExposureIndicator = false
                }) {
                    Column (modifier = Modifier
                        .background(MaterialTheme.colorScheme.onBackground)
                        .padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally){
                        Text("Exposure:${currentSliderPosition.toInt()}", style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.primary))
                        Spacer(modifier = Modifier.height(10.dp))
                        Slider(
                            valueRange = lowerSliderRange..upperSliderRange,
                            value = currentSliderPosition.toFloat(),
                            onValueChange = {
                                currentSliderPosition = it
                                cameraViewModel.updateExposureCompensation(it.toInt())
                            },
                            steps = steps
                        )
                    }
                }
            }


        }
    }

}

