package org.witness.proofmode.camera

import android.Manifest
import android.app.Activity
import android.app.Activity.RESULT_OK
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import androidx.annotation.RawRes
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.witness.proofmode.camera.data.MediaType
import org.witness.proofmode.camera.databinding.FragmentCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


class CameraFragment : Fragment() {
    private var CURRENT_CAMERA = CameraSelector.LENS_FACING_BACK
    private lateinit var cameraProvider: ProcessCameraProvider
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recorder: Recorder? = null
    private var preview: Preview? = null
    private var resume = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private val proofModeDir = "DCIM/Proofmode"

    // Views
    private lateinit var captureImageButton: ImageButton
 //   private lateinit var recordVideoButton: ImageButton
 //   private lateinit var stopRecordingVideoButton: ImageButton
  //  private lateinit var pauseRecordingVideoButton: ImageButton
    private lateinit var flipCameraButton: ImageButton
    private lateinit var dot: ImageButton
    private lateinit var videoTimer: Chip
    private lateinit var previewView: PreviewView
    private lateinit var resumeRecordingVideoButton: ImageButton
    private lateinit var capturedPreview: ImageView
    private lateinit var flashSettingsButton: ImageView
    private lateinit var imageViewContainer:MaterialCardView
    private var cameraSelector: CameraSelector? = null
    private val viewModel: FlashModeViewModel by activityViewModels()

    private var _binding: FragmentCameraBinding? = null

    private val binding get() = _binding!!

    private var resultData : Intent = Intent()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        binding.flashSettingsViewModel = viewModel
        binding.lifecycleOwner = this
        initViews()
        observeSettingsChange()
        observeKeyEventChanges()
        return binding.root

    }

    private fun observeKeyEventChanges() {
        viewModel.keyEvent.observe(viewLifecycleOwner) {

            if (it == KeyEvent.KEYCODE_VOLUME_UP || it == KeyEvent.KEYCODE_VOLUME_DOWN) {
                captureImage()
            }

        }
    }

    private fun playSound(@RawRes soundFile: Int) {
        /**
        var mediaPlayer: MediaPlayer? = MediaPlayer.create(requireActivity(), soundFile)
        mediaPlayer?.start()
        mediaPlayer?.setOnCompletionListener {
            it.release()
            mediaPlayer = null
        }**/

    }


    private fun observeSettingsChange() {

        viewModel.flashMode.observe(viewLifecycleOwner) {
            rebindImageCapture(it)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as AppCompatActivity).supportActionBar?.hide()

        if (hasAllPermissions(requireContext(), permissions.toTypedArray())) {
            lifecycleScope.launch {
                startCamera()
            }
        } else {
            requestAllPermissions(
                requireParentFragment(),
                permissions = permissions.toTypedArray(),
                requestCode = CAMERA_PERMISSION_REQUEST_CODE
            )

        }

        setClickListeners()

    }

    private fun initViews() {
        binding.apply {
            captureImageButton = buttonCapturePicture
        //    recordVideoButton = buttonCaptureVideo
      //      pauseRecordingVideoButton = buttonPauseVideoRecording
          //  stopRecordingVideoButton = buttonStopRecording
            videoTimer = videoTimerView
            flipCameraButton = buttonFlipCamera
           // dot = buttonDot
            previewView = viewFinderView
           // resumeRecordingVideoButton = buttonResume
           // flashSettingsButton = buttonFlashSettings
            capturedPreview = capturedImagePreview
            imageViewContainer = imagePreviewContainer
        }
    }


    // GestureDetecctor to detect long press
    private val gestureDetector = GestureDetector(object : GestureDetector.SimpleOnGestureListener() {

        override fun onSingleTapUp(e: MotionEvent?): Boolean {
            captureImage()
            return super.onSingleTapUp(e)
        }

        override fun onLongPress(e: MotionEvent) {
            captureVideo()
        }


    })

    private fun setClickListeners() {


        capturedPreview.setOnClickListener {

            requireActivity()?.setResult(RESULT_OK,resultData)
            requireActivity()?.finish()
        }

        flipCameraButton.setOnClickListener {
            flipCamera()
        }

        captureImageButton.setOnTouchListener { view, motionEvent ->

            if (gestureDetector.onTouchEvent(motionEvent))
            else if (motionEvent.action == MotionEvent.ACTION_UP) {
                stopVideoRecording()
                true
            }

            false
        }
        /**
        recordVideoButton.setOnClickListener {
            captureVideo()
        }

        stopRecordingVideoButton.setOnClickListener {
            stopVideoRecording()
        }

        pauseRecordingVideoButton.setOnClickListener {
            pauseVideoRecording()
        }

        resumeRecordingVideoButton.setOnClickListener {
            resumeVideoRecording()
        }
        flashSettingsButton.setOnClickListener {
            val dialog = FlashSettingsDialogFragment()
            dialog.show(requireActivity().supportFragmentManager, "dialog")
        }**/

        /**
        capturedPreview.setOnClickListener {
            viewModel.mediaType.value?.let {
                if (it == MediaType.TypeVideo) {
                 //   val action = CameraFragmentDirections.actionCameraFragmentToViewCapturedFragment()
                   // findNavController().navigate(action)
                }
            }
        }**/
    }


    private suspend fun startCamera() {
        cameraProvider =
            ProcessCameraProvider.getInstance(requireContext().applicationContext).await()
        recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST))
            .build()
        videoCapture = VideoCapture.withOutput(recorder!!)
        bindPreview()
        initImageCapture()
        cameraSelector = CameraSelector.Builder().requireLensFacing(CURRENT_CAMERA).build()
        val recorder = Recorder.Builder()
            .setQualitySelector(Recorder.DEFAULT_QUALITY_SELECTOR)
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector!!,
                preview,
                imageCapture,
                videoCapture
            )

        } catch (ex: Exception) {
            Toast.makeText(requireContext(), getString(R.string.capture_error), Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindPreview() {
        preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

    }


    private fun initImageCapture() {
        imageCapture = ImageCapture.Builder()
            .setFlashMode(flashMode)
            .setJpegQuality(100).build()

    }

    private fun rebindImageCapture(flashMode: Int) {
        val imageCapture = imageCapture ?: return
        imageCapture.flashMode = flashMode
        try {
            cameraProvider.unbind(imageCapture)
            cameraProvider.bindToLifecycle(this, cameraSelector!!, imageCapture)

        } catch (ex: Exception) {
            Log.e(TAG, "rebindImageCapture: Error binding image capture usecase")
        }


    }

    private fun flipCamera() {
        CURRENT_CAMERA = if (CURRENT_CAMERA == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        lifecycleScope.launch {
            startCamera()
        }
    }


    private fun stopVideoRecording() {
        recording?.stop()
        //videoTimer.stop()
        videoTimer.visibility = View.INVISIBLE
        recording = null
    }

    private fun pauseVideoRecording() {
        //elapsedTime = videoTimer.base
        //videoTimer.stop()
        recording?.pause()

    }

    private fun resumeVideoRecording() {
        //videoTimer.base = elapsedTime
        //videoTimer.start()
        recording?.resume()

    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return
        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

    //    flipCameraButton.visibility = View.INVISIBLE
        videoTimer.visibility = View.VISIBLE
        val name = SimpleDateFormat(FILE_NAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            //put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, proofModeDir)
            }
        }
        val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val mediaStoreOutputOptions =
            MediaStoreOutputOptions.Builder(requireActivity().contentResolver, videoUri)
                .setContentValues(contentValues)
                .build()
        recording = videoCapture.output
            .prepareRecording(requireContext(), mediaStoreOutputOptions)
            .apply {
                if (ContextCompat.checkSelfPermission(
                        requireContext(),
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
            //            stopRecordingVideoButton.visibility = View.VISIBLE
          //              pauseRecordingVideoButton.visibility = View.VISIBLE
                    }

                    // As recording proceeds, get the duration and update the UI
                    is VideoRecordEvent.Status -> {
                        val stats = recordEvent.recordingStats
                        val nanoSecs = stats.recordedDurationNanos
                        videoTimer.text = formatNanoseconds(nanoSecs)

                    }

                    is VideoRecordEvent.Finalize -> {

                        viewModel.setVideoUri(recordEvent.outputResults.outputUri)

                        val bitmap =
                            createVideoThumb(requireContext(), recordEvent.outputResults.outputUri)
                        Log.d(TAG, "captureVideo: ${recordEvent.outputResults.outputUri}")
                        viewModel.setVideoImage(bitmap!!)
                        val viewVisible = View.VISIBLE
                        val viewInvisible = View.INVISIBLE
           //             stopRecordingVideoButton.visibility = viewInvisible
                        captureImageButton.visibility = viewVisible
             //           pauseRecordingVideoButton.visibility = viewInvisible
            //            flipCameraButton.visibility = viewVisible
                        captureImageButton.isEnabled = true
               //         recordVideoButton.isEnabled = true
                        videoTimer.visibility = viewInvisible
                        imageViewContainer.visibility = viewVisible
                        videoTimer.text = ""
                        if (!recordEvent.hasError()) {

                            sendLocalCameraEvent(recordEvent.outputResults.outputUri,"video/mp4")


                        } else {
                            val error = errorString(recordEvent.error)
                            recordEvent.cause
                            recording = null
                            val message = "Error : $error"
                        //    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT)
                          //      .show()
                        }
                    }

                    is VideoRecordEvent.Pause -> {
                        val viewVisible = View.VISIBLE
                        val viewInvisible = View.INVISIBLE
                        val stats = recordEvent.recordingStats
                        val nanoSecs = stats.recordedDurationNanos
                        videoTimer.text = "Paused:${formatNanoseconds(nanoSecs)}"
             //           stopRecordingVideoButton.visibility = viewVisible
           //             pauseRecordingVideoButton.visibility = viewInvisible
                        resumeRecordingVideoButton.visibility = viewVisible
                    }

                    is VideoRecordEvent.Resume -> {
                        val viewVisible = View.VISIBLE
                        val viewInvisible = View.INVISIBLE
                        resumeRecordingVideoButton.visibility = viewInvisible
                //        stopRecordingVideoButton.visibility = viewVisible
              //          pauseRecordingVideoButton.visibility = viewVisible
                        dot.visibility = viewInvisible
                        flipCameraButton.isEnabled = true
                    }


                }

            }


    }


    private fun captureImage() {
        playSound(R.raw.camera_click)
        val imageCapture: ImageCapture = imageCapture ?: return
        val name = SimpleDateFormat(FILE_NAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.ImageColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.ImageColumns.DISPLAY_NAME, name)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.ImageColumns.RELATIVE_PATH, proofModeDir)
            }
        }

        val outputDir = getExternalMediaDirs(requireContext().applicationContext)
        // On Android Q and above you can modify content of volume only if it is a primary volume
        val imagesCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        Log.d(TAG, "The collection is $imagesCollection")
        val outputFileOptions =
            ImageCapture.OutputFileOptions.Builder(
                requireActivity().contentResolver,
                imagesCollection,
                contentValues
            )
                .build()

        imageCapture.takePicture(outputFileOptions, ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val file = createFile(outputDir, ".jpg")

                    val savedUri =
                        outputFileResults.savedUri ?: Uri.fromFile(file)

                    Log.d(TAG, "onImageSaved: savedUri = $savedUri")

                    viewModel.setImage(savedUri)
                    MediaScannerConnection.scanFile(requireContext(), arrayOf(file.absolutePath),
                        arrayOf("image/jpeg")
                    ) { _, _ ->

                    }
                    imageViewContainer.visibility = View.VISIBLE

                    sendLocalCameraEvent(savedUri,"image/jpeg")

                }

                override fun onError(exception: ImageCaptureException) {
                    /**
                    Toast.makeText(
                        requireContext(),
                        "Error saving image ${exception.message}",
                        Toast.LENGTH_LONG
                    )
                        .show()**/
                }

            })


    }


    @Suppress("OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty()) {
                lifecycleScope.launch {
                    startCamera()
                }
            } else {
                Snackbar.make(
                    binding.root,
                    "Permissions are required to use the Camera.Enable in settings",
                    Snackbar.LENGTH_LONG
                )
                    .setAction(getString(android.R.string.ok)) {

                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", "org.witness.proofmode", null)
                        intent.data = uri
                        startActivity(intent)
                    }
                    .show()
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }


    companion object {
        const val TAG = "CameraFragment"
        private const val FILE_NAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val CAMERA_PERMISSION_REQUEST_CODE = 100
        private val permissions =
            arrayListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }


        private fun createFile(baseFolder: File, extension: String): File {
            return File(
                baseFolder,
                SimpleDateFormat(FILE_NAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis()) + extension
            )
        }

        @Suppress("DEPRECATION")
        private fun getExternalMediaDirs(context: Context): File {
            val appContext = context.applicationContext
            val mediaDir = context.externalMediaDirs.firstOrNull()?.let {
                File(it, "Proofmode").apply { mkdirs() }
            }
            return if (mediaDir != null && mediaDir.exists()) mediaDir else appContext.filesDir
        }
    }

    fun sendLocalCameraEvent(newMediaFile : Uri, mediaType : String) {

        resultData.data = newMediaFile
        resultData.type = mediaType

        var intent = Intent("org.witness.proofmode.NEW_MEDIA")
        intent.data = newMediaFile
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
