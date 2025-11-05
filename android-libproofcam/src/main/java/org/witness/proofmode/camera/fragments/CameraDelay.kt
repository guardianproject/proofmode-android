package org.witness.proofmode.camera.fragments

import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import org.witness.proofmode.camera.R

enum class CameraDelay(val value:Int){
    Zero(0),
    Three(3),
    Ten(10)
}

@Composable
fun CameraDelay.toVectorIcon(): ImageVector {
    return when(this){
        CameraDelay.Zero -> {
            ImageVector.vectorResource(R.drawable.timer_off)
        }
        CameraDelay.Three -> {
            ImageVector.vectorResource(R.drawable.timer_3_select)
        }
        CameraDelay.Ten -> {
            ImageVector.vectorResource(R.drawable.timer_10_select)

        }
    }
}

fun CameraDelay.toSecondsString():String {
    return when(this){
        CameraDelay.Zero -> "Off"
        else -> "${this.value} sec"
    }
}