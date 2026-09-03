package app.galaxyvitals

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.galaxyvitals.ui.HealthTrackRoot
import app.galaxyvitals.ui.HealthTrackViewModel
import app.galaxyvitals.ui.theme.HealthTrackTheme

class MainActivity : ComponentActivity() {
    private val viewModel: HealthTrackViewModel by viewModels()
    private var pendingExternalImport by mutableStateOf<Uri?>(null)

    private val importDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importUri(uri)
        }
    }

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            HealthTrackTheme {
                Surface(Modifier.fillMaxSize()) {
                    HealthTrackRoot(
                        viewModel = viewModel,
                        onImport = {
                            // DocumentsUI often filters by the first type only.
                            // ECG files may be gzip, csv, or octet-stream depending on name.
                            importDoc.launch(arrayOf("*/*"))
                        },
                    )
                    pendingExternalImport?.let { uri ->
                        AlertDialog(
                            onDismissRequest = { pendingExternalImport = null },
                            title = { Text(stringResource(R.string.import_dialog_title)) },
                            text = { Text(stringResource(R.string.import_dialog_body)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        pendingExternalImport = null
                                        viewModel.importUri(uri)
                                    },
                                ) { Text(stringResource(R.string.import_dialog_confirm)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingExternalImport = null }) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        pendingExternalImport = uri
    }
}
