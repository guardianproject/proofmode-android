package org.witness.proofmode.camera

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
class CameraActivity : AppCompatActivity(){


    private val PREF_OPTION_AI = "blockAI"
    private val PREF_OPTION_CREDENTIALS = "addCR"

    var useCredentials = true
    var useAIFlag = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.camera_main)

        useCredentials = intent.getBooleanExtra(PREF_OPTION_CREDENTIALS, true)
        useAIFlag = intent.getBooleanExtra(PREF_OPTION_AI, true)

    }
}
