package org.witness.proofmode.camera.fragments

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.GestureDetector
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.*
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import coil.load
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.witness.proofmode.c2pa.C2paUtils
import org.witness.proofmode.camera.CameraActivity
import org.witness.proofmode.camera.R
import org.witness.proofmode.camera.databinding.FragmentCameraBinding
import org.witness.proofmode.camera.enums.CameraTimer
import org.witness.proofmode.camera.utils.*
import org.witness.proofmode.service.MediaWatcher
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.util.Date
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.Delegates


class CameraFragment : BaseFragment<FragmentCameraBinding>(R.layout.fragment_camera) {
    private lateinit var proofModeCamera: Camera
    private lateinit var proofModeViewFinder: PreviewView
    private lateinit var tapDetector: GestureDetector
    private lateinit var pinchToZoomDetector: ScaleGestureDetector
    private lateinit var context: Context

    private val lensViewModel: CameraLensViewModel by activityViewModels()


    // An instance for display manager to get display change callbacks
    private val displayManager by lazy { requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager }

    // An instance of a helper function to work with Shared Preferences
    private val prefs by lazy { SharedPrefsManager.newInstance(requireContext()) }

    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    //private var imageAnalyzer: ImageAnalysis? = null

    // A lazy instance of the current fragment's view binding
    override val binding: FragmentCameraBinding by lazy {
        FragmentCameraBinding.inflate(
            layoutInflater
        )
    }

    private var displayId = -1

    // Selector showing which camera is selected (front or back)
    //private var lensFacing = CameraSelector.DEFAULT_BACK_CAMERA
    private var defaultLensFacing = CameraSelector.LENS_FACING_BACK
    //private var hdrCameraSelector: CameraSelector? = null

    // Selector showing which flash mode is selected (on, off or auto)
    private var flashMode by Delegates.observable(FLASH_MODE_OFF) { _, _, new ->
        binding.btnFlash.setImageResource(
            when (new) {
                FLASH_MODE_ON -> R.drawable.ic_flash_on
                FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
                else -> R.drawable.ic_flash_off
            }
        )
    }


    // Selector showing is grid enabled or not
    private var hasGrid = false

    // Selector showing is hdr enabled or not (will work, only if device's camera supports hdr on hardware level)
    private var hasHdr = false

    // Selector showing is there any selected timer and it's value (3s or 10s)
    private var selectedTimer = CameraTimer.OFF

    /**
     * A display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        @SuppressLint("UnsafeExperimentalUsageError", "UnsafeOptInUsageError")
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                preview?.targetRotation = view.display.rotation
                imageCapture?.targetRotation = view.display.rotation
          //      imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        flashMode = prefs.getInt(KEY_FLASH, FLASH_MODE_OFF)
        hasGrid = prefs.getBoolean(KEY_GRID, false)
        hasHdr = false;//prefs.getBoolean(KEY_HDR, false)
        initViews()

        displayManager.registerDisplayListener(displayListener, null)

        binding.run {
            proofModeViewFinder = viewFinder
            viewFinder.addOnAttachStateChangeListener(object :
                View.OnAttachStateChangeListener {
                override fun onViewDetachedFromWindow(v: View) =
                    displayManager.registerDisplayListener(displayListener, null)

                override fun onViewAttachedToWindow(v: View) =
                    displayManager.unregisterDisplayListener(displayListener)
            })

            btnTakePicture.setOnClickListener { takePicture() }
            btnGallery.setOnClickListener { openPreview() }
            btnSwitchCamera.setOnClickListener { toggleCamera() }
            btnExit.setOnClickListener { activity?.finish() }
            btnGrid.setOnClickListener { toggleGrid() }
            btnFlash.setOnClickListener { selectFlash() }
          //  btnHdr.setOnClickListener { toggleHdr() }
            /**
            btnTimerOff.setOnClickListener { closeTimerAndSelect(CameraTimer.OFF) }
            btnTimer3.setOnClickListener { closeTimerAndSelect(CameraTimer.S3) }
            btnTimer10.setOnClickListener { closeTimerAndSelect(CameraTimer.S10) }
            **/
            btnFlashOff.setOnClickListener { closeFlashAndSelect(FLASH_MODE_OFF) }
            btnFlashOn.setOnClickListener { closeFlashAndSelect(FLASH_MODE_ON) }
            btnFlashAuto.setOnClickListener { closeFlashAndSelect(FLASH_MODE_AUTO) }
            btnExposure.setOnClickListener { flExposure.visibility = View.VISIBLE }
            flExposure.setOnClickListener { flExposure.visibility = View.GONE }
            cameraVideoText?.setOnClickListener {
                navigateToVideoFragment()
            }

            // This swipe gesture adds a fun gesture to switch between video and photo
            /*
            val swipeGestures = SwipeGestureDetector().apply {
                setSwipeCallback(right = {
                    navigateToVideoFragment()
                })
            }
            val gestureDetectorCompat = GestureDetector(requireContext(), swipeGestures)
            */

            viewFinder.setOnTouchListener { _, motionEvent ->
                // Make sure to let the touch listener handle each event here
                //    gestureDetectorCompat.onTouchEvent(motionEvent)
                pinchToZoomDetector.onTouchEvent(motionEvent)
                tapDetector.onTouchEvent(motionEvent)
                return@setOnTouchListener true
            }
        }
    }

    private fun navigateToVideoFragment() {
        findNavController().navigate(R.id.action_camera_to_video)
    }

    /**
     * Create some initial states
     * */
    private fun initViews() {
        binding.btnGrid.setImageResource(if (hasGrid) R.drawable.ic_grid_on else R.drawable.ic_grid_off)
        binding.groupGridLines.visibility = if (hasGrid) View.VISIBLE else View.GONE
        adjustInsets()
    }

    /**
     * This methods adds all necessary margins to some views based on window insets and screen orientation
     * */
    private fun adjustInsets() {
        activity?.window?.fitSystemWindows()
        binding.btnTakePicture.onWindowInsets { view, windowInsets ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                view.bottomMargin =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            } else {
                view.endMargin = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).right
            }
        }

        binding.btnExit.onWindowInsets { view, windowInsets ->
            view.topMargin = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
        }

        binding.llTimerOptions.onWindowInsets { view, windowInsets ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                view.topPadding =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            } else {
                view.startPadding =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).left
            }
        }
        binding.llFlashOptions.onWindowInsets { view, windowInsets ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                view.topPadding =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            } else {
                view.startPadding =
                    windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).left
            }
        }
    }

    /**
     * Change the facing of camera
     *  toggleButton() function is an Extension function made to animate button rotation
     * */
    @SuppressLint("RestrictedApi")
    fun toggleCamera() = binding.btnSwitchCamera.toggleButton(
        flag = lensViewModel.lensFacing.value == CameraSelector.LENS_FACING_BACK,
        rotationAngle = 180f,
        firstIcon = R.drawable.ic_outline_camera_rear,
        secondIcon = R.drawable.ic_outline_camera_front,
    ) {
        /*lensFacing = if (it) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }*/

        lensViewModel.toggleLensFacing()

        startCamera()
    }

    /**
     * Navigate to PreviewFragment
     * */
    private fun openPreview() {
        if (getMedia().isEmpty()) return
        view?.let { Navigation.findNavController(it).navigate(R.id.action_camera_to_preview) }
    }

    /**
     * Show timer selection menu by circular reveal animation.
     *  circularReveal() function is an Extension function which is adding the circular reveal
     * */
  //  private fun selectTimer() = binding.llTimerOptions.circularReveal(binding.btnTimer)

    /**
     * This function is called from XML view via Data Binding to select a timer
     *  possible values are OFF, S3 or S10
     *  circularClose() function is an Extension function which is adding circular close
     * */
    /**
    private fun closeTimerAndSelect(timer: CameraTimer) =
        binding.llTimerOptions.circularClose(binding.btnTimer) {
            selectedTimer = timer
            binding.btnTimer.setImageResource(
                when (timer) {
                    CameraTimer.S3 -> R.drawable.ic_timer_3
                    CameraTimer.S10 -> R.drawable.ic_timer_10
                    CameraTimer.OFF -> R.drawable.ic_timer_off
                }
            )
        }**/

    /**
     * Show flashlight selection menu by circular reveal animation.
     *  circularReveal() function is an Extension function which is adding the circular reveal
     * */
    private fun selectFlash() = binding.llFlashOptions.circularReveal(binding.btnFlash)

    /**
     * This function is called from XML view via Data Binding to select a FlashMode
     *  possible values are ON, OFF or AUTO
     *  circularClose() function is an Extension function which is adding circular close
     * */
    private fun closeFlashAndSelect(@FlashMode flash: Int) =
        binding.llFlashOptions.circularClose(binding.btnFlash) {
            flashMode = flash
            binding.btnFlash.setImageResource(
                when (flash) {
                    FLASH_MODE_ON -> R.drawable.ic_flash_on
                    FLASH_MODE_OFF -> R.drawable.ic_flash_off
                    else -> R.drawable.ic_flash_auto
                }
            )
            imageCapture?.flashMode = flashMode
            prefs.putInt(KEY_FLASH, flashMode)
        }

    /**
     * Turns on or off the grid on the screen
     * */
    private fun toggleGrid() {
        binding.btnGrid.toggleButton(
            flag = hasGrid,
            rotationAngle = 180f,
            firstIcon = R.drawable.ic_grid_off,
            secondIcon = R.drawable.ic_grid_on,
        ) { flag ->
            hasGrid = flag
            prefs.putBoolean(KEY_GRID, flag)
            binding.groupGridLines.visibility = if (flag) View.VISIBLE else View.GONE
        }
    }

    /**
     * Turns on or off the HDR if available
     * */
    /**
    private fun toggleHdr() {
        binding.btnHdr.toggleButton(
            flag = hasHdr,
            rotationAngle = 360f,
            firstIcon = R.drawable.ic_hdr_off,
            secondIcon = R.drawable.ic_hdr_on,
        ) { flag ->
            hasHdr = flag
            prefs.putBoolean(KEY_HDR, flag)
            startCamera()
        }
    }**/

    override fun onPermissionGranted() {
        // Each time apps is coming to foreground the need permission check is being processed
        binding.viewFinder.let { vf ->
            vf.post {
                // Setting current display ID
                displayId = vf.display.displayId
                startCamera()
                lifecycleScope.launch(Dispatchers.IO) {
                    // Do on IO Dispatcher
                    setLastPictureThumbnail()
                }
            }
        }
    }

    private fun setLastPictureThumbnail() = binding.btnGallery.post {
        getMedia().firstOrNull() // check if there are any photos or videos in the app directory
            ?.let { setGalleryThumbnail(it.uri) } // preview the last one
            ?: binding.btnGallery.setImageResource(R.drawable.ic_no_picture) // or the default placeholder
    }

    /**
     * Unbinds all the lifecycles from CameraX, then creates new with new parameters
     * */
    private fun startCamera() {
        // This is the CameraX PreviewView where the camera will be rendered
        val viewFinder = binding.viewFinder

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
            } catch (e: InterruptedException) {
                Toast.makeText(requireContext(), "Error starting camera", Toast.LENGTH_SHORT).show()
                return@addListener
            } catch (e: ExecutionException) {
                Toast.makeText(requireContext(), "Error starting camera", Toast.LENGTH_SHORT).show()
                return@addListener
            }

            // The display information
            val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
            // The ratio for the output image and preview
            val aspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
            // The display rotation
            val rotation = viewFinder.display.rotation

            val localCameraProvider = cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

            // The Configuration of camera preview
            preview = Preview.Builder()
                .setTargetAspectRatio(aspectRatio) // set the camera aspect ratio
                .setTargetRotation(rotation) // set the camera rotation
                .build()

            // The Configuration of image capture
            imageCapture = Builder()
                .setCaptureMode(CAPTURE_MODE_MINIMIZE_LATENCY) // setting to have pictures with highest quality possible (may be slow)
                .setFlashMode(flashMode) // set capture flash
                .setTargetAspectRatio(aspectRatio) // set the capture aspect ratio
                .setTargetRotation(rotation) // set the capture rotation
                .build()


            checkForHdrExtensionAvailability()
/**
            // The Configuration of image analyzing
            imageAnalyzer = ImageAnalysis.Builder()
                .setTargetAspectRatio(aspectRatio) // set the analyzer aspect ratio
                .setTargetRotation(rotation) // set the analyzer rotation
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // in our analysis, we care about the latest image
                .build()**/

            // Unbind the use-cases before rebinding them
            localCameraProvider.unbindAll()
            // Bind all use cases to the camera with lifecycle
            bindToLifecycle(localCameraProvider, viewFinder)

            //listen for rotation
            orientationEventListener.enable()

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun checkForHdrExtensionAvailability() {
        // Create a Vendor Extension for HDR
        val extensionsManagerFuture = ExtensionsManager.getInstanceAsync(
            requireContext(), cameraProvider ?: return,
        )
        extensionsManagerFuture.addListener(
            {
                val extensionsManager = extensionsManagerFuture.get() ?: return@addListener
                val cameraProvider = cameraProvider ?: return@addListener
                val selector = CameraSelector.Builder().requireLensFacing(
                    lensViewModel.lensFacing.value ?: CameraSelector.LENS_FACING_BACK
                ).build()

                val isAvailable =
                    extensionsManager.isExtensionAvailable(selector, ExtensionMode.HDR)


                // check for any extension availability
                println(
                    "AUTO " + extensionsManager.isExtensionAvailable(
                        selector,
                        ExtensionMode.AUTO
                    )
                )
                println(
                    "HDR " + extensionsManager.isExtensionAvailable(
                        selector,
                        ExtensionMode.HDR
                    )
                )
                println(
                    "FACE RETOUCH " + extensionsManager.isExtensionAvailable(
                        selector,
                        ExtensionMode.FACE_RETOUCH
                    )
                )
                println(
                    "BOKEH " + extensionsManager.isExtensionAvailable(
                        selector,
                        ExtensionMode.BOKEH
                    )
                )
                println(
                    "NIGHT " + extensionsManager.isExtensionAvailable(
                        selector,
                        ExtensionMode.NIGHT
                    )
                )
                println(
                    "NONE " + extensionsManager.isExtensionAvailable(
                        selector,
                        ExtensionMode.NONE
                    )
                )

                binding.btnHdr.visibility = View.GONE

                /**
                // Check if the extension is available on the device
                if (!isAvailable) {
                    // If not, hide the HDR button
                    binding.btnHdr.visibility = View.GONE
                } else if (hasHdr) {
                    // If yes, turn on if the HDR is turned on by the user

                    binding.btnHdr.visibility = View.VISIBLE
                    hdrCameraSelector =
                        extensionsManager.getExtensionEnabledCameraSelector(
                            selector,
                            ExtensionMode.HDR
                        )

                }**/
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

   /**
    private fun setLuminosityAnalyzer(imageAnalysis: ImageAnalysis) {
        // Use a worker thread for image analysis to prevent glitches
        val analyzerThread = HandlerThread("LuminosityAnalysis").apply { start() }
        imageAnalysis.setAnalyzer(
            ThreadExecutor(Handler(analyzerThread.looper)),
            LuminosityAnalyzer()
        )
    }**/

    @SuppressLint("ClickableViewAccessibility")
    private fun bindToLifecycle(
        localCameraProvider: ProcessCameraProvider,
        viewFinder: PreviewView
    ) {
        try {
            val lens = lensViewModel.lensFacing.value
            val selector = if (lens != null) CameraSelector.Builder().requireLensFacing(lens)
                .build() else CameraSelector.DEFAULT_BACK_CAMERA
            proofModeCamera = localCameraProvider.bindToLifecycle(
                viewLifecycleOwner, // current lifecycle owner
                selector, // either front or back facing
                preview, // camera preview use case
                imageCapture // image capture use case
            ).apply {
                // Init camera exposure control
                cameraInfo.exposureState.run {
                    val lower = exposureCompensationRange.lower
                    val upper = exposureCompensationRange.upper

                    binding.sliderExposure.run {
                        valueFrom = lower.toFloat()
                        valueTo = upper.toFloat()
                        stepSize = 1f
                        value = exposureCompensationIndex.toFloat()

                        addOnChangeListener { _, value, _ ->
                            cameraControl.setExposureCompensationIndex(value.toInt())
                        }
                    }
                }
            }

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(viewFinder.surfaceProvider)
            tapDetector = viewFinder.createTapGestureDetector(proofModeCamera, lifecycleScope)
            pinchToZoomDetector = viewFinder.createPinchDetector(proofModeCamera)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind use cases", e)
        }
    }

    /**
     *  Detecting the most suitable aspect ratio for current dimensions
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    @Suppress("NON_EXHAUSTIVE_WHEN")
    private fun takePicture() = lifecycleScope.launch(Dispatchers.Main) {
        // Show a timer based on user selection
        when (selectedTimer) {
            CameraTimer.S3 -> for (i in 3 downTo 1) {
                binding.tvCountDown.text = i.toString()
                delay(1000)
            }

            CameraTimer.S10 -> for (i in 10 downTo 1) {
                binding.tvCountDown.text = i.toString()
                delay(1000)
            }

            else -> {
            }

        }
        binding.tvCountDown.text = ""
        captureImage()
    }


    private val mExec = Executors.newSingleThreadExecutor()

    private fun captureImage() {

        context = requireContext()

        proofModeViewFinder?.visibility = View.GONE
        proofModeViewFinder?.visibility = View.VISIBLE

        val localImageCapture =
            imageCapture ?: throw IllegalStateException("Camera initialization failed.")

        // Setup image capture metadata
        val metadata = Metadata().apply {
            // Mirror image when using the front camera
            isReversedHorizontal =
                lensViewModel.lensFacing.value == CameraSelector.LENS_FACING_FRONT
        }

        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // Options fot the output image file
        val outputOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, System.currentTimeMillis())
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, outputDirectory)
            }

            val contentResolver = requireContext().contentResolver

            // Create the output uri
            val contentUri =
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            OutputFileOptions.Builder(contentResolver, contentUri, contentValues)
        } else {

            File(outputDirectory).mkdirs()
            val fileMedia = File(outputDirectory, "${System.currentTimeMillis()}.jpg")
            OutputFileOptions.Builder(fileMedia)
        }.setMetadata(metadata).build()

        localImageCapture.takePicture(
            outputOptions, // the options needed for the final image
            mExec, // the executor, on which the task will run
            object : OnImageSavedCallback { // the callback, about the result of capture process
                override fun onImageSaved(outputFileResults: OutputFileResults) {

                    val dateSaved = Date()

                    // This function is called if capture is successfully completed
                    outputFileResults.savedUri
                        ?.let { uri ->
                            setGalleryThumbnail(uri)
                            sendLocalCameraEvent(uri)
                            Timber.tag(TAG).d("Photo saved in " + uri)
                        }
                        ?: setLastPictureThumbnail()

                    var proofUri = outputFileResults.savedUri
                    val isDirectCapture = true; //this is from our camera

                    if (CameraActivity.useCredentials) {

                        val allowMachineLearning = CameraActivity.useAIFlag; //by default, we flag to not allow
                        val fileOut = C2paUtils.addContentCredentials(
                            context,
                            proofUri,
                            isDirectCapture,
                            allowMachineLearning
                        )

                        var proofUriC2pa = Uri.fromFile(fileOut)

                        if (proofUriC2pa != null)
                            proofUri = proofUriC2pa

                    }

                    val mw: MediaWatcher = MediaWatcher.getInstance(context)

                    if (proofUri != null) {
                        val resultProofHash: String? =
                            mw.processUri(proofUri, isDirectCapture, dateSaved)

                        Timber.tag(CameraFragment.TAG)
                            .d("Photo proof generated: %s", resultProofHash)
                    }
                    else
                    {
                        Timber.tag(CameraFragment.TAG)
                            .d("Photo URI was null")
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    // This function is called if there is an errors during capture process
                    val msg = "Photo capture failed: ${exception.message}"
                    Log.e(TAG, msg)
                    exception.printStackTrace()
                }
            }
        )
    }

    private fun setGalleryThumbnail(savedUri: Uri?) = binding.btnGallery.load(savedUri) {
        placeholder(R.drawable.ic_no_picture)
        transformations(CircleCropTransformation())
        listener(object : ImageRequest.Listener {
            override fun onError(request: ImageRequest, result: ErrorResult) {
                super.onError(request, result)
                binding.btnGallery.load(savedUri) {
                    placeholder(R.drawable.ic_no_picture)
                    transformations(CircleCropTransformation())
                    // videoFrameMillis(0)
                }
            }

        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        displayManager.unregisterDisplayListener(displayListener)
        orientationEventListener.disable()
    }

    override fun onBackPressed() = when {
        /**
        binding.llTimerOptions.visibility == View.VISIBLE -> binding.llTimerOptions.circularClose(
            binding.btnTimer
        )**/

        binding.llFlashOptions.visibility == View.VISIBLE -> binding.llFlashOptions.circularClose(
            binding.btnFlash
        )

        else -> requireActivity().finish()
    }

    companion object {
        const val TAG = "libProofCam"

        const val KEY_FLASH = "sPrefFlashCamera"
        const val KEY_GRID = "sPrefGridCamera"
        const val KEY_HDR = "sPrefHDR"

        private const val RATIO_4_3_VALUE = 4.0 / 3.0 // aspect ratio 4x3
        private const val RATIO_16_9_VALUE = 16.0 / 9.0 // aspect ratio 16x9
    }


    fun sendLocalCameraEvent(newMediaFile: Uri) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {

            try {
                var f = newMediaFile.toFile()

                MediaStore.Images.Media.insertImage(
                    context?.contentResolver,
                    f.absolutePath, f.name, null
                )
                context?.sendBroadcast(
                    Intent(
                        Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(f)
                    )
                )
            } catch (e: FileNotFoundException) {
                e.printStackTrace()
            }
        }


    }

    private val orientationEventListener by lazy {
        object : OrientationEventListener(requireActivity()) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) {
                    return
                }

                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                imageCapture?.targetRotation = rotation
            }
        }
    }
}
