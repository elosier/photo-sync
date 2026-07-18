package com.photosync.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()

                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { /* result reflected on next scan */ }

                val perms = remember { requiredPermissions() }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    permLauncher.launch(perms)
                }
                // Re-read persisted status each time the screen comes forward.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

                MainScreen(state, vm)
            }
        }
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
            list += Manifest.permission.POST_NOTIFICATIONS
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ partial access ("Select photos"). For a backup app
            // the user should pick "Allow all", but request this so a partial
            // grant at least keeps working and remains re-promptable.
            list += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
        return list.toTypedArray()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(state: UiState, vm: MainViewModel) {
    Scaffold(topBar = { TopAppBar(title = { Text("Photo Sync") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = vm::onServerUrl,
                label = { Text("Server URL") },
                placeholder = { Text("https://your-domain.duckdns.org:8443/photos") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = vm::onToken,
                label = { Text("Access token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.deviceFolder,
                onValueChange = vm::onDeviceFolder,
                label = { Text("Device folder (phonename-phonemodel)") },
                placeholder = { Text("myphone-galaxys25ultra") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = vm::detectDeviceFolder) {
                Text("Detect from this phone")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.wifiOnly, onCheckedChange = vm::onWifiOnly)
                Spacer(Modifier.height(0.dp).padding(4.dp))
                Text("Wi-Fi only (uncheck to allow mobile data)")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.cameraOnly, onCheckedChange = vm::onCameraOnly)
                Spacer(Modifier.padding(4.dp))
                Text("Camera roll only (excludes screenshots; uncheck for all media)")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::save, enabled = !state.busy) { Text("Save") }
                OutlinedButton(onClick = vm::testConnection, enabled = !state.busy) {
                    Text("Test connection")
                }
                Button(onClick = vm::syncNow, enabled = !state.busy) { Text("Sync now") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.syncEnabled, onCheckedChange = vm::setSyncEnabled)
                Spacer(Modifier.padding(4.dp))
                Text(if (state.syncEnabled) "Background sync ON" else "Background sync OFF")
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text("State: ${state.syncState}")
                    Text("Uploaded (recorded): ${state.uploadedCount}")
                    Text("Last run: ${state.lastRun}")
                    if (state.lastResult.isNotEmpty()) Text("Last result: ${state.lastResult}")
                    if (state.message.isNotEmpty()) {
                        Text(state.message, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Text(
                "Photos and videos are uploaded one-way to the server and never " +
                    "deleted there. Background sync runs every 15 minutes and " +
                    "retries automatically when the connection returns.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
