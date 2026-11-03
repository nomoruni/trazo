package com.trazo.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trazo.scanner.ui.theme.TrazoTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            TrazoTheme {
                val viewModel: TrazoViewModel = viewModel()
                TrazoApp(viewModel)
            }
        }

        requestLegacyStorageIfNeeded()
    }

    /**
     * En Android 9 o inferior guardar el PNG/PDF en la carpeta pública exige
     * WRITE_EXTERNAL_STORAGE. En Android 10+ (MediaStore) no hace falta ningún permiso.
     */
    private fun requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 1001)
        }
    }
}
