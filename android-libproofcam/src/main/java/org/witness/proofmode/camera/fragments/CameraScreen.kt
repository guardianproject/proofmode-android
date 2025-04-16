package org.witness.proofmode.camera.fragments

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import org.witness.proofmode.camera.R

private val permissions = mutableListOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_EXTERNAL_STORAGE,
).apply {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        add(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remove(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(modifier: Modifier = Modifier, onClose: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: CameraViewModel = viewModel()
    val navController = rememberNavController()
    val permissionsState = rememberMultiplePermissionsState(permissions)
    if (permissionsState.allPermissionsGranted) {
        CameraNavigation(navController = navController, viewModel = viewModel, lifecycleOwner = lifecycleOwner,onClosed = onClose)
    } else {
        Column(modifier = modifier
            .fillMaxSize()
            .wrapContentSize()
            .widthIn(480.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {

            val textToShow = if(permissionsState.shouldShowRationale) {
                stringResource(R.string.permissions_rationale)
            } else {
                stringResource(R.string.message_no_permissions)

            }

            Text(textToShow, style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                Text(stringResource(R.string.grant_permissions))
            }

        }
    }
}
