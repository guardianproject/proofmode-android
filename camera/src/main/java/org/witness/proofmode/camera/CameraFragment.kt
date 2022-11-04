package org.witness.proofmode.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.Image
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
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
    private var elapsedTime = 0L
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    // Views
    private lateinit var captureImageButton: ImageButton
    private lateinit var recordVideoButton: ImageButton
    private lateinit var stopRecordingVideoButton: ImageButton
    private lateinit var pauseRecordingVideoButton: ImageButton
    private lateinit var flipCameraButton: ImageButton
    private lateinit var dot: ImageButton
    private lateinit var videoTimer: Chronometer
    private lateinit var previewView: PreviewView
    private lateinit var resumeRecordingVideoButton: ImageButton
    private lateinit var flashSettingsButton: Button
    private var cameraSelector:CameraSelector? = null
    private val viewModel:FlashModeViewModel by activityViewModels()

    private var _binding: FragmentCameraBinding? = null

    private val binding get() = _binding!!



    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        binding.flashSettingsViewModel = viewModel
        binding.lifecycleOwner = this

        initViews()
        observeSettingsChange()
        return binding.root

    }

    private fun observeSettingsChange() {

        viewModel.flashMode.observe(viewLifecycleOwner){
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
                requireActivity(),
                permissions = permissions.toTypedArray(),
                requestCode = CAMERA_PERMISSION_REQUEST_CODE
            )

        }

        setClickListeners()
        setVideoTimeTickEvent()

    }




    private fun initViews() {
        binding.apply {
            captureImageButton = buttonCapturePicture
            recordVideoButton = buttonCaptureVideo
            pauseRecordingVideoButton = buttonPauseVideoRecording
            stopRecordingVideoButton = buttonStopRecording
            videoTimer = chronometer
            flipCameraButton = buttonFlipCamera
            dot = buttonDot
            previewView = viewFinderView
            resumeRecordingVideoButton = buttonResume
            flashSettingsButton = buttonFlashSettings
        }
    }

    private fun setVideoTimeTickEvent() {
        videoTimer.setOnChronometerTickListener {
            if (!resume) {
                val minutes = ((SystemClock.elapsedRealtime() - it.base) / 1000) / 60
                val seconds = ((SystemClock.elapsedRealtime() - it.base) / 1000) % 60
                elapsedTime = SystemClock.elapsedRealtime()
            } else {
                val minutes = ((SystemClock.elapsedRealtime() - elapsedTime) / 1000) / 60
                val seconds = ((SystemClock.elapsedRealtime() - elapsedTime) / 1000) % 60
                elapsedTime += 1000
            }
        }
    }

    private fun setClickListeners() {

        captureImageButton.setOnClickListener {
            captureImage()
        }

        flipCameraButton.setOnClickListener {
            flipCamera()
        }

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
            dialog.show(requireActivity().supportFragmentManager,"dialog")
        }


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
            Toast.makeText(requireContext(), "Error binding camera", Toast.LENGTH_LONG).show()

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

    private fun rebindImageCapture(flashMode:Int) {
        val imageCapture = imageCapture?:return
        imageCapture.flashMode = flashMode
        try {
            cameraProvider.unbind(imageCapture)
            val camera = cameraProvider.bindToLifecycle(this,cameraSelector!!,imageCapture)

        }catch (ex:Exception) {
            Log.e(TAG, "rebindImageCapture: Error binding image capture usecase", )
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
        val curRecording = recording

        recording?.stop()
        videoTimer.stop()
        videoTimer.visibility = View.INVISIBLE
        recording = null
    }

    private fun pauseVideoRecording() {
        elapsedTime = videoTimer.base
        videoTimer.stop()
        recording?.pause()

    }

    private fun resumeVideoRecording() {
        videoTimer.base = elapsedTime
        videoTimer.start()
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

        binding.buttonFlipCamera.isEnabled = false
        binding.buttonFlipCamera.visibility = View.INVISIBLE
        videoTimer.visibility = View.VISIBLE
        videoTimer.start()
        val name = SimpleDateFormat(FILE_NAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            //put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Proofmode")
            }
        }
        val videoUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
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
                        binding.buttonCaptureVideo.visibility = View.INVISIBLE
                        binding.buttonStopRecording.visibility = View.VISIBLE
                        binding.buttonPauseVideoRecording.visibility = View.VISIBLE
                        if (!resume) {
                            videoTimer.apply {
                                base = SystemClock.elapsedRealtime()
                                start()
                            }
                        } else {
                            videoTimer.start()
                        }

                    }

                    is VideoRecordEvent.Finalize -> {
                        val viewVisible = View.VISIBLE
                        val viewInvisible = View.INVISIBLE
                        binding.apply {
                            buttonStopRecording.visibility = viewInvisible
                            buttonCaptureVideo.visibility = viewVisible
                            buttonPauseVideoRecording.visibility = viewInvisible
                            buttonFlipCamera.isEnabled = true
                            buttonFlipCamera.visibility = viewVisible
                            buttonCapturePicture.isEnabled = true
                            buttonCaptureVideo.isEnabled = true
                            videoTimer.stop()
                            videoTimer.text = "00:00"
                            videoTimer.visibility = viewInvisible
                        }

                        if (!recordEvent.hasError()) {
                            val message =
                                "Video capture succeeded ${recordEvent.outputResults.outputUri}"
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT)
                                .show()


                        } else {
                            recording?.close()
                            recording = null
                            val message = "Video capture ended with error: ${recordEvent.error}"
                            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }

                    is VideoRecordEvent.Pause -> {
                        val viewVisible = View.VISIBLE
                        val viewInvisible = View.INVISIBLE
                        stopRecordingVideoButton.visibility = viewVisible
                        pauseRecordingVideoButton.visibility = viewInvisible
                        resumeRecordingVideoButton.visibility = viewVisible

                    }

                    is VideoRecordEvent.Resume -> {
                        val viewVisible = View.VISIBLE
                        val viewInvisible = View.INVISIBLE
                        resumeRecordingVideoButton.visibility = viewInvisible
                        stopRecordingVideoButton.visibility = viewVisible
                        pauseRecordingVideoButton.visibility = viewVisible
                        dot.visibility = viewInvisible
                        flipCameraButton.isEnabled = true
                    }


                }

            }


    }



    private fun captureImage() {
        val imageCapture: ImageCapture = imageCapture ?: return
        val name = SimpleDateFormat(FILE_NAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.ImageColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.ImageColumns.DISPLAY_NAME, name)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.ImageColumns.RELATIVE_PATH, "Pictures/Proofmode")
            }
        }
        val outputDir = getExternalMediaDirs(requireContext().applicationContext)
        // On Android Q and above you can modify content of volume only if it is a primary volume
        val imagesCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
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
                    val savedUri =
                        outputFileResults.savedUri ?: Uri.fromFile(createFile(outputDir, ".jpg"))
                    Toast.makeText(
                        requireContext(), "Image saved at location ${savedUri.path}",
                        Toast.LENGTH_LONG
                    )
                        .show()
                }


                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        requireContext(),
                        "Error saving image ${exception.message}",
                        Toast.LENGTH_LONG
                    )
                        .show()
                }

            })


    }


    @Deprecated("Deprecated in Java")
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

        /*private fun requestAllPermissions(activity: Activity) {
            ActivityCompat.requestPermissions(
                activity,
                permissions.toTypedArray(),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }

        private fun hasAllPermissions(context: Context): Boolean = permissions.all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }*/

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


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

interface OnFlashButtonClick {
    fun onClick(mode:Int)
}