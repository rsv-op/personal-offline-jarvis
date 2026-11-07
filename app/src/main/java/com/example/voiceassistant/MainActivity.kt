package com.example.voiceassistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.voiceassistant.orchestrator.AssistantViewModel
import com.example.voiceassistant.ui.AssistantApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: AssistantViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* The view model reports microphone permission errors if access is denied. */ }

    /** Extracted sherpa-onnx model folder. */
    private val modelFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            persistReadPermission(uri)
            viewModel.importOfflineModel(uri)
        }
    }

    /** sherpa-onnx model `.zip` archive. */
    private val modelArchivePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistReadPermission(uri)
            viewModel.importOfflineModelArchive(uri)
        }
    }

    /** The GGUF reply model. Restricted to `.gguf` so the picker shows models only. */
    private val replyModelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistReadPermission(uri)
            viewModel.importReplyModel(uri)
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                // POST_NOTIFICATIONS is a runtime permission from Android 13.
                // The foreground service can run without it, but then the
                // user would not see the persistent notification.
                Manifest.permission.POST_NOTIFICATIONS,
                // The contacts tool is read-only and refuses to run without
                // this, so the user is asked once at startup.
                Manifest.permission.READ_CONTACTS
            )
        )
        setContent {
            AssistantApp(
                viewModel = viewModel,
                onInstallModel = { modelFolderPicker.launch(null) },
                onInstallArchive = { modelArchivePicker.launch(arrayOf("application/zip", "*/*")) },
                onInstallReplyModel = { replyModelPicker.launch(arrayOf("application/octet-stream", "*/*")) }
            )
        }
    }
}
