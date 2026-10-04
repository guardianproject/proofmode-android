package org.witness.proofmode.camera

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
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

        hideSystemBars()

        setContent {

            CameraScreen(viewModel, modifier = Modifier.fillMaxSize(), onClose = {
                finish()
            })

        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    }

    /**
     * Immersive camera: hide both the status bar and the navigation bar, which would
     * otherwise sit over the shutter row. A swipe from the edge brings them back
     * temporarily, as in the system camera, without the app losing the space.
     */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs, permission prompts and returning from another app all restore the
        // bars; hide them again whenever the camera has the screen back.
        if (hasFocus) hideSystemBars()
    }


}
