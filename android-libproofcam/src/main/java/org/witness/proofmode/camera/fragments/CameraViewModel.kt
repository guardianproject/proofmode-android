@file:Suppress("DEPRECATION")

package org.witness.proofmode.camera.fragments

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExposureState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCapture.FlashMode
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.core.ImageCapture.OnImageSavedCallback
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.witness.proofmode.LocationCapturePolicy
import org.witness.proofmode.ProofMode
import org.witness.proofmode.c2pa.proofsign.CaptureAuthority
import org.witness.proofmode.camera.DeviceOrientationProvider
import org.witness.proofmode.camera.adapter.Media
import org.witness.proofmode.camera.utils.SharedPrefsManager
import org.witness.proofmode.camera.utils.getMediaFlow
import org.witness.proofmode.camera.utils.getSupportedQualities
import org.witness.proofmode.camera.utils.isUltraHdrSupported
import org.witness.proofmode.service.MediaWatcher.Companion.getInstance
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

class CameraViewModel(private val app: Application) : AndroidViewModel(app) {
    private val orientationProvider = DeviceOrientationProvider(app)

    val deviceRotation = orientationProvider.rotation
    private val sharedPrefsManager = SharedPrefsManager.newInstance(app.applicationContext)
    private val outputDirectory: String by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${Environment.DIRECTORY_DCIM}/ProofMode/"
        } else {
            "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}/ProofMode/"
        }
    }
    private var _mediaFiles:MutableStateFlow<List<Media>> = MutableStateFlow(emptyList())
    val mediaFiles: StateFlow<List<Media>> = _mediaFiles
    private val mExec = Executors.newSingleThreadExecutor()
    private var surfaceOrientedMeteringPointFactory:SurfaceOrientedMeteringPointFactory? = null
    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest
    // Used for navigating to the preview page. Supposed to have content credentials attached if enabled
    private var _lastCapturedMedia: MutableStateFlow<Media?> = MutableStateFlow(null)
    val lastCapturedMedia: StateFlow<Media?> = _lastCapturedMedia
    // Used for rounded thumbnail to immediately show when an image or video is captured
    var _thumbPreviewUri = MutableStateFlow<Media?>(null)
    val thumbPreviewUri: StateFlow<Media?> = _thumbPreviewUri


    private val _cameraQualities = MutableStateFlow<List<Quality>>(emptyList())
    val cameraQualities: StateFlow<List<Quality>> = _cameraQualities

    private var _exposureState:MutableStateFlow<ExposureState?> = MutableStateFlow(null)
    val exposureState: StateFlow<ExposureState?> = _exposureState
    // ExposureState is an immutable snapshot taken at bind time, so setting a new
    // compensation index does not update it. The UI slider reads this instead.
    private val _exposureIndex:MutableStateFlow<Int> = MutableStateFlow(0)
    val exposureIndex: StateFlow<Int> = _exposureIndex
    private var _cameraDelay:MutableStateFlow<CameraDelay> = MutableStateFlow(CameraDelay.Zero)
    val cameraDelay: StateFlow<CameraDelay> = _cameraDelay
    private val previewUseCase = Preview.Builder()
        .build().apply {
        setSurfaceProvider { newSurfaceRequest->
            _surfaceRequest.update { newSurfaceRequest }
            surfaceOrientedMeteringPointFactory = SurfaceOrientedMeteringPointFactory(
                newSurfaceRequest.resolution.width.toFloat(),
                newSurfaceRequest.resolution.height.toFloat()
            )

        }

    }
    init {
        loadMediaFiles()

    }


    private fun loadMediaFiles() {
        viewModelScope.launch {
            getMediaFlow(app.applicationContext,outputDirectory)
                .collect{ media->
                    _thumbPreviewUri.value = media.firstOrNull()
                    _mediaFiles.value = media
                    _lastCapturedMedia.value = media.firstOrNull()
                }
        }
    }

    fun updateCameraDelay(delay: CameraDelay) {
        _cameraDelay.update { delay }
    }

    fun deleteMedia(media: Media?) {
        viewModelScope.launch(Dispatchers.IO) {
            media?.let {
                // Get contentResolver
                val contentResolver = app.applicationContext.contentResolver

                // Delete the media from the content provider (MediaStore)
                val rowsDeleted = contentResolver.delete(it.uri, null, null)

                if (rowsDeleted > 0) {
                    // If deletion is successful, remove from the local media list
                    val currentList = _mediaFiles.value.toMutableList()
                    currentList.remove(it)  // Remove the media from the list
                    _mediaFiles.value = currentList  // Update the media list

                    // If the deleted media was the last captured media, update accordingly
                    if (_lastCapturedMedia.value == it) {
                        _lastCapturedMedia.value = currentList.firstOrNull()
                    }
                } else {
                    // Handle failure to delete from the content provider if necessary
                    Timber.e("Failed to delete media from content provider: ${it.uri}")
                }
            }
        }
    }
    private val _previewAlpha = MutableStateFlow(1f)
    val previewAlpha: StateFlow<Float> = _previewAlpha

    private val _shutterFlashTrigger = MutableStateFlow(0)
    val shutterFlashTrigger: StateFlow<Int> = _shutterFlashTrigger

    private val _locationEnabled = MutableStateFlow(
        LocationCapturePolicy.shouldEmbedLocation(app.applicationContext)
    )
    val locationEnabled: StateFlow<Boolean> = _locationEnabled

    private val _requestLocationPermission = MutableStateFlow(0)
    val requestLocationPermission: StateFlow<Int> = _requestLocationPermission

    fun toggleLocationEnabled() {
        if (_locationEnabled.value) {
            setLocationEnabled(false)
        } else {
            if (LocationCapturePolicy.hasOsLocationPermission(app.applicationContext)) {
                setLocationEnabled(true)
            } else {
                _requestLocationPermission.update { it + 1 }
            }
        }
    }

    fun setLocationEnabled(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(app.applicationContext)
            .edit()
            .putBoolean(ProofMode.PREF_OPTION_LOCATION, enabled)
            .apply()
        _locationEnabled.value = enabled
    }

    fun refreshLocationPermissionState() {
        _locationEnabled.value =
            LocationCapturePolicy.shouldEmbedLocation(app.applicationContext)
    }



    var lensFacing: MutableLiveData<Int> = MutableLiveData(
        sharedPrefsManager.getInt(SharedPrefsManager.KEY_LENS_FACING, CameraSelector.LENS_FACING_BACK)
    )
        private set
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timerRunnable: Runnable
    private var startTime: Long = 0

    // LiveData to expose the elapsed time
    private val _elapsedTime = MutableLiveData<String>()
    val elapsedTime: LiveData<String> get() = _elapsedTime

    private var _recordTime = MutableStateFlow<String>("")
    val recordTime: StateFlow<String> = _recordTime

    private var cameraProvider: ProcessCameraProvider? = null
    private var recorder: Recorder? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var cameraControl: CameraControl? = null

    // Recording state
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> get() = _recordingState
    private val _flashMode = MutableStateFlow(ImageCapture.FLASH_MODE_OFF)
    val flashMode: StateFlow<Int> = _flashMode

    private var _torchOn = MutableStateFlow(false)
    val torchOn: StateFlow<Boolean> = _torchOn
    private var _supportedFrameRates = MutableStateFlow(emptySet<Range<Int>>())
    val supportedFrameRates: StateFlow<Set<Range<Int>>> = _supportedFrameRates
    var zoomState: LiveData<ZoomState?> = MutableLiveData(null)
        private set

    // Reactive zoom values for the Compose zoom control. zoomState (LiveData) is
    // reassigned on every (re)bind, which makes it awkward to observe; these flows
    // are refreshed after each bind and on every zoom change so the preset bubbles
    // and slider always reflect the live ratio.
    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio
    private val _minZoomRatio = MutableStateFlow(1f)
    val minZoomRatio: StateFlow<Float> = _minZoomRatio
    private val _maxZoomRatio = MutableStateFlow(1f)
    val maxZoomRatio: StateFlow<Float> = _maxZoomRatio

    // Still-capture framing & quality. Seeded from prefs so the user's last choice
    // survives app restarts; persisted again in changeAspectRatio / changePhotoQuality.
    // The enum is stored by name(), with a fallback in case a stored value is ever
    // renamed or removed.
    private val _photoAspectRatio = MutableStateFlow(
        runCatching {
            PhotoAspectRatio.valueOf(
                sharedPrefsManager.getString(
                    SharedPrefsManager.KEY_PHOTO_ASPECT_RATIO, PhotoAspectRatio.RATIO_4_3.name
                )
            )
        }.getOrDefault(PhotoAspectRatio.RATIO_4_3)
    )
    val photoAspectRatio: StateFlow<PhotoAspectRatio> = _photoAspectRatio
    private val _photoQuality = MutableStateFlow(
        runCatching {
            PhotoQuality.valueOf(
                sharedPrefsManager.getString(
                    SharedPrefsManager.KEY_PHOTO_QUALITY, PhotoQuality.HIGH.name
                )
            )
        }.getOrDefault(PhotoQuality.HIGH)
    )
    val photoQuality: StateFlow<PhotoQuality> = _photoQuality

    // Selected quality
    private val _selectedQuality = MutableStateFlow<Quality?>(null) // Default to FHD (1080p)
    val selectedQuality: StateFlow<Quality?> get() = _selectedQuality

    private var _ultraHdr = MutableStateFlow(UltraHDRAvailabilityState.OFF)
    val ultraHdr: StateFlow<UltraHDRAvailabilityState> = _ultraHdr

    private val imageCaptureBuilder = ImageCapture.Builder()
        .setJpegQuality(100)
    private var imageCapture: ImageCapture? = null

    fun toggleTorchForVideo() {
        val previousTorchState = torchOn.value
        _torchOn.value = !previousTorchState
        applyTorchState()
    }

    /**
     * Re-applies the user's torch selection to whatever camera is currently bound.
     * The desired state is kept in [_torchOn] even when the bound camera has no flash
     * unit (typically the front lens), so switching back to the rear camera restores it.
     */
    private fun applyTorchState() {
        if (camera?.cameraInfo?.hasFlashUnit() == true) {
            cameraControl?.enableTorch(_torchOn.value)
        }
    }


    fun toggleFlashMode( @FlashMode newMode: Int, lifecycleOwner: LifecycleOwner) {
        _flashMode.value = newMode
        imageCaptureBuilder.setFlashMode(flashMode.value)
        bindImageUseCases(lifecycleOwner,deviceRotation.value)
    }

    /**
     * Builds an [ImageCapture] reflecting the current aspect ratio, JPEG quality
     * and Ultra HDR selection. The shared [imageCaptureBuilder] retains the flash
     * mode set elsewhere.
     */
    private fun buildImageCapture(): ImageCapture {
        val aspect = _photoAspectRatio.value
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                if (aspect.baseAspectRatio == AspectRatio.RATIO_16_9)
                    AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                else
                    AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            )
            .build()
        return imageCaptureBuilder
            .setResolutionSelector(resolutionSelector)
            .setJpegQuality(_photoQuality.value.jpegQuality)
            .apply {
                setOutputFormat(
                    if (ultraHdr.value == UltraHDRAvailabilityState.ON)
                        ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR
                    else
                        ImageCapture.OUTPUT_FORMAT_JPEG
                )
            }
            .build()
    }

    /**
     * (Re)binds the preview + image-capture use cases as a [UseCaseGroup] sharing a
     * [ViewPort] of the selected aspect ratio, so the preview and the saved file are
     * framed identically (WYSIWYG). The viewport's crop rect is what yields a true
     * square output for 1:1 — CameraX has no native 1:1 aspect-ratio strategy.
     */
    private fun bindImageUseCases(lifecycleOwner: LifecycleOwner,rotation: Int) {
        val provider = cameraProvider ?: return
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing.value ?: CameraSelector.LENS_FACING_BACK)
            .build()
        provider.unbind(imageCapture)
        imageCapture = buildImageCapture()
        // A ViewPort's aspect ratio is expressed in the *output* (rotated) orientation,
        // so the rational must follow how the device is held: portrait inverts the
        // landscape sensor rational (16:9 -> 9:16) for a tall crop, landscape keeps it
        // as-is. Without this the saved crop comes out wide even in portrait.
        val baseRational = _photoAspectRatio.value.rational
        val isPortrait = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
        val orientedRational = if (isPortrait)
            Rational(baseRational.denominator, baseRational.numerator)
        else
            baseRational
        val viewPort = ViewPort.Builder(orientedRational, rotation).build()
        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(previewUseCase)
            .addUseCase(imageCapture!!)
            .setViewPort(viewPort)
            .build()
        try {
            camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, useCaseGroup)
            zoomState = camera!!.cameraInfo.zoomState
            cameraControl = camera?.cameraControl
            refreshZoomState()
            refreshExposureState()
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to bind image use cases")
        }
    }

    /** Switch the still-capture framing (4:3 / 16:9 / 1:1) and rebind. */
    suspend fun changeAspectRatio(aspect: PhotoAspectRatio, lifecycleOwner: LifecycleOwner) {
        if (_photoAspectRatio.value == aspect) return
        _photoAspectRatio.update { aspect }
        sharedPrefsManager.putString(SharedPrefsManager.KEY_PHOTO_ASPECT_RATIO, aspect.name)
        _previewAlpha.update { 0.5f }
        bindImageUseCases(lifecycleOwner,deviceRotation.value)
        delay(250)
        _previewAlpha.update { 1f }
    }

    /** Switch JPEG quality (High / Standard) and rebind. */
    fun changePhotoQuality(quality: PhotoQuality, lifecycleOwner: LifecycleOwner) {
        if (_photoQuality.value == quality) return
        _photoQuality.update { quality }
        sharedPrefsManager.putString(SharedPrefsManager.KEY_PHOTO_QUALITY, quality.name)
        bindImageUseCases(lifecycleOwner,deviceRotation.value)
    }




    fun pinchZoom(zoom: Float) {
        val zoomState = camera?.cameraInfo?.zoomState?.value
        if (zoomState != null) {
            val maxZoomRatio = zoomState.maxZoomRatio
            val minZoomRatio = zoomState.minZoomRatio
            val currentZoomRatio = zoomState.zoomRatio
            val newZoomRatio = (currentZoomRatio * zoom).coerceIn(minZoomRatio, maxZoomRatio)
            cameraControl?.setZoomRatio(newZoomRatio)
            _zoomRatio.value = newZoomRatio
        }
    }

    /** Jump to (or smoothly drive, from the slider) an absolute zoom ratio. */
    fun setZoomRatio(target: Float) {
        val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
        val clamped = target.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        cameraControl?.setZoomRatio(clamped)
        _zoomRatio.value = clamped
    }

    /** Pull the live min/current/max zoom out of the camera after a (re)bind. */
    private fun refreshZoomState() {
        camera?.cameraInfo?.zoomState?.value?.let { zs ->
            _minZoomRatio.value = zs.minZoomRatio
            _maxZoomRatio.value = zs.maxZoomRatio
            _zoomRatio.value = zs.zoomRatio
        }
    }

    suspend fun changeQuality(quality:Quality,lifecycleOwner: LifecycleOwner) {
        _selectedQuality.update{ quality}
        cameraProvider?.unbindAll()
        _previewAlpha.update { 0.5f }
        delay(800)
        _previewAlpha.update { 1f }

        videoCapture = null
        recorder = null
        recorder = Recorder.Builder()

            .setQualitySelector(QualitySelector.from(_selectedQuality.value!!, FallbackStrategy.lowerQualityThan(
                _selectedQuality.value!!)))
            .build()
        videoCapture = VideoCapture.withOutput(recorder!!).apply {
            targetRotation = deviceRotation.value
        }
        try {
            camera = cameraProvider!!.bindToLifecycle(lifecycleOwner = lifecycleOwner,CameraSelector.Builder().requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build(),
                previewUseCase,videoCapture)
            camera?.cameraInfo?.supportedFrameRateRanges.let { ranges->
                _supportedFrameRates.update { ranges as Set<Range<Int>> }

            }
            zoomState = camera!!.cameraInfo.zoomState
            cameraControl = camera?.cameraControl
            applyTorchState()
            refreshExposureState()
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to bind video capture with quality $quality")
        }

    }


suspend fun bindUseCasesForVideo(lifecycleOwner: LifecycleOwner) {
    cameraProvider = (cameraProvider?: ProcessCameraProvider.awaitInstance(app.applicationContext)).also {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build()
        val qualities = getSupportedQualities(cameraSelector,it)
        _cameraQualities.update { qualities }
        it.unbindAll()
    }
    //cameraProvider?.unbindAll()
    recorder = Recorder.Builder()
        .apply {
            selectedQuality.value?.let {
                setQualitySelector(QualitySelector.from(it, FallbackStrategy.lowerQualityThan(it)))
            }
        }
        .build()
    videoCapture = VideoCapture
        .withOutput(recorder!!)
        .apply {
            targetRotation = deviceRotation.value
        }
    try {
        camera = cameraProvider!!.bindToLifecycle(lifecycleOwner = lifecycleOwner,CameraSelector.Builder().requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build(),
            previewUseCase,videoCapture)
        zoomState = camera!!.cameraInfo.zoomState
        cameraControl = camera?.cameraControl
        applyTorchState()
        refreshExposureState()
    } catch (ex:Exception){
        Timber.e("Binding failed")
    }


}
    suspend fun bindUseCasesForImage(lifecycleOwner: LifecycleOwner) {
        // Attach the provider to the current UI lifecycle
        lifecycleOwner.lifecycle.addObserver(orientationProvider)
        cameraProvider = cameraProvider?: ProcessCameraProvider.awaitInstance(app.applicationContext)
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build()
        if (!isUltraHdrSupported(cameraSelector, cameraProvider!!)) {
            _ultraHdr.update { UltraHDRAvailabilityState.NOT_SUPPORTED }
        }
        bindImageUseCases(lifecycleOwner,deviceRotation.value)
    }

    suspend fun toggleUltraHdr(lifecycleOwner: LifecycleOwner) {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build()
        val isUltraHdrSupported = isUltraHdrSupported(cameraSelector,cameraProvider!!)
        if (!isUltraHdrSupported) {
            _ultraHdr.update { UltraHDRAvailabilityState.NOT_SUPPORTED }
        } else {
            if (ultraHdr.value == UltraHDRAvailabilityState.ON) {
                _ultraHdr.update { UltraHDRAvailabilityState.OFF }
            } else {
                _ultraHdr.update { UltraHDRAvailabilityState.ON }
            }
            _previewAlpha.update { 0.5f }
            delay(800)
            _previewAlpha.update { 1f }
            bindImageUseCases(lifecycleOwner,deviceRotation.value)
        }

    }



    fun captureImage() {
        val rotation = deviceRotation.value
        _shutterFlashTrigger.update { it + 1 }

        val metadata = Metadata().apply {
            isReversedHorizontal = false //do not mirror
            // Mirror image when using the front camera
            //    lensFacing.value == CameraSelector.LENS_FACING_FRONT

            // Embed GPS into the JPEG EXIF at capture time when location is enabled.
            // CameraX writes these tags into the file before it is C2PA-signed downstream.
            // Same Ideal recipe as C2PAManager / MediaWatcher (LocationCapturePolicy.shouldEmbedLocation).
            if (LocationCapturePolicy.shouldEmbedLocation(app.applicationContext)) {
                location = ProofMode.getLatestLocation(app.applicationContext)
            }
        }

        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // Options fot the output image file
        val outputOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, System.currentTimeMillis())
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, outputDirectory)
            }

            val contentResolver = app.contentResolver

            // Create the output uri
            val contentUri =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            OutputFileOptions.Builder(contentResolver, contentUri, contentValues)
        } else {

            File(outputDirectory).mkdirs()
            val fileMedia = File(outputDirectory, "${System.currentTimeMillis()}.jpg")
            OutputFileOptions.Builder(fileMedia)
        }.setMetadata(metadata).build()

        imageCapture?.targetRotation = rotation

        imageCapture?.takePicture(
            outputOptions,
            mExec,
            object : OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri

                    // Create a temporary image to immediately show in thumbnail.
                    savedUri?.let {
                        val capturedTime = System.currentTimeMillis()
                        val newMedia = Media(it, false, capturedTime)
                        _thumbPreviewUri.value = newMedia

                        _lastCapturedMedia.value = newMedia
                        _mediaFiles.value = listOf(newMedia) + mediaFiles.value

                        CoroutineScope(Dispatchers.IO).launch {
                            sendLocalCameraEvent(it, CameraEventType.NEW_IMAGE)

                        }
                    }

                }

                override fun onError(exception: ImageCaptureException) {
                    Timber.e("Error capturing image")
                }
            }

        )
    }


    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalPersistentRecording::class)
    fun startRecording() {
        videoCapture?.targetRotation = deviceRotation.value

        if (recordingState.value != RecordingState.Idle && recordingState.value != RecordingState.Stopped) return
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, SimpleDateFormat("yyyy-MM-dd HH-mm:ss", Locale.US)
                .format(System.currentTimeMillis()))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/ProofMode")
        }
        startTimer()

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(app.applicationContext.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()
        // Persistent: the recording ignores the VideoCapture being unbound, which is what
        // lets switchLensFacing() rebind the same VideoCapture to the other lens mid-take
        // and keep writing both segments into this one file. Only an explicit stop()/close()
        // finalizes it, so every path out of recording must call stopRecording().
        recording = recorder?.prepareRecording(app.applicationContext,mediaStoreOutput)
            ?.asPersistentRecording()
            ?.withAudioEnabled()
            ?.start(ContextCompat.getMainExecutor(app.applicationContext)){ recordEvent->
                when(recordEvent) {
                    is VideoRecordEvent.Start-> {
                        _recordingState.update { RecordingState.Recording }
                    }
                    is VideoRecordEvent.Finalize -> {
                        stopTimer()
                        if (!recordEvent.hasError()) {

                            _recordingState.update {  RecordingState.Stopped}

                            CoroutineScope(Dispatchers.IO).launch {

                                val savedUri: Uri? = recordEvent.outputResults.outputUri
                                savedUri?.let {
                                    _thumbPreviewUri.value =
                                        Media(it, true, System.currentTimeMillis())


                                    val capturedTime = System.currentTimeMillis()
                                    sendLocalCameraEvent(
                                        it,
                                        CameraEventType.NEW_VIDEO
                                    )

                                    val newMedia = savedUri?.let {
                                        Media(
                                            it,
                                            true,
                                            capturedTime
                                        )
                                    }
                                    newMedia?.let {
                                        _lastCapturedMedia.value = it
                                        _mediaFiles.value = listOf(it) + mediaFiles.value
                                    }
                                }
                            }
                        } else {
                            _recordingState.update { RecordingState.Error("Recording finished with error") }
                        }
                    }
                }


            }


    }

    fun pauseRecording() {
        if (_recordingState.value == RecordingState.Recording) {
            recording?.pause()
            _recordingState.update { RecordingState.Paused }
        }
    }

    fun resumeRecording() {
        if (_recordingState.value == RecordingState.Paused) {
            recording?.resume()
            _recordingState.update { RecordingState.Recording }
        }
    }

    fun stopRecording() {
        if (_recordingState.value == RecordingState.Recording || _recordingState.value == RecordingState.Paused) {
            recording?.stop()
            _recordingState.update { RecordingState.Idle }
        }
    }


    private fun sendLocalCameraEvent(newMediaFile: Uri, cameraEventType: CameraEventType) {

        val mw = getInstance(app)
        var prefs = PreferenceManager.getDefaultSharedPreferences(app)

        try {

            app.sendBroadcast(
                Intent(
                    Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, newMediaFile
                )
            )
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }

        // Tell the Activities feed about this capture immediately, before any
        // (potentially slow) proof generation runs, so the item shows up right
        // away as PENDING. Proof status updates follow via PROOF_START /
        // PROOF_GENERATED keyed on this same media URI. Package-targeted so it
        // reaches our unexported ProofEventReceiver only.
        app.sendBroadcast(
            Intent(ProofMode.EVENT_MEDIA_CAPTURED).apply {
                setPackage(app.packageName)
                putExtra(ProofMode.EVENT_PROOF_EXTRA_URI, newMediaFile.toString())
            }
        )

        // Issue a capture-authorization nonce bound to the SHA-256 of the
        // file CameraX just wrote. The nonce travels with ingestMedia() and
        // is consumed in MediaWatcher before the C2PA signing call. An
        // attacker who drives signing via Frida without going through this
        // capture path will not have a valid nonce, and signing is refused.
        val captureNonce: ByteArray? = try {
            val digest = computeFileDigest(newMediaFile)
            digest?.let { CaptureAuthority.issueNonce(it) }
        } catch (e: Exception) {
            Timber.w(e, "failed to issue capture nonce for $newMediaFile")
            null
        }

        if (cameraEventType == CameraEventType.NEW_VIDEO) {

            if (!prefs.getBoolean(ProofMode.PREFS_DOPROOF,false))
                 mw?.ingestMedia(newMediaFile, true, null, "video/mp4", null, captureNonce)


        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {

                try {
                    val f = newMediaFile.toFile()
                    MediaStore.Images.Media.insertImage(
                        app.contentResolver,
                        f.absolutePath, f.name, null
                    )

                } catch (e: FileNotFoundException) {
                    e.printStackTrace()
                }
            }

            if (!prefs.getBoolean(ProofMode.PREFS_DOPROOF,false))
                mw?.ingestMedia(newMediaFile, true, null, "image/jpeg", null, captureNonce)

        }


    }

    private fun computeFileDigest(uri: Uri): ByteArray? {
        val md = MessageDigest.getInstance("SHA-256")
        val input = app.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }


    suspend fun switchLensFacing(lifecycleOwner: LifecycleOwner,cameraMode: CameraMode) {
        val previousFacing = lensFacing.value ?: CameraSelector.LENS_FACING_BACK
        val newFacing = if (previousFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        // Mid-take switch. The recording is persistent, so rebinding the *same*
        // VideoCapture/Recorder to the other lens keeps the encoder session alive and both
        // segments land in the one output file. Rebuilding them (as the idle path below
        // does) would orphan the in-flight recording, so this path must not touch them.
        if (cameraMode == CameraMode.VIDEO && isRecordingInProgress()) {
            if (rebindVideoForLensSwitch(lifecycleOwner, newFacing)) {
                lensFacing.value = newFacing
                sharedPrefsManager.putInt(SharedPrefsManager.KEY_LENS_FACING, newFacing)
            } else {
                // The other camera can't feed the in-flight encoder (usually an unsupported
                // resolution or surface combination). Put the original one back so the
                // recording keeps going rather than being left with no video source.
                if (!rebindVideoForLensSwitch(lifecycleOwner, previousFacing)) {
                    Timber.e("Lens switch failed and the original camera could not be restored; stopping recording")
                    stopRecording()
                }
            }
            return
        }

        lensFacing.value = newFacing
        sharedPrefsManager.putInt(SharedPrefsManager.KEY_LENS_FACING, newFacing)
        cameraProvider?.unbindAll()
        if (cameraMode == CameraMode.VIDEO) {
            bindUseCasesForVideo(lifecycleOwner)
        } else if (cameraMode == CameraMode.IMAGE) {
            bindUseCasesForImage(lifecycleOwner)
        }


    }

    private fun isRecordingInProgress(): Boolean =
        recording != null &&
                (_recordingState.value == RecordingState.Recording ||
                        _recordingState.value == RecordingState.Paused)

    /**
     * Rebinds the existing preview + [videoCapture] to [facing] without recreating either
     * use case. Returns false if the new camera cannot be bound, leaving nothing bound —
     * the caller is responsible for recovering.
     */
    private fun rebindVideoForLensSwitch(lifecycleOwner: LifecycleOwner, facing: Int): Boolean {
        val provider = cameraProvider ?: return false
        val capture = videoCapture ?: return false
        provider.unbindAll()
        return try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(facing).build(),
                previewUseCase,
                capture
            )
            // targetRotation is deliberately left alone: it was locked in at
            // startRecording() and the in-flight recording keeps that orientation.
            zoomState = camera!!.cameraInfo.zoomState
            cameraControl = camera?.cameraControl
            refreshZoomState()
            applyTorchState()
            refreshExposureState()
            true
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to rebind video capture to lens facing %d while recording", facing)
            false
        }
    }

    fun updateExposureCompensation(compensationIndex:Int){
        val state = _exposureState.value ?: return
        if (!state.isExposureCompensationSupported) return
        val clamped = compensationIndex.coerceIn(
            state.exposureCompensationRange.lower,
            state.exposureCompensationRange.upper
        )
        if (clamped == _exposureIndex.value) return
        _exposureIndex.update { clamped }
        cameraControl?.setExposureCompensationIndex(clamped)
    }

    /**
     * Re-read the exposure metadata from the freshly bound [camera].
     *
     * [ExposureState] belongs to a specific Camera instance and the compensation
     * index resets to 0 on every rebind (lens switch, aspect/quality change), so
     * both the range and the current index have to be pulled again or the slider
     * drifts out of sync with what the sensor is actually doing.
     */
    private fun refreshExposureState() {
        val cameraInfo = camera?.cameraInfo ?: return
        val state = cameraInfo.exposureState
        _exposureState.update { state }
        _exposureIndex.update { state.exposureCompensationIndex }
    }



    // Format the elapsed time
    private fun formatElapsedTime(elapsedTime: Long): String {
        val hours = (elapsedTime / 3600000).toInt()
        val minutes = (elapsedTime % 3600000 / 60000).toInt()
        val seconds = (elapsedTime % 60000 / 1000).toInt()

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(),"%02d:%02d", minutes, seconds)
        }
    }

    private fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
        _elapsedTime.value = "" // Reset the timer
        _recordTime.update { "" }
    }
    private fun startTimer() {
        startTime = System.currentTimeMillis()
        timerRunnable = object : Runnable {
            override fun run() {
                val timeDelta = System.currentTimeMillis() - startTime
                _elapsedTime.postValue(formatElapsedTime(timeDelta))
                _recordTime.update { formatElapsedTime(timeDelta) }
                handler.postDelayed(this, 1000)

            }
        }
        handler.post(timerRunnable)
    }


    override fun onCleared() {
        try {
        if (this::timerRunnable.isInitialized)
            handler.removeCallbacks(timerRunnable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onCleared()
    }

    fun tapToFocus(tapCoordinates: Offset) {
        val point = surfaceOrientedMeteringPointFactory?.createPoint(tapCoordinates.x,tapCoordinates.y)
        if (point != null) {
            val meteringAction = FocusMeteringAction.Builder(point).build()
            if (camera?.cameraInfo?.isFocusMeteringSupported(meteringAction) == true){
                cameraControl?.startFocusAndMetering(meteringAction)

            }

        }

    }

    fun unbindAll() {
        cameraProvider?.unbindAll()
        recording?.stop()
        _recordingState.update { RecordingState.Idle }
    }


}

enum class CameraEventType {
    NEW_IMAGE,
    NEW_VIDEO
}

object CameraConstants {
    const val NEW_MEDIA_EVENT = "org.witness.proofmode.NEW_MEDIA"
}



enum class CameraMode{
    VIDEO,
    IMAGE
}

enum class UltraHDRAvailabilityState(val description: String) {
    ON("On"),
    OFF("Off"),
    NOT_SUPPORTED("Not supported")
}

/**
 * Still-capture framing. [rational] drives the shared [ViewPort] crop (so 1:1 yields
 * a real square output), while [baseAspectRatio] picks the sensor output strategy —
 * 1:1 is captured from the full 4:3 sensor area and cropped square by the viewport.
 */
enum class PhotoAspectRatio(val label: String, val rational: Rational, val baseAspectRatio: Int) {
    RATIO_4_3("4:3", Rational(4, 3), AspectRatio.RATIO_4_3),
    RATIO_16_9("16:9", Rational(16, 9), AspectRatio.RATIO_16_9),
    RATIO_1_1("1:1", Rational(1, 1), AspectRatio.RATIO_4_3)
}

/** JPEG quality presets for stills. */
enum class PhotoQuality(val label: String, val jpegQuality: Int) {
    HIGH("High", 100),
    STANDARD("Standard", 85)
}


