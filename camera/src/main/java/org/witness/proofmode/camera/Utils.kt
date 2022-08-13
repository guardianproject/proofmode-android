package org.witness.proofmode.camera

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

fun requestAllPermissions(activity: Activity,permissions:Array<String>,requestCode:Int) {
    ActivityCompat.requestPermissions(
        activity,
        permissions,
        requestCode
    )
}

fun hasAllPermissions(context: Context,permissions:Array<String>): Boolean = permissions.all {
    ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

const val PREFS_FILE_NAME = "prefs"
const val FLASH_KEY = "flash"