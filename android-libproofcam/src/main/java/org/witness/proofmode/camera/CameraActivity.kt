package org.witness.proofmode.camera

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import org.witness.proofmode.camera.c2pa.C2paUtils
import org.witness.proofmode.camera.c2pa.C2paUtils.Companion.IDENTITY_NAME_KEY
import org.witness.proofmode.camera.c2pa.C2paUtils.Companion.IDENTITY_URI_KEY

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

        if (useCredentials) {
            //set identity based on passed strings
            C2paUtils.setC2PAIdentity(
                intent.getStringExtra(IDENTITY_NAME_KEY),
                intent.getStringExtra(IDENTITY_URI_KEY)
            )
        }
    }
}
