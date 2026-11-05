package com.pdfeditable

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.pdfeditable.ui.EditorScreen
import com.pdfeditable.ui.theme.PdfEditableTheme

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only react to the launching intent once; the view model survives rotation.
        if (savedInstanceState == null) handleViewIntent(intent)
        setContent {
            PdfEditableTheme {
                EditorApp(viewModel = viewModel, onExit = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        viewModel.open(uri)
    }
}

@Composable
private fun EditorApp(viewModel: EditorViewModel, onExit: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Keep access across restarts so "Save" can write back to the same file later.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.open(uri)
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) viewModel.save(uri)
    }

    EditorScreen(
        state = state,
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        onOpenClick = { openLauncher.launch(arrayOf("application/pdf")) },
        onSaveAsClick = { saveAsLauncher.launch(viewModel.suggestedSaveName()) },
        onExit = onExit,
    )
}
