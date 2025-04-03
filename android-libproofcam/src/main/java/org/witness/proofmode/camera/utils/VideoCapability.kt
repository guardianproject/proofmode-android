package org.witness.proofmode.camera.utils

import androidx.camera.core.CameraSelector
import androidx.camera.video.Quality

data class VideoCapability(val cameraSelector: CameraSelector, val supportedQualities:List<Quality>)
