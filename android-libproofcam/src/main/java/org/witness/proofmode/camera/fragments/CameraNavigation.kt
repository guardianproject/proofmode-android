package org.witness.proofmode.camera.fragments

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState

@Composable
fun CameraNavigation(navController:NavHostController,
                     viewModel: CameraViewModel,
                     lifecycleOwner: LifecycleOwner,
                     onClosed:()->Unit
) {
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = currentBackStackEntry?.destination?.route
    LaunchedEffect(currentDestination) {
        when(currentBackStackEntry?.destination?.route){
            CameraDestinations.PHOTO -> {
                viewModel.unbindAll()
                viewModel.bindUseCasesForImage(lifecycleOwner)
            }
            CameraDestinations.VIDEO -> {
                viewModel.unbindAll()
                viewModel.bindUseCasesForVideo(lifecycleOwner)
            }
            CameraDestinations.PREVIEW-> {

            }
        }
    }

    NavHost(navController = navController, startDestination = CameraDestinations.PHOTO) {
        composable(CameraDestinations.PHOTO,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }
        ) {

            PhotoCamera(cameraViewModel = viewModel, lifecycleOwner = lifecycleOwner, onNavigateToPreview = {
                navController.navigate(CameraDestinations.PREVIEW)
            }, onNavigateToVideo = {
                navController.navigate(CameraDestinations.VIDEO){
                    popUpTo(CameraDestinations.PHOTO){
                        inclusive = true
                    }
                }
            }, onClose = onClosed)

        }
        composable(CameraDestinations.VIDEO,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }) {
            VideoCamera(cameraViewModel = viewModel,
                lifecycleOwner = lifecycleOwner,
                onNavigateToPhotoCamera = {
                navController.navigate(CameraDestinations.PHOTO) {
                    popUpTo(CameraDestinations.VIDEO) {
                        inclusive = true
                    }
                }
            }, onNavigateBack = {
                navController.popBackStack()

            }, onNavigateToPreview = {
                navController.navigate(CameraDestinations.PREVIEW)
            }, onClose = onClosed)

        }
        composable(CameraDestinations.PREVIEW,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() }) {
            MediaPreview(viewModel = viewModel, modifier = Modifier.fillMaxSize(), onNavigateBack = {
                navController.popBackStack()
            })

        }

    }


}

object CameraDestinations {
    const val PHOTO = "photo"
   const val VIDEO = "video"
   const val PREVIEW = "preview"
}