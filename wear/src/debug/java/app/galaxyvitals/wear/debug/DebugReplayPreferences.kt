package app.galaxyvitals.wear.debug

import android.content.Context

internal object DebugReplayPreferences {
    private const val PREFS_NAME = "debug_ecg_replay"
    private const val KEY_FIXTURE = "fixture"

    fun fixtureName(context: Context): String? {
        val stored = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FIXTURE, null)
        return DebugReplayFixtures.parseName(stored)
    }

    fun setFixtureName(context: Context, name: String) {
        val parsed = DebugReplayFixtures.parseName(name) ?: return
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FIXTURE, parsed)
            .commit()
    }

    fun clearFixture(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_FIXTURE)) return false
        return prefs.edit().remove(KEY_FIXTURE).commit()
    }
}
