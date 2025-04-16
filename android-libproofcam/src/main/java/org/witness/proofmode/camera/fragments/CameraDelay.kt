package org.witness.proofmode.camera.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer10Select
import androidx.compose.material.icons.outlined.Timer3Select
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

enum class CameraDelay(val value:Int){
    Zero(0),
    Three(3),
    Ten(10)
}

@Composable
fun CameraDelay.toVectorIcon(): ImageVector {
    return when(this){
        CameraDelay.Zero -> {
            Icons.Outlined.TimerOff
        }
        CameraDelay.Three -> {
            Icons.Outlined.Timer3Select
        }
        CameraDelay.Ten -> {
            Icons.Outlined.Timer10Select

        }
    }
}

fun CameraDelay.toSecondsString():String {
    return when(this){
        CameraDelay.Zero -> "Off"
        else -> "${this.value} sec"
    }
}