package org.witness.proofmode.camera

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.witness.proofmode.ProofMode.PREF_OPTION_BLOCK_AI
import org.witness.proofmode.c2pa.DeviceIntegritySupport
import org.witness.proofmode.camera.fragments.CameraScreen
import org.witness.proofmode.camera.fragments.CameraViewModel


class CameraActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (DeviceIntegritySupport().isEnvironmentCompromised())
            System.exit(0)

        val window = window
       window.decorView.setBackgroundColor(android.graphics.Color.BLACK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController!!.hide(
                android.view.WindowInsets.Type.statusBars()
            )
        }
        else
        {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
        }

        setContent {

            CameraScreen(viewModel, modifier = Modifier.fillMaxSize(), onClose = {
                finish()
            })

        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    }


}
