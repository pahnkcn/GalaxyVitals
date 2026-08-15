package app.healthtrack

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
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import app.healthtrack.ui.HealthTrackRoot
import app.healthtrack.ui.HealthTrackViewModel
import app.healthtrack.ui.theme.HealthTrackTheme

class MainActivity : ComponentActivity() {
    private val viewModel: HealthTrackViewModel by viewModels()

    private val importDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.importUri(uri)
        }
    }

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleShare(intent)
        setContent {
            HealthTrackTheme {
                Surface(Modifier.fillMaxSize()) {
                    HealthTrackRoot(
                        viewModel = viewModel,
                        onImport = {
                            importDoc.launch(arrayOf("application/gzip", "application/octet-stream", "text/csv", "*/*"))
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
        uri?.let(viewModel::importUri)
    }
}
