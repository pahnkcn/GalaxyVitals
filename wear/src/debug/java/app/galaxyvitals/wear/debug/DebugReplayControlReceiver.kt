package app.galaxyvitals.wear.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only ADB hook that stores a synthetic ECG replay fixture name.
 *
 * The wear class lives in `app.galaxyvitals.wear.debug` while applicationId is
 * `app.galaxyvitals`, so the PLAN shorthand
 * `-n app.galaxyvitals/.debug.DebugReplayControlReceiver` does not resolve here.
 *
 * Working recipe:
 * ```
 * adb shell am broadcast -n app.galaxyvitals/app.galaxyvitals.wear.debug.DebugReplayControlReceiver -a app.galaxyvitals.DEBUG_ECG_REPLAY --es fixture clean_72
 * adb shell am force-stop app.galaxyvitals
 * adb shell am start -n app.galaxyvitals/app.galaxyvitals.wear.MainWearActivity
 * adb logcat -s EcgAcquisition EcgBpm EcgGraph
 * ```
 */
class DebugReplayControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != null && action != ACTION) return
        val raw = intent.getStringExtra(EXTRA_FIXTURE)
        val parsed = DebugReplayFixtures.parseName(raw)
        if (parsed == null) {
            Log.i(TAG, "replay fixture ignored name=$raw")
            return
        }
        DebugReplayPreferences.setFixtureName(context, parsed)
        Log.i(TAG, "replay fixture set name=$parsed")
    }

    companion object {
        const val ACTION = "app.galaxyvitals.DEBUG_ECG_REPLAY"
        const val EXTRA_FIXTURE = "fixture"
        private const val TAG = "EcgAcquisition"
    }
}
