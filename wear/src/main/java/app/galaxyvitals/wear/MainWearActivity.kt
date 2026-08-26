package app.galaxyvitals.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import app.galaxyvitals.wear.ui.WearRoot
import app.galaxyvitals.wear.ui.theme.HealthTrackWearTheme

class MainWearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        (application as WearApplication).container.attachEcgSensor(this)
        setContent {
            HealthTrackWearTheme {
                WearRoot()
            }
        }
    }
}
