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
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.witness.proofmode.camera.CameraActivity
import org.witness.proofmode.camera.adapter.Media
import org.witness.proofmode.camera.fragments.CameraConstants.NEW_MEDIA_EVENT
import org.witness.proofmode.camera.utils.getMediaFlow
import org.witness.proofmode.camera.utils.getSupportedQualities
import org.witness.proofmode.camera.utils.isUltraHdrSupported
import org.witness.proofmode.service.MediaWatcher.Companion.getInstance
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class CameraViewModel(private val activity: CameraActivity, private val app: Application) : AndroidViewModel(app) {
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



    var lensFacing: MutableLiveData<Int> = MutableLiveData(CameraSelector.LENS_FACING_BACK)
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
        cameraControl?.enableTorch(_torchOn.value)
    }


    fun toggleFlashMode( @FlashMode newMode: Int, lifecycleOwner: LifecycleOwner) {
        _flashMode.value = newMode
        cameraProvider?.unbind(imageCapture)
        imageCapture = null
        imageCaptureBuilder.setFlashMode(flashMode.value)
        imageCapture = imageCaptureBuilder.build()
        camera = cameraProvider!!.bindToLifecycle(lifecycleOwner,CameraSelector.Builder().requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build(),
            previewUseCase,imageCapture).apply {
                cameraInfo.exposureState.exposureCompensationRange
        }
        zoomState = camera!!.cameraInfo.zoomState
        cameraControl = camera?.cameraControl

    }




    fun pinchZoom(zoom: Float) {
        val zoomState = camera?.cameraInfo?.zoomState?.value
        if (zoomState != null) {
            val maxZoomRatio = zoomState.maxZoomRatio ?: 1f

            val minZoomRatio = zoomState.minZoomRatio ?: 1f
            val currentZoomRatio = zoomState.zoomRatio ?: 1f
            val newZoomRatio = (currentZoomRatio * zoom).coerceIn(minZoomRatio, maxZoomRatio)
            cameraControl?.setZoomRatio(newZoomRatio)
        }


    }

    suspend fun changeQuality(quality:Quality,lifecycleOwner: LifecycleOwner) {
        _selectedQuality.update{ quality}
        cameraProvider?.unbind(videoCapture)
        _previewAlpha.update { 0.5f }
        delay(800)
        _previewAlpha.update { 1f }

        videoCapture = null
        recorder = null
        recorder = Recorder.Builder()

            .setQualitySelector(QualitySelector.from(_selectedQuality.value!!, FallbackStrategy.lowerQualityThan(
                _selectedQuality.value!!)))
            .build()
        videoCapture = VideoCapture.withOutput(recorder!!)
        camera = cameraProvider!!.bindToLifecycle(lifecycleOwner = lifecycleOwner,CameraSelector.Builder().requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build(),
            previewUseCase,videoCapture)
        camera?.cameraInfo?.supportedFrameRateRanges.let { ranges->
            _supportedFrameRates.update { ranges as Set<Range<Int>> }

        }
        zoomState = camera!!.cameraInfo.zoomState
        cameraControl = camera?.cameraControl
        cameraControl?.enableTorch(_torchOn.value)

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
    try {
        camera = cameraProvider!!.bindToLifecycle(lifecycleOwner = lifecycleOwner,CameraSelector.Builder().requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build(),
            previewUseCase,videoCapture)
        zoomState = camera!!.cameraInfo.zoomState
        cameraControl = camera?.cameraControl
        cameraControl?.enableTorch(_torchOn.value)
    } catch (ex:Exception){
        Timber.e("Binding failed")
    }


}
    suspend fun bindUseCasesForImage(lifecycleOwner: LifecycleOwner) {
        cameraProvider = cameraProvider?: ProcessCameraProvider.awaitInstance(app.applicationContext)
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build()
        val isUltraHdrSupported = isUltraHdrSupported(cameraSelector,cameraProvider!!)
        if (isUltraHdrSupported){
            if (ultraHdr.value == UltraHDRAvailabilityState.ON) {
                imageCaptureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
            }
        } else {
            _ultraHdr.update { UltraHDRAvailabilityState.NOT_SUPPORTED }
        }

        imageCapture = imageCaptureBuilder
            .build()
        camera = cameraProvider!!.bindToLifecycle(lifecycleOwner = lifecycleOwner,cameraSelector,
            previewUseCase,imageCapture).apply {
                _exposureState.value = cameraInfo.exposureState
        }
        zoomState = camera!!.cameraInfo.zoomState
        cameraControl = camera?.cameraControl
    }

    suspend fun toggleUltraHdr(lifecycleOwner: LifecycleOwner) {
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing.value?:CameraSelector.LENS_FACING_BACK).build()
        val isUltraHdrSupported = isUltraHdrSupported(cameraSelector,cameraProvider!!)
        if (!isUltraHdrSupported) {
            _ultraHdr.update { UltraHDRAvailabilityState.NOT_SUPPORTED }
        } else {
            val currentUltraHdrState = ultraHdr.value
            if (currentUltraHdrState == UltraHDRAvailabilityState.ON) {
                _ultraHdr.update { UltraHDRAvailabilityState.OFF }
            } else {
                _ultraHdr.update { UltraHDRAvailabilityState.ON }
            }
            _previewAlpha.update { 0.5f }
            delay(800)
            _previewAlpha.update { 1f }
            cameraProvider?.unbind(imageCapture)
            imageCapture = null
            imageCapture = imageCaptureBuilder
                .apply {
                    if (ultraHdr.value == UltraHDRAvailabilityState.ON) {
                        setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                    }
                }
                .build()
            camera = cameraProvider!!.bindToLifecycle(lifecycleOwner = lifecycleOwner,cameraSelector,
                previewUseCase,imageCapture)
                .apply {
                    _exposureState.value = cameraInfo.exposureState
                }
            zoomState = camera!!.cameraInfo.zoomState
            cameraControl = camera?.cameraControl
        }

    }



    fun captureImage() {
        val metadata = Metadata().apply {
            // Mirror image when using the front camera
            isReversedHorizontal =
                lensFacing.value == CameraSelector.LENS_FACING_FRONT
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

        imageCapture?.setTargetRotation(activity.getScreenOrientation())

        imageCapture?.takePicture(
            outputOptions,
            mExec,
            object : OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri

                    // Create a temporary image to immediately show in thumbnail.
                    savedUri?.let {
                        _thumbPreviewUri.value = Media(it, false, System.currentTimeMillis())

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
    fun startRecording() {
        videoCapture?.targetRotation = activity.getScreenOrientation()

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
        recording = recorder?.prepareRecording(app.applicationContext,mediaStoreOutput)
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
       // val mw = getInstance(activity)

        try {

            app.sendBroadcast(
                Intent(
                    Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, newMediaFile
                )
            )
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        }

        if (cameraEventType == CameraEventType.NEW_VIDEO) {
           // val intent = Intent(NEW_MEDIA_EVENT).apply { data = newMediaFile }
          //  LocalBroadcastManager.getInstance(app).sendBroadcast(intent)

           // mw?.processUri(newMediaFile, true, null, "video/mp4")





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

            //mw?.processUri(newMediaFile, true, null, "image/jpeg")

        }


    }


    suspend fun switchLensFacing(lifecycleOwner: LifecycleOwner,cameraMode: CameraMode) {
        lensFacing.value = if (lensFacing.value == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        cameraProvider?.unbindAll()
        if (cameraMode == CameraMode.VIDEO) {
            bindUseCasesForVideo(lifecycleOwner)
        } else if (cameraMode == CameraMode.IMAGE) {
            bindUseCasesForImage(lifecycleOwner)
        }


    }

    fun updateExposureCompensation(compensationIndex:Int){
        cameraControl?.setExposureCompensationIndex(compensationIndex)
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


