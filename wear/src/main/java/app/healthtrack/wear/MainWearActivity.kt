package app.healthtrack.wear

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import app.healthtrack.wear.ui.WearRoot
import app.healthtrack.wear.ui.theme.HealthTrackWearTheme

class MainWearActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val needed = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= 33) {
                add("android.permission.health.READ_HEART_RATE")
            }
        }.toTypedArray()
        permissionLauncher.launch(needed)
        setContent {
            HealthTrackWearTheme {
                WearRoot()
            }
        }
    }
}
