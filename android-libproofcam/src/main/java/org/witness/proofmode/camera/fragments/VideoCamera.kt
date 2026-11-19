package org.witness.proofmode.camera.fragments

import android.Manifest
import android.view.Surface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.constraintlayout.compose.Dimension
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.takeOrElse
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.ConstraintSet
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.witness.proofmode.camera.R
import org.witness.proofmode.camera.utils.getName
import org.witness.proofmode.camera.utils.toIconRes
import java.util.UUID
import kotlin.math.abs


@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun VideoCamera(modifier: Modifier = Modifier,cameraViewModel: CameraViewModel = viewModel(),
                lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
                onNavigateToPhotoCamera: () -> Unit,
                onNavigateBack: (() -> Unit)? = null,
                onNavigateToPreview: () -> Unit,
                onClose:()-> Unit = {}) {
    val context = LocalContext.current
    val surfaceRequest by cameraViewModel.surfaceRequest.collectAsStateWithLifecycle()
    var showGridLines:Boolean by rememberSaveable {
        mutableStateOf(false)
    }
    val recordingState by cameraViewModel.recordingState.collectAsStateWithLifecycle()
    val torchOn by cameraViewModel.torchOn.collectAsStateWithLifecycle()
    val settingsSheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var showBSettingsBottomSheet by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    val cameraQualities by cameraViewModel.cameraQualities.collectAsStateWithLifecycle()
    val selectedQuality by cameraViewModel.selectedQuality.collectAsStateWithLifecycle()
    val previewAlpha by cameraViewModel.previewAlpha.collectAsStateWithLifecycle()
    val locationEnabled by cameraViewModel.locationEnabled.collectAsStateWithLifecycle()
    val requestLocationPermission by cameraViewModel.requestLocationPermission.collectAsStateWithLifecycle()

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        cameraViewModel.setLocationEnabled(granted)
    }
    LaunchedEffect(requestLocationPermission) {
        if (requestLocationPermission > 0) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val proMode by cameraViewModel.proMode.collectAsStateWithLifecycle()
    val proModeDescription = stringResource(R.string.pro_mode_description)
    val deviceRotation by cameraViewModel.deviceRotation.collectAsStateWithLifecycle()
    val videoFrameRates by cameraViewModel.videoFrameRates.collectAsStateWithLifecycle()
    val activeVideoFps by cameraViewModel.activeVideoFps.collectAsStateWithLifecycle()
    val videoStabilization by cameraViewModel.videoStabilization.collectAsStateWithLifecycle()
    val stabilizationSupported by cameraViewModel.stabilizationSupported.collectAsStateWithLifecycle()
    val audioMuted by cameraViewModel.audioMuted.collectAsStateWithLifecycle()
    val audioLevel by cameraViewModel.audioLevel.collectAsStateWithLifecycle()
    val isRecording = recordingState == RecordingState.Recording || recordingState == RecordingState.Paused

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
    val config  = LocalConfiguration.current

    // Exposure rides alongside focus: tapping to focus reveals the left-edge slider,
    // and any touch of it restarts the auto-hide timer via a bumped nudge counter.
    val exposureState by cameraViewModel.exposureState.collectAsStateWithLifecycle()
    val exposureIndex by cameraViewModel.exposureIndex.collectAsStateWithLifecycle()
    var showExposureSlider by remember { mutableStateOf(false) }
    var exposureNudge by remember { mutableIntStateOf(0) }

    // Queue hiding the request for each unique autofocus tap
    if (showAutoFocusIndicator){
        LaunchedEffect(autoRequestId) {
            delay(1000)
            // Clear the offset to finish the request and hide the indicator
            autofocusRequest = autoRequestId to Offset.Unspecified
        }
    }

    LaunchedEffect(showExposureSlider, exposureNudge) {
        if (showExposureSlider) {
            delay(ExposureSliderTimeoutMs)
            showExposureSlider = false
        }
    }
    val elapsedTime by cameraViewModel.elapsedTime.asFlow().collectAsStateWithLifecycle("")

    val audioPermissionState = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    LaunchedEffect(Unit) {
        if (!audioPermissionState.status.isGranted) {
            audioPermissionState.launchPermissionRequest()
        }
    }

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
                .fillMaxSize()
                .background(Color.Black)){
                // Keep preview, tap targets, and the AF ring in one padded box so
                // tap Offset is in the same coordinate space as the ring overlay.
                Box(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize()
                ) {
                    CameraXViewfinder(
                        surfaceRequest = newRequest,
                        coordinateTransformer = coordinateTransformer,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(cameraViewModel, coordinateTransformer) {
                                awaitEachGesture {
                                    // requireUnconsumed: overlays drawn on top of the
                                    // viewfinder (the exposure slider) are hit-tested
                                    // too, and claim the down when the touch is theirs.
                                    val firstDown = awaitFirstDown(requireUnconsumed = true)

                                    var drag = Offset.Zero
                                    var pastTouchSlop = false
                                    val touchSlop = viewConfiguration.touchSlop
                                    var previousPinchDistance = 0f
                                    var isZooming = false

                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }

                                        if (pressed.size >= 2) {
                                            val currentDistance =
                                                (pressed[0].position - pressed[1].position).getDistance()

                                            if (!isZooming) {
                                                isZooming = true
                                                previousPinchDistance = currentDistance
                                                drag = Offset.Zero
                                                pastTouchSlop = false
                                            } else if (previousPinchDistance > 0f && currentDistance > 0f) {
                                                val zoomDelta = currentDistance / previousPinchDistance
                                                cameraViewModel.pinchZoom(zoomDelta)
                                                previousPinchDistance = currentDistance
                                            }
                                            pressed.forEach { it.consume() }
                                        } else if (pressed.size == 1 && !isZooming) {
                                            val pointer = pressed[0]
                                            if (pointer.id == firstDown.id) {
                                                drag += pointer.positionChange()
                                                if (!pastTouchSlop && abs(drag.x) > touchSlop) {
                                                    pastTouchSlop = true
                                                }
                                                if (pastTouchSlop) {
                                                    pointer.consume()
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })

                                    if (!isZooming) {
                                        if (pastTouchSlop && abs(drag.x) > abs(drag.y)) {
                                            if (drag.x > 0) {
                                                if (recordingState == RecordingState.Recording) {
                                                    cameraViewModel.stopRecording()
                                                    onNavigateToPhotoCamera()
                                                } else {
                                                    onNavigateToPhotoCamera()
                                                }
                                            }
                                        } else if (!pastTouchSlop && drag.getDistance() < touchSlop) {
                                            val tapCoordinates = firstDown.position
                                            with(coordinateTransformer) {
                                                cameraViewModel.tapToFocus(tapCoordinates.transform())
                                            }
                                            autofocusRequest = UUID.randomUUID() to tapCoordinates
                                            showExposureSlider = true
                                            exposureNudge++
                                        }
                                    }
                                }
                            }
                            .alpha(previewAlpha)
                    )

                    AnimatedVisibility(
                        visible = showAutoFocusIndicator,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .offset { autofocusCoordinates.takeOrElse { Offset.Zero }.round() }
                            .offset((-24).dp, (-24).dp)
                    ) {
                        Spacer(
                            modifier = Modifier
                                .border(1.5.dp, AccentGreen, CircleShape)
                                .size(48.dp)
                                .shadow(elevation = 8.dp)
                        )
                    }

                    // Exposure lives over the preview rather than in the top bar, so it
                    // is adjusted while looking at the scene it affects.
                    AnimatedVisibility(
                        // In Pro mode exposure compensation lives in the Pro bar instead.
                        visible = showExposureSlider && !proMode,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 8.dp)
                    ) {
                        ExposureSlider(
                            exposureState = exposureState,
                            index = exposureIndex,
                            onIndexChange = { cameraViewModel.updateExposureCompensation(it) },
                            onInteraction = { exposureNudge++ }
                        )
                    }
                }
                // Meant for responsive design but activity has been restricted to portrait,

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isPortrait = minWidth < minHeight
                    // The activity is locked to portrait, so when the phone is held in
                    // landscape the Pro bar is turned to read upright and moved to whichever
                    // long edge is now physically at the bottom (as in PhotoCamera).
                    val proLandscape = deviceRotation == Surface.ROTATION_90 ||
                            deviceRotation == Surface.ROTATION_270
                    // ROTATION_90: left edge down, so "up" for the reader is screen-right.
                    val proOnStartEdge = deviceRotation == Surface.ROTATION_90
                    val constraints = if (isPortrait) portraitConstraints(proLandscape, proOnStartEdge)
                    else landscapeConstraints()

                    val topBarButtons = remember {
                        movableContentOf{
                            IconButton(onClick = {
                                cameraViewModel.toggleLocationEnabled()
                            }) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_location),
                                    tint = if (locationEnabled) AccentGreen else InactiveWhite,
                                    contentDescription = stringResource(R.string.toggle_location_description))
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    showBSettingsBottomSheet = !showBSettingsBottomSheet
                                }
                            }) {
                                Icon(ImageVector.vectorResource(R.drawable.ic_video_settings), contentDescription = null, tint = Color.White)
                            }
                            IconButton(onClick = {
                                showGridLines = !showGridLines
                            }) {
                                Icon(imageVector = if (showGridLines) ImageVector.vectorResource(R.drawable.ic_grid_off)
                                else ImageVector.vectorResource(R.drawable.ic_grid_on),
                                    tint = Color.White,contentDescription = if (showGridLines) stringResource(
                                        R.string.grid_lines_hide_description
                                    ) else stringResource(R.string.show_grid_lines_description)
                                )
                            }
                            IconButton(onClick = {
                                cameraViewModel.toggleTorchForVideo()

                            }) {
                                Icon(imageVector = if (torchOn) ImageVector.vectorResource(R.drawable.ic_flash_on)
                                else ImageVector.vectorResource(R.drawable.ic_flash_off),
                                    tint = if (torchOn) AccentGreen else Color.White,contentDescription = if (torchOn) stringResource(
                                        R.string.turn_flash_off
                                    ) else stringResource(R.string.turn_flash_on)
                                )
                            }
                            IconButton(onClick = { cameraViewModel.toggleAudioMuted() }) {
                                MicIcon(muted = audioMuted)
                            }
                            IconButton(onClick = { cameraViewModel.setProMode(!proMode) }) {
                                Text(
                                    stringResource(R.string.pro_mode),
                                    color = if (proMode) AccentGreen else Color.White,
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.semantics {
                                        contentDescription = proModeDescription
                                    }
                                )
                            }
                            IconButton(onClick = {
                                showNoteDialog = true
                            }) {
                                Icon(imageVector = if (torchOn) ImageVector.vectorResource(R.drawable.ic_note)
                                else ImageVector.vectorResource(R.drawable.ic_note), contentDescription = stringResource(R.string.edit_note)
                                )
                            }
                        }
                    }
                    // remember bottom bg to be able

                        ConstraintLayout(modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Transparent),
                            constraintSet = constraints
                        ) {
                            // Common modifier for grid lines
                            val lineModifier = Modifier
                                .alpha(if (showGridLines) 1f else 0f)
                                .background(GridLine)

                            // Subtle top scrim so the controls stay legible over any scene
                            Box(modifier = Modifier
                                .layoutId("topScrim")
                                .then(
                                    if (isPortrait) Modifier.fillMaxWidth().height(140.dp)
                                    else Modifier.fillMaxHeight().width(140.dp)
                                )
                                .background(topScrimBrush)
                            )

                            AnimatedVisibility(visible = (recordingState == RecordingState.Idle || recordingState == RecordingState.Stopped),
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .layoutId("topBar")
                                    .then(
                                        if (isPortrait) Modifier.fillMaxWidth()
                                        else Modifier.fillMaxHeight()
                                    )
                            ){
                                if(isPortrait){
                                    Row(modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly) {
                                        topBarButtons()
                                    }
                                } else {
                                    Column(modifier = Modifier
                                        .fillMaxHeight()
                                        .padding(horizontal = 16.dp),
                                        verticalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        topBarButtons()
                                    }
                                }

                            }

                            AnimatedVisibility(visible = elapsedTime.isNotEmpty(),
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier.layoutId("chipTimer")) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ElevatedAssistChip(onClick = {}, label = { Text(elapsedTime) })
                                    Spacer(Modifier.width(8.dp))
                                    // Mute stays reachable mid-take (the top bar is hidden
                                    // while recording); the meter shows the mic is live.
                                    AudioLevelControl(
                                        muted = audioMuted,
                                        level = audioLevel,
                                        onToggleMute = { cameraViewModel.toggleAudioMuted() }
                                    )
                                }
                            }

                            // Pro bar: floats just above the record button and stays up while
                            // recording, so exposure, focus and white balance can be ridden
                            // mid-take. Its slider opens underneath, pushing the bar up.
                            AnimatedVisibility(
                                visible = proMode,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier.layoutId("proControls")
                            ) {
                                ProControls(
                                    pro = cameraViewModel.pro,
                                    exposureState = exposureState,
                                    exposureIndex = exposureIndex,
                                    onExposureIndexChange = { cameraViewModel.updateExposureCompensation(it) },
                                    modifier = if (proLandscape)
                                        Modifier.quarterTurn(clockwise = proOnStartEdge).fillMaxWidth()
                                    else
                                        Modifier.fillMaxWidth()
                                )
                            }

                            Box(modifier = Modifier
                                .layoutId("bottomBg")
                                then(
                                    if (isPortrait) Modifier.fillMaxWidth().height(dimensionResource(R.dimen.transparent_view_height))
                                    else Modifier.fillMaxHeight().width(dimensionResource(R.dimen.transparent_view_height))
                                )
                                .background(bottomScrimBrush)
                            )

                            Image(
                                painter = painterResource(R.drawable.proofmoderound),
                                contentDescription = "ProofMode settings",
                                alpha  = 0.5f,
                                modifier = Modifier
                                    .layoutId("logo")
                                    .size(32.dp)
                                    .clickable {
                                        context.startActivity(
                                            Intent().setClassName(
                                                context, "org.witness.proofmode.SettingsActivity"
                                            )
                                        )
                                    }
                            )

                            Image(
                                painter = painterResource(R.drawable.crlogo),
                                contentDescription = "Content Credentials signing settings",
                                alpha  = 0.5f,
                                modifier = Modifier
                                    .layoutId("crLogo")
                                    .size(32.dp)
                                    .clickable {
                                        context.startActivity(
                                            Intent().setClassName(
                                                context, "org.witness.proofmode.SigningSettingsActivity"
                                            )
                                        )
                                    }
                            )



                            RecordButton(
                                modifier = Modifier.layoutId("recordButton"),
                                isRecording = isRecording,
                                onClick = {
                                    if (recordingState == RecordingState.Idle || recordingState == RecordingState.Stopped) {
                                        if (!audioPermissionState.status.isGranted) {
                                            audioPermissionState.launchPermissionRequest()
                                        } else {
                                            cameraViewModel.startRecording()
                                        }
                                    } else {
                                        cameraViewModel.stopRecording()
                                    }
                                }
                            )

                            AnimatedVisibility(visible = recordingState != RecordingState.Recording,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier.layoutId("galleryPreview")){
                                Box(
                                    modifier = Modifier.secondaryControl()
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
                                                    onClose()
                                                }
                                                , media = media )
                                        } else {
                                            Icon(
                                                imageVector = ImageVector.vectorResource(R.drawable.ic_no_picture),
                                                contentDescription = "No media",
                                                tint = Color.Gray,
                                                modifier = Modifier
                                                    .align(Alignment.Center)
                                            )
                                        }
                                    }

                                }
                            }

                            // Stays available while recording: the take is a persistent
                            // recording, so switching lenses mid-shot continues into the
                            // same file rather than ending it.
                            IconButton(onClick = {
                                scope.launch {
                                    cameraViewModel.switchLensFacing(lifecycleOwner,CameraMode.VIDEO)
                                }

                            },
                                modifier = Modifier
                                    .layoutId("cameraSwitcher")
                                    .secondaryControl()
                            ) {
                                Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_switch),
                                    tint = Color.White,
                                    contentDescription = stringResource(R.string.cb_switch))
                            }

                            AnimatedVisibility(
                                visible = recordingState != RecordingState.Recording,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .layoutId("modeToggle")
                                    .rotate(if (isPortrait) 0f else -90f)
                            ) {
                                CameraModeToggle(
                                    selectedMode = CameraModeSelection.VIDEO,
                                    onModeSelected = { mode ->
                                        if (mode == CameraModeSelection.PHOTO) {
                                            onNavigateToPhotoCamera()
                                        }
                                    }
                                )
                            }

                            // 1/3 vertical grid line
                            Box(
                                modifier = lineModifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .layoutId("verticalLine1")
                            )
                            // 2/3 vertical grid line
                            Box(
                                modifier = lineModifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .layoutId("verticalLine2")
                            )

                            // Horizontal grid lines
                            // 1/3 horizontal grid line
                            Box(
                                modifier = lineModifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .layoutId("horizontalLine1")
                            )
                            // 2/3 horizontal grid line
                            Box(
                                modifier = lineModifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .layoutId("horizontalLine2")
                            )



                        }

                }
            }

            if (showBSettingsBottomSheet){
                ModalBottomSheet(onDismissRequest = {
                    showBSettingsBottomSheet = false
                }, sheetState = settingsSheetState) {

                    Text(
                        stringResource(R.string.video_settings),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

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
                                .background(Color.White.copy(alpha = 0.1f), CircleShape)) {
                                Icon(painter = painterResource(it.toIconRes()), contentDescription = it.getName(),
                                    tint = if (selectedQuality == it) AccentGreen else Color.White )
                            }
                            Spacer(modifier = Modifier.width(5.dp))
                        }

                    }

                    // Frame rate: only the fixed rates this lens supports. The rate actually
                    // bound is highlighted, which can differ from the saved choice after a
                    // switch to a lens that can't do it.
                    if (videoFrameRates.size > 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.video_frame_rate), style = MaterialTheme.typography.bodySmall)
                                activeVideoFps?.let {
                                    Text(stringResource(R.string.video_fps, it), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            videoFrameRates.forEach { fps ->
                                val isSelectedFps = activeVideoFps == fps
                                Box(modifier = Modifier
                                    .padding(start = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelectedFps) AccentGreen else Color.White.copy(alpha = 0.1f))
                                    .clickable(enabled = !isRecording) {
                                        scope.launch { cameraViewModel.changeVideoFps(fps, lifecycleOwner) }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        fps.toString(),
                                        color = if (isSelectedFps) CameraBlack else Color.White,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                        }
                    }

                    if (stabilizationSupported) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.video_stabilization), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.video_stabilization_description), style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = videoStabilization,
                                enabled = !isRecording,
                                onCheckedChange = { enabled ->
                                    scope.launch { cameraViewModel.setVideoStabilization(enabled, lifecycleOwner) }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(40.dp))
                }
            }
        }
    }

    if (showNoteDialog) {
        NoteDialog(onDismiss = { showNoteDialog = false })
    }
}


private fun portraitConstraints(proLandscape: Boolean, proOnStartEdge: Boolean): ConstraintSet {
    return ConstraintSet {
        val topScrim = createRefFor("topScrim")
        val topBar = createRefFor("topBar")
        val bottomBg = createRefFor("bottomBg")
        val recordButton = createRefFor("recordButton")
        val cameraSwitcher = createRefFor("cameraSwitcher")
        val galleryPreview = createRefFor("galleryPreview")
        val modeToggle = createRefFor("modeToggle")
        val chipTimer = createRefFor("chipTimer")
        val logo = createRefFor("logo")
        val crLogo = createRefFor("crLogo")

        // Guidelines
        val vertical1 = createGuidelineFromStart(0.33f)
        val vertical2 = createGuidelineFromStart(0.66f)
        val horizontal1 = createGuidelineFromTop(0.33f)
        val horizontal2 = createGuidelineFromTop(0.66f)

        // Constraints
        constrain(topScrim) {
            top.linkTo(parent.top)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
        }

        constrain(topBar) {
            top.linkTo(parent.top, margin = 60.dp)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
        }

        constrain(bottomBg) {
            bottom.linkTo(parent.bottom)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
        }

        constrain(recordButton) {
            top.linkTo(bottomBg.top)
            bottom.linkTo(bottomBg.bottom)
            start.linkTo(bottomBg.start)
            end.linkTo(bottomBg.end)
            verticalBias = 0.2f
        }

        val proControls = createRefFor("proControls")
        constrain(proControls) {
            if (proLandscape) {
                // Run between the top bar and the record button row.
                top.linkTo(parent.top, margin = 116.dp)
                bottom.linkTo(bottomBg.top)
                height = Dimension.fillToConstraints
                if (proOnStartEdge) start.linkTo(parent.start, margin = 8.dp)
                else end.linkTo(parent.end, margin = 8.dp)
            } else {
                bottom.linkTo(recordButton.top, margin = 12.dp)
                start.linkTo(parent.start, margin = 12.dp)
                end.linkTo(parent.end, margin = 12.dp)
                width = Dimension.fillToConstraints
            }
        }

        constrain(chipTimer) {
            // Above the Pro bar when it's up in portrait; collapses onto the scrim edge
            // otherwise, since a hidden bar measures zero.
            if (proLandscape) bottom.linkTo(bottomBg.top, 8.dp)
            else bottom.linkTo(proControls.top, 8.dp)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
        }

        constrain(galleryPreview) {
            top.linkTo(recordButton.top)
            bottom.linkTo(recordButton.bottom)
            end.linkTo(recordButton.start)
            start.linkTo(parent.start)
        }

        constrain(cameraSwitcher) {
            top.linkTo(recordButton.top)
            bottom.linkTo(recordButton.bottom)
            start.linkTo(recordButton.end)
            end.linkTo(parent.end)
        }

        constrain(modeToggle) {
            start.linkTo(parent.start)
            end.linkTo(parent.end)
            top.linkTo(recordButton.bottom, 8.dp)
        }

        constrain(logo) {
            start.linkTo(parent.start, margin = 12.dp)
            bottom.linkTo(parent.bottom, margin = 36.dp)
        }

        constrain(crLogo) {
            end.linkTo(parent.end, margin = 12.dp)
            bottom.linkTo(parent.bottom, margin = 36.dp)
        }

        // Grid lines (optional, since they're visibility controlled)
        val verticalLine1 = createRefFor("verticalLine1")
        val verticalLine2 = createRefFor("verticalLine2")
        val horizontalLine1 = createRefFor("horizontalLine1")
        val horizontalLine2 = createRefFor("horizontalLine2")

        constrain(verticalLine1) {
            start.linkTo(vertical1)
        }

        constrain(verticalLine2) {
            start.linkTo(vertical2)
        }

        constrain(horizontalLine1) {
            top.linkTo(horizontal1)
        }

        constrain(horizontalLine2) {
            top.linkTo(horizontal2)
        }
    }
}


private fun landscapeConstraints(): ConstraintSet {
    return ConstraintSet {
        val topScrim = createRefFor("topScrim")
        val topBar = createRefFor("topBar")
        val bottomBg = createRefFor("bottomBg")
        val recordButton = createRefFor("recordButton")
        val cameraSwitcher = createRefFor("cameraSwitcher")
        val galleryPreview = createRefFor("galleryPreview")
        val modeToggle = createRefFor("modeToggle")
        val chipTimer = createRefFor("chipTimer")
        val logo = createRefFor("logo")
        val crLogo = createRefFor("crLogo")

        // Guidelines
        val vertical1 = createGuidelineFromStart(0.33f)
        val vertical2 = createGuidelineFromStart(0.66f)
        val horizontal1 = createGuidelineFromTop(0.33f)
        val horizontal2 = createGuidelineFromTop(0.66f)

        // Constraints
        constrain(topScrim) {
            top.linkTo(parent.top)
            bottom.linkTo(parent.bottom)
            start.linkTo(parent.start)
        }

        constrain(topBar) {
            top.linkTo(parent.top)
            bottom.linkTo(parent.bottom)
            start.linkTo(parent.start, margin = 60.dp)
        }

        constrain(bottomBg) {
            bottom.linkTo(parent.bottom)
            bottom.linkTo(parent.bottom)
            end.linkTo(parent.end)
        }

        constrain(recordButton) {
            start.linkTo(bottomBg.start)
            bottom.linkTo(parent.bottom)
            top.linkTo(parent.top)
            end.linkTo(bottomBg.end)
            horizontalBias = 0.3f
        }

        constrain(galleryPreview) {
            top.linkTo(recordButton.bottom)
            bottom.linkTo(bottomBg.bottom)
            start.linkTo(recordButton.start)
            end.linkTo(recordButton.end)
        }

        constrain(chipTimer) {
            bottom.linkTo(bottomBg.top, 8.dp)
            start.linkTo(parent.start)
            end.linkTo(parent.end)
        }

        constrain(createRefFor("proControls")) {
            top.linkTo(parent.top, margin = 16.dp)
            start.linkTo(topBar.end, margin = 8.dp)
            end.linkTo(bottomBg.start, margin = 8.dp)
            width = Dimension.fillToConstraints
        }

        constrain(cameraSwitcher) {
            top.linkTo(parent.top)
            bottom.linkTo(recordButton.top)
            end.linkTo(recordButton.end)
            start.linkTo(recordButton.start)
        }

        constrain(modeToggle) {
            start.linkTo(recordButton.end, margin = 8.dp)
            top.linkTo(recordButton.top)
            bottom.linkTo(recordButton.bottom)
        }

        constrain(logo) {
            start.linkTo(parent.start, margin = 12.dp)
            bottom.linkTo(parent.bottom, margin = 36.dp)
        }

        constrain(crLogo) {
            end.linkTo(parent.end, margin = 12.dp)
            bottom.linkTo(parent.bottom, margin = 36.dp)
        }

        // Grid lines (optional, since they're visibility controlled)
        val verticalLine1 = createRefFor("verticalLine1")
        val verticalLine2 = createRefFor("verticalLine2")
        val horizontalLine1 = createRefFor("horizontalLine1")
        val horizontalLine2 = createRefFor("horizontalLine2")

        constrain(verticalLine1) {
            start.linkTo(vertical1)
        }

        constrain(verticalLine2) {
            start.linkTo(vertical2)
        }

        constrain(horizontalLine1) {
            top.linkTo(horizontal1)
        }

        constrain(horizontalLine2) {
            top.linkTo(horizontal2)
        }
    }
}


@Composable
private fun MicIcon(muted: Boolean, modifier: Modifier = Modifier) {
    Icon(
        imageVector = ImageVector.vectorResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic),
        tint = if (muted) InactiveWhite else Color.White,
        contentDescription = stringResource(if (muted) R.string.unmute_audio else R.string.mute_audio),
        modifier = modifier
    )
}

/** Mic mute toggle with a small live level bar, shown beside the timer while recording. */
@Composable
private fun AudioLevelControl(muted: Boolean, level: Float, onToggleMute: () -> Unit) {
    // Amplitude is linear and speech sits low on it; a square root spreads it out so
    // normal talking reads as a healthy bar rather than a sliver.
    val shown by animateFloatAsState(
        targetValue = if (muted) 0f else kotlin.math.sqrt(level.coerceIn(0f, 1f)),
        label = "audioLevel"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xB3000000))
            .clickable(onClick = onToggleMute)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        MicIcon(muted = muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shown)
                    .background(if (shown > 0.9f) Color(0xFFFF5252) else AccentGreen)
            )
        }
    }
}
