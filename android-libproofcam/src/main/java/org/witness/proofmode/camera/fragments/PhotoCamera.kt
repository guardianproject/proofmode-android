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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.GridOn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoCamera(modifier: Modifier = Modifier, cameraViewModel: CameraViewModel = viewModel(),
                lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
                onNavigateToVideo: () -> Unit,
                onNavigateToPreview: () -> Unit) {

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
            Box(modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()){
                CameraXViewfinder(surfaceRequest = newRequest, modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(cameraViewModel, coordinateTransformer) {
                        detectTapGestures { tapCoordinates ->
                            //Toast.makeText(context, tapCoordinates.toString(),Toast.LENGTH_SHORT).show()
                            with(coordinateTransformer) {
                                cameraViewModel.tapToFocus(tapCoordinates.transform())
                            }
                            autofocusRequest = UUID.randomUUID() to tapCoordinates

                        }

                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, gestureZoom, _ ->
                            //val oldScale = zoom
                            val newScale = zoom * gestureZoom
                            zoom = newScale
                            cameraViewModel.pinchZoom(zoom)
                        }
                    }
                    .alpha(previewAlpha)
                )



                ConstraintLayout(modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)) {
                    val (topBAr, bottomBg,captureButton,cameraSwitcher,galleryPreview,videoText,cameraText,
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

                    AnimatedVisibility(visible = !showFlashModes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .constrainAs(topBAr) {
                                top.linkTo(parent.top)
                                start.linkTo(parent.start)
                                end.linkTo(parent.end)
                            }){
                        Row(modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.4f))
                            .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly) {
                            IconButton(onClick = {}) {

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
                                Icon(imageVector = if (showGridLines) Icons.Outlined.GridOff else Icons.Outlined.GridOn,
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
                                Icon(Icons.Outlined.Exposure, tint = Color.White,contentDescription = stringResource(
                                    R.string.change_exposure_compensation
                                )
                                )
                            }
                        }
                    }

                    AnimatedVisibility(visible = showFlashModes,
                        modifier = Modifier.constrainAs(flashModeRow){
                            top.linkTo(parent.top)
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
                                    Icons.Filled.FlashOff,
                                    tint = if (flashMode == ImageCapture.FLASH_MODE_OFF) Color.Red else Color.White,
                                    contentDescription = stringResource(R.string.turn_off_flash_description)
                                )

                            }
                            IconButton(onClick = {
                                cameraViewModel.toggleFlashMode(ImageCapture.FLASH_MODE_AUTO,lifecycleOwner)
                                showFlashModes = false
                            }) {
                                Icon(
                                    Icons.Filled.FlashAuto,
                                    tint = if (flashMode == ImageCapture.FLASH_MODE_AUTO) Color.Red else Color.White,
                                    contentDescription = stringResource(R.string.turn_flash_on_auto_description)
                                )

                            }

                            IconButton(onClick = {
                                cameraViewModel.toggleFlashMode(ImageCapture.FLASH_MODE_ON,lifecycleOwner)
                                showFlashModes = false
                            }) {
                                Icon(
                                    Icons.Filled.FlashOn,
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

                    IconButton(onClick = {
                        cameraViewModel.captureImage()

                    },
                        modifier = Modifier
                            .constrainAs(captureButton) {
                                top.linkTo(bottomBg.top)
                                bottom.linkTo(bottomBg.bottom)
                                start.linkTo(bottomBg.start)
                                end.linkTo(bottomBg.end)
                                verticalBias = 0.3f
                            }
                            .size(48.dp)
                            .background(Color.White, CircleShape)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Camera,
                            tint = Color.White,
                            contentDescription = stringResource(R.string.capture_image_description)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .constrainAs(galleryPreview) {
                                top.linkTo(captureButton.top)
                                bottom.linkTo(captureButton.bottom)
                                start.linkTo(captureButton.end)
                                end.linkTo(parent.end)
                            }
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

                    IconButton(onClick = {
                        scope.launch {
                            cameraViewModel.switchLensFacing(lifecycleOwner,CameraMode.IMAGE)
                        }

                    },
                        modifier = Modifier
                            .constrainAs(cameraSwitcher) {
                                top.linkTo(captureButton.top)
                                bottom.linkTo(captureButton.bottom)
                                end.linkTo(captureButton.start)
                                start.linkTo(parent.start)
                            }
                            .size(48.dp)
                            .background(Color(0xFF444444), CircleShape)
                            .clip(CircleShape)
                    ) {
                        Icon(imageVector = Icons.Outlined.Cameraswitch,
                            tint = Color.White,
                            contentDescription = null)
                    }

                    Button(onClick = {},modifier = Modifier
                        .constrainAs(cameraText){
                            start.linkTo(captureButton.start)
                            end.linkTo(captureButton.end)
                            top.linkTo(captureButton.bottom, margin = 8.dp)

                        }) {
                        Text(stringResource(R.string.camera))
                    }

                    Text(text = stringResource(R.string.video),
                        modifier = Modifier
                            .constrainAs(videoText) {
                                end.linkTo(cameraText.start)
                                top.linkTo(cameraText.top)
                                bottom.linkTo(cameraText.bottom)
                                start.linkTo(parent.start)
                                horizontalBias = 0.6f

                            }
                            .clickable {
                                onNavigateToVideo()
                            },
                        style = MaterialTheme.typography.labelMedium.copy(color = Color.White)
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

