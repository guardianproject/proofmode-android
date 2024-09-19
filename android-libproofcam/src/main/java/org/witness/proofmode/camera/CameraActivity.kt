package org.witness.proofmode.camera

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
class CameraActivity : AppCompatActivity(){


    private val PREF_OPTION_AI = "blockAI"
    private val PREF_OPTION_CREDENTIALS = "addCR"

    companion object {
        var useCredentials = true
        var useAIFlag = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the Android version is lower than Jellybean, use this call to hide
        // the status bar.
        if (Build.VERSION.SDK_INT < 16) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
        else
        {

            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            window.setDecorFitsSystemWindows(true)

        }

        setContentView(R.layout.camera_main)

        useCredentials = intent.getBooleanExtra(PREF_OPTION_CREDENTIALS, true)
        useAIFlag = intent.getBooleanExtra(PREF_OPTION_AI, true)

    }
}
