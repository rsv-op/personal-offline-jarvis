package com.example.voiceassistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.example.voiceassistant.orchestrator.AssistantViewModel
import com.example.voiceassistant.ui.AssistantApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * The only Activity in the app.
 *
 * It owns two families of Storage Access Framework entry points:
 *
 *  * **model importers** — a sherpa-onnx folder or archive, and the GGUF reply model;
 *  * **knowledge importers** — one document (`ACTION_OPEN_DOCUMENT`), including `.zip`
 *    archives that are expanded member by member, and a whole directory tree
 *    (`ACTION_OPEN_DOCUMENT_TREE`) that is walked recursively.
 *
 * Both families take a *persistable* read grant, so a folder the user picked once can be
 * re-indexed later without showing the picker again.
 *
 * SAF rather than `READ_EXTERNAL_STORAGE` is a deliberate part of the offline story: the app
 * is handed exactly the files the user picked and nothing else, which is why its manifest
 * needs no broad storage permission at all.
 *
 * The pickers are registered with `rememberLauncherForActivityResult` inside `setContent`
 * rather than as `Activity`-level fields. Both are correct; the compose form keeps every
 * launcher next to the button that triggers it, which makes it impossible to wire a picker
 * to the wrong callback.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()

    /**
     * The permission request is issued on the first composition.
     *
     * The result is deliberately ignored: the view model reports a denied microphone with a
     * specific message at the moment it matters, which is far more useful than a toast at
     * launch time.
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Reported by the view model when access is actually needed. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestStartupPermissions()

        setContent {
            val modelFolderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    persistReadPermission(uri)
                    viewModel.importOfflineModel(uri)
                }
            }

            val modelArchivePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    persistReadPermission(uri)
                    viewModel.importOfflineModelArchive(uri)
                }
            }

            val replyModelPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    persistReadPermission(uri)
                    viewModel.importReplyModel(uri)
                }
            }

            val knowledgeFilePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    persistReadPermission(uri)
                    viewModel.importKnowledgeDocument(uri)
                }
            }

            val knowledgeFolderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    persistReadPermission(uri)
                    viewModel.importKnowledgeFolder(uri)
                }
            }

            AssistantApp(
                viewModel = viewModel,
                onInstallModel = { modelFolderPicker.launch(null) },
                onInstallArchive = {
                    modelArchivePicker.launch(arrayOf("application/zip", "*/*"))
                },
                onInstallReplyModel = {
                    replyModelPicker.launch(arrayOf("application/octet-stream", "*/*"))
                },
                onAddKnowledgeFile = {
                    // "text/*" first so the picker opens on documents. The extra entries
                    // cover code, PDF and archive files; the real gate is the extension
                    // check inside the index, which runs before a byte is read.
                    knowledgeFilePicker.launch(
                        arrayOf(
                            "text/*",
                            "application/pdf",
                            "application/zip",
                            "application/json",
                            "application/octet-stream",
                            "*/*",
                        )
                    )
                },
                onAddKnowledgeFolder = { knowledgeFolderPicker.launch(null) },
            )
        }
    }

    private fun requestStartupPermissions() {
        permissionLauncher.launch(
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
    }

    /**
     * Keeps the grant across reboots, so a picked folder can be re-indexed without the
     * picker. Failure is ignored on purpose: a provider that does not offer persistable
     * grants still works for the current session.
     */
    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    override fun onStop() {
        // Leaving the app must not leave the microphone open: this app deliberately declares
        // no background service for hands-free listening, so the loop stops with the UI.
        if (!isChangingConfigurations) {
            viewModel.stopHandsFree()
        }
        super.onStop()
    }
}
