package app.healthtrack.wear.store

import android.content.Context
import app.healthtrack.domain.Wrist

class WearPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("watch_prefs", Context.MODE_PRIVATE)

    var wrist: Wrist
        get() = if (prefs.getBoolean(KEY_RIGHT, false)) Wrist.RIGHT else Wrist.LEFT
        set(value) {
            prefs.edit().putBoolean(KEY_RIGHT, value == Wrist.RIGHT).apply()
        }

    companion object {
        private const val KEY_RIGHT = "ecg_wrist_right"
    }
}
