package org.witness.proofmode.camera

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.LifecycleOwner
import org.witness.proofmode.camera.fragments.CameraScreen
import org.witness.proofmode.camera.fragments.CameraViewModel

class CameraActivity : ComponentActivity(){


    private val PREF_OPTION_AI = "blockAI"
    private val PREF_OPTION_CREDENTIALS = "addCR"


    companion object {
        var useCredentials = true
        var useAIFlag = true
    }

    val cameraViewModel:CameraViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the Android version is lower than Jellybean, use this call to hide
        // the status bar.

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN

        setContent {

            CameraScreen(modifier = Modifier.fillMaxSize())
        }

        //setContentView(R.layout.camera_main)
        useCredentials = intent.getBooleanExtra(PREF_OPTION_CREDENTIALS, true)
        useAIFlag = intent.getBooleanExtra(PREF_OPTION_AI, true)

    }
}
