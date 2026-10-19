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
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        )
        setContent {
            AssistantApp(
                viewModel = viewModel,
                onInstallModel = { modelFolderPicker.launch(null) },
                onInstallArchive = { modelArchivePicker.launch(arrayOf("application/zip", "*/*")) },
                onInstallReplyModel = { replyModelPicker.launch(arrayOf("*/*")) }
            )
        }
    }
}
