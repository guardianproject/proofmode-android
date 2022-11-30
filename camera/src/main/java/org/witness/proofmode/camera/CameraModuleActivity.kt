package org.witness.proofmode.camera

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import org.witness.proofmode.camera.databinding.ActivityCameraMainBinding
class CameraModuleActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityCameraMainBinding
    private val viewModel:CameraViewModel by (this as androidx.core.app.ComponentActivity as ComponentActivity).viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= 21) {

            requestWindowFeature(Window.FEATURE_NO_TITLE);
            getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            );

            getWindow().setStatusBarColor(this.getResources().getColor(R.color.black));

        }

        super.onCreate(savedInstanceState)
        binding = ActivityCameraMainBinding.inflate(layoutInflater)
        displayEdgeToEdge()
        setContentView(binding.root)
        binding.viewModel = viewModel
        binding.lifecycleOwner = this
        setSupportActionBar(binding.toolbar)

        supportActionBar?.hide()
        val navController = findNavController(R.id.nav_host_fragment)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP){
            viewModel.setEventFromKeyCode(keyCode)
            true
        } else super.onKeyUp(keyCode, event)
    }


    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }

    private fun displayEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
           view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
               leftMargin = insets.left
               rightMargin = insets.right
               bottomMargin = insets.bottom
               topMargin = insets.top
           }

            WindowInsetsCompat.CONSUMED
        }
    }
}