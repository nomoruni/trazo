package com.trazo.scanner

import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trazo.scanner.ui.CameraScreen
import com.trazo.scanner.ui.CropScreen
import com.trazo.scanner.ui.EditorScreen
import com.trazo.scanner.ui.HomeScreen

@Composable
fun TrazoApp(viewModel: TrazoViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel.message) {
        val text = viewModel.message
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    BackHandler(enabled = viewModel.screen != Screen.HOME) { viewModel.back() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (viewModel.screen) {
            Screen.HOME -> HomeScreen(
                onCamera = viewModel::goCamera,
                onImagePicked = viewModel::openImage,
            )

            Screen.CAMERA -> CameraScreen(
                onBack = viewModel::back,
                onCaptured = viewModel::openCaptured,
            )

            Screen.CROP -> {
                val source = viewModel.sourceBitmap
                if (source != null) {
                    CropScreen(
                        bitmap = source,
                        onCancel = viewModel::back,
                        onApply = { corners ->
                            viewModel.applyCrop(corners?.map { PointF(it.x, it.y) })
                        },
                    )
                } else {
                    LaunchedEffect(Unit) { viewModel.goHome() }
                }
            }

            Screen.EDITOR -> EditorScreen(
                viewModel = viewModel,
                onBack = viewModel::goHome,
            )
        }

        if (viewModel.busy) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp),
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.processing),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}
