package org.witness.proofmode.camera.fragments

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import kotlin.math.ln
import kotlin.math.pow

/** The adjustable parameters shown in the Pro bar, in display order. */
enum class ProSetting { ISO, SHUTTER, EV, FOCUS, WHITE_BALANCE }

/**
 * The user's manual overrides; `null` means "leave it to the camera".
 *
 * [iso] and [shutterNs] are always set or cleared together: Camera2 only honours
 * either one with auto-exposure off, at which point both must be supplied.
 */
data class ProSettings(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val focusDiopters: Float? = null,
    val whiteBalanceK: Int? = null,
) {
    val manualExposure: Boolean get() = iso != null && shutterNs != null
}

/** What the bound camera can do manually. A null / zero / false entry hides that control. */
data class ProCapabilities(
    val isoRange: IntRange? = null,
    val shutterRangeNs: LongRange? = null,
    /** Closest focus in diopters (1 / metres); 0 for a fixed-focus lens. */
    val minFocusDiopters: Float = 0f,
    val manualWhiteBalance: Boolean = false,
) {
    val manualExposure: Boolean get() = isoRange != null && shutterRangeNs != null
    val manualFocus: Boolean get() = minFocusDiopters > 0f
}

/** What the sensor is actually doing right now, used to label and seed the auto state. */
data class ProLiveReadout(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val focusDiopters: Float? = null,
    val whiteBalanceK: Int? = null,
)

/**
 * Manual ISO / shutter / focus / white balance on top of CameraX, via Camera2 interop.
 *
 * CameraX has no manual-sensor API of its own, so the overrides are pushed as
 * [CaptureRequestOptions] onto the bound camera's [Camera2CameraControl]; they apply
 * to the repeating preview request and to still captures alike, so the preview shows
 * what will be saved. The overrides live on the camera, not on a use case, which is
 * why [bind] has to be called after every (re)bind — including the video ones, with
 * `active = false`, so photo-only settings never leak into a recording.
 */
@OptIn(ExperimentalCamera2Interop::class)
class ProCameraController(context: Context) {

    private val cameraManager = context.getSystemService(CameraManager::class.java)


    private val _settings = MutableStateFlow(ProSettings())
    val settings: StateFlow<ProSettings> = _settings
    private val _capabilities = MutableStateFlow(ProCapabilities())
    val capabilities: StateFlow<ProCapabilities> = _capabilities
    private val _live = MutableStateFlow(ProLiveReadout())
    val live: StateFlow<ProLiveReadout> = _live

    private var control: Camera2CameraControl? = null
    private var cameraId: String? = null
    private var active = false

    // Written on the camera thread by the capture callback, read on the main thread.
    @Volatile private var lastGains: RggbChannelVector? = null
    @Volatile private var lastTransform: ColorSpaceTransform? = null
    private var frameCount = 0

    /** Auto white balance's last answer, frozen at the moment the user went manual. */
    private class WbAnchor(val gains: RggbChannelVector, val kelvin: Int, val transform: ColorSpaceTransform?)
    private var wbAnchor: WbAnchor? = null

    /**
     * Attach to the preview use case (Camera2Interop.Extender.setSessionCaptureCallback).
     * Feeds the live readout, and remembers the AWB gains manual white balance starts from.
     */
    val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            lastGains = gains
            lastTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
            // A few updates a second is plenty for a readout; every frame would
            // recompose the bar 30 times a second for nothing.
            if (frameCount++ % LIVE_READOUT_FRAME_INTERVAL != 0) return
            _live.value = ProLiveReadout(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                whiteBalanceK = gains?.let { estimateKelvin(it) },
            )
        }
    }

    /**
     * Point the controller at a freshly bound [camera]. Manual values survive a rebind
     * of the same camera (aspect ratio, quality, a trip through video) but are dropped
     * on a lens switch, since the other sensor has different ranges and calibration.
     */
    fun bind(camera: Camera, active: Boolean) {
        this.active = active
        try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            control = Camera2CameraControl.from(camera.cameraControl)
            if (info.cameraId != cameraId) {
                cameraId = info.cameraId
                wbAnchor = null
                _settings.value = ProSettings()
            }
            _capabilities.value = readCapabilities(info)
        } catch (e: IllegalArgumentException) {
            // Not a Camera2-backed camera: no manual controls.
            Timber.w(e, "Camera2 interop unavailable; pro controls disabled")
            control = null
            _capabilities.value = ProCapabilities()
        }
        apply()
    }

    /** Pro mode on/off. Off hands everything back to auto but remembers the values. */
    fun setActive(active: Boolean) {
        this.active = active
        apply()
    }

    fun setIso(iso: Int) {
        val caps = _capabilities.value
        val isoRange = caps.isoRange ?: return
        val shutterRange = caps.shutterRangeNs ?: return
        _settings.update {
            it.copy(
                iso = iso.coerceIn(isoRange),
                shutterNs = it.shutterNs ?: seedShutter(shutterRange)
            )
        }
        apply()
    }

    fun setShutter(shutterNs: Long) {
        val caps = _capabilities.value
        val isoRange = caps.isoRange ?: return
        val shutterRange = caps.shutterRangeNs ?: return
        _settings.update {
            it.copy(
                iso = it.iso ?: (_live.value.iso ?: DEFAULT_ISO).coerceIn(isoRange),
                shutterNs = shutterNs.coerceIn(shutterRange)
            )
        }
        apply()
    }

    fun setAutoExposure() {
        _settings.update { it.copy(iso = null, shutterNs = null) }
        apply()
    }

    /** [diopters] of 0 is infinity; null returns to autofocus. */
    fun setFocus(diopters: Float?) {
        val caps = _capabilities.value
        if (diopters != null && !caps.manualFocus) return
        _settings.update { it.copy(focusDiopters = diopters?.coerceIn(0f, caps.minFocusDiopters)) }
        apply()
    }

    /** null returns to auto white balance. */
    fun setWhiteBalance(kelvin: Int?) {
        if (kelvin == null) {
            wbAnchor = null
        } else {
            if (!_capabilities.value.manualWhiteBalance) return
            if (wbAnchor == null) {
                val gains = lastGains ?: FALLBACK_GAINS
                wbAnchor = WbAnchor(gains, estimateKelvin(gains), lastTransform)
            }
        }
        _settings.update { it.copy(whiteBalanceK = kelvin?.coerceIn(WB_MIN_KELVIN, WB_MAX_KELVIN)) }
        apply()
    }

    fun resetAll() {
        wbAnchor = null
        _settings.value = ProSettings()
        apply()
    }

    private fun seedShutter(range: LongRange): Long =
        (_live.value.shutterNs ?: DEFAULT_SHUTTER_NS).coerceIn(range)

    /**
     * Decide per control from what the HAL says it will *accept*, not from the
     * MANUAL_SENSOR / MANUAL_POST_PROCESSING capability flags. Those flags are
     * all-or-nothing bundles, and mid-range phones commonly leave them off while still
     * honouring parts of them: a Samsung A36's main camera lists neither, yet accepts
     * LENS_FOCUS_DISTANCE with AF off and COLOR_CORRECTION_GAINS with AWB off (it just
     * won't take SENSOR_SENSITIVITY / SENSOR_EXPOSURE_TIME). The available request keys
     * are the HAL's contract for which of these it honours.
     */
    private fun readCapabilities(info: Camera2CameraInfo): ProCapabilities {
        // CameraX's interop exposes characteristics by key only, and the request-key
        // list is a method rather than a public key, so ask CameraManager directly.
        val requestKeys: Set<CaptureRequest.Key<*>> = runCatching {
            cameraManager?.getCameraCharacteristics(info.cameraId)
                ?.availableCaptureRequestKeys?.toSet()
        }.getOrNull() ?: emptySet()
        val aeModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            ?: intArrayOf()
        val afModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: intArrayOf()
        val awbModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: intArrayOf()
        val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutter = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)

        // Camera2 only takes ISO or shutter with AE off, and then needs both.
        val manualExposure = CameraMetadata.CONTROL_AE_MODE_OFF in aeModes &&
                CaptureRequest.SENSOR_SENSITIVITY in requestKeys &&
                CaptureRequest.SENSOR_EXPOSURE_TIME in requestKeys
        val manualFocus = CameraMetadata.CONTROL_AF_MODE_OFF in afModes &&
                CaptureRequest.LENS_FOCUS_DISTANCE in requestKeys
        val manualWhiteBalance = CameraMetadata.CONTROL_AWB_MODE_OFF in awbModes &&
                CaptureRequest.COLOR_CORRECTION_MODE in requestKeys &&
                CaptureRequest.COLOR_CORRECTION_GAINS in requestKeys
        Timber.d(
            "Pro capabilities for camera %s: exposure=%b focus=%b (min %s dpt) whiteBalance=%b",
            info.cameraId, manualExposure, manualFocus, minFocus, manualWhiteBalance
        )
        return ProCapabilities(
            isoRange = iso?.takeIf { manualExposure && it.lower < it.upper }?.let { it.lower..it.upper },
            // Sensors advertise exposures of many seconds; past a second the preview
            // (which shares the exposure) is too slow to frame with.
            shutterRangeNs = shutter?.takeIf { manualExposure && it.lower < it.upper }
                ?.let { it.lower..it.upper.coerceAtMost(MAX_SHUTTER_NS).coerceAtLeast(it.lower) },
            minFocusDiopters = minFocus?.takeIf { manualFocus } ?: 0f,
            manualWhiteBalance = manualWhiteBalance,
        )
    }

    private fun apply() {
        val control = control ?: return
        val s = _settings.value
        val caps = _capabilities.value
        if (!active) {
            control.clearCaptureRequestOptions()
            return
        }
        val options = CaptureRequestOptions.Builder()
        if (s.iso != null && s.shutterNs != null && caps.manualExposure) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, s.iso)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, s.shutterNs)
        }
        if (s.focusDiopters != null && caps.manualFocus) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, s.focusDiopters)
        }
        val anchor = wbAnchor
        if (s.whiteBalanceK != null && anchor != null && caps.manualWhiteBalance) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            options.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            options.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                gainsForKelvin(anchor, s.whiteBalanceK)
            )
            anchor.transform?.let {
                options.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it)
            }
        }
        // Replaces whatever was set before, so a cleared setting really goes back to auto.
        control.setCaptureRequestOptions(options.build())
    }

    companion object {
        const val WB_MIN_KELVIN = 2500
        const val WB_MAX_KELVIN = 9000
        private const val MAX_SHUTTER_NS = 1_000_000_000L
        private const val DEFAULT_SHUTTER_NS = 16_666_667L // 1/60 s
        private const val DEFAULT_ISO = 100
        private const val LIVE_READOUT_FRAME_INTERVAL = 6

        // Used only if the HAL never reports its AWB gains: a typical daylight set.
        private val FALLBACK_GAINS = RggbChannelVector(2.0f, 1.0f, 1.0f, 1.6f)

        /**
         * Rough red/blue gain ratio of a phone sensor relative to the sRGB-ish
         * illuminant model below. Only affects the Kelvin *label* of where auto
         * white balance was; the picture itself is anchored to the device's own gains.
         */
        private const val SENSOR_RB_BIAS = 1.44f

        /**
         * Camera2 has no "colour temperature" control, only per-channel gains. Rather
         * than guess absolute gains for an uncalibrated sensor, scale the gains AWB had
         * settled on by how much the illuminant colour changes between the Kelvin AWB
         * was (estimated to be) at and the Kelvin asked for. Going manual therefore
         * starts exactly where auto left off, and the slider warms/cools from there.
         */
        private fun gainsForKelvin(anchor: WbAnchor, kelvin: Int): RggbChannelVector {
            val (r0, g0, b0) = illuminant(anchor.kelvin)
            val (r, g, b) = illuminant(kelvin)
            // A lower Kelvin setting assumes warmer light, so it must pull red down
            // and push blue up — hence gain ∝ green / channel.
            val redScale = (g / r) / (g0 / r0)
            val blueScale = (g / b) / (g0 / b0)
            return RggbChannelVector(
                (anchor.gains.red * redScale).coerceAtLeast(MIN_GAIN),
                anchor.gains.greenEven,
                anchor.gains.greenOdd,
                (anchor.gains.blue * blueScale).coerceAtLeast(MIN_GAIN)
            )
        }

        private const val MIN_GAIN = 0.1f

        /** Inverse of the above: which Kelvin best explains these AWB gains. */
        private fun estimateKelvin(gains: RggbChannelVector): Int {
            if (gains.blue <= 0f) return WB_MAX_KELVIN
            val target = gains.red / gains.blue / SENSOR_RB_BIAS
            // blue/red of the illuminant rises monotonically with temperature.
            var lo = WB_MIN_KELVIN
            var hi = WB_MAX_KELVIN
            while (hi - lo > 50) {
                val mid = (lo + hi) / 2
                val (r, _, b) = illuminant(mid)
                if (b / r < target) lo = mid else hi = mid
            }
            return (lo + hi) / 2 / 50 * 50
        }

        /** Tanner Helland's black-body approximation, as linear-ish RGB in 0..1. */
        private fun illuminant(kelvin: Int): Triple<Float, Float, Float> {
            val t = kelvin / 100.0
            val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
            val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661
            else 288.1221695283 * (t - 60).pow(-0.0755148492)
            val b = if (t >= 66) 255.0 else 138.5177312231 * ln(t - 10) - 305.0447927307
            fun norm(v: Double) = (v.coerceIn(1.0, 255.0) / 255.0).toFloat()
            return Triple(norm(r), norm(g), norm(b))
        }
    }
}
