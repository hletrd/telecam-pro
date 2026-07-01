package com.hletrd.findx9tele

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hletrd.findx9tele.ui.CameraScreen
import com.hletrd.findx9tele.ui.CameraViewModel
import com.hletrd.findx9tele.ui.theme.FindX9TeleTheme

class MainActivity : ComponentActivity() {

    private val vm: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            FindX9TeleTheme {
                val state by vm.state.collectAsState()
                var hasCamera by remember { mutableStateOf(hasCameraPermission()) }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    hasCamera = result[Manifest.permission.CAMERA] == true || hasCameraPermission()
                }

                LaunchedEffect(Unit) {
                    if (!hasCamera) launcher.launch(REQUIRED_PERMISSIONS)
                }

                if (hasCamera) {
                    CameraScreen(state = state, actions = vm, modifier = Modifier.fillMaxSize())
                } else {
                    PermissionGate { launcher.launch(REQUIRED_PERMISSIONS) }
                }
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private companion object {
        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("카메라 권한이 필요합니다")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequest) { Text("권한 허용") }
        }
    }
}
