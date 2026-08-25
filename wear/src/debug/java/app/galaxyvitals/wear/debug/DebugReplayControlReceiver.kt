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
 * adb logcat -s EcgAcquisition EcgMeasurement EcgBpm
 * ```
 *
 * Switch back to Samsung hardware (requires app restart; EcgSensor is created once):
 * ```
 * adb shell am broadcast -n app.galaxyvitals/app.galaxyvitals.wear.debug.DebugReplayControlReceiver -a app.galaxyvitals.DEBUG_ECG_REPLAY --es fixture hardware
 * adb shell am force-stop app.galaxyvitals
 * adb shell am start -n app.galaxyvitals/app.galaxyvitals.wear.MainWearActivity
 * ```
 */
class DebugReplayControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != null && action != ACTION) return
        val raw = intent.getStringExtra(EXTRA_FIXTURE)
        when (val command = parseDebugReplayCommand(raw)) {
            DebugReplayCommand.UseHardware -> {
                val cleared = DebugReplayPreferences.clearFixture(context)
                Log.i(TAG, "replay fixture cleared hardware=true changed=$cleared")
            }
            is DebugReplayCommand.SetFixture -> {
                DebugReplayPreferences.setFixtureName(context, command.name)
                Log.i(TAG, "replay fixture set name=${command.name}")
            }
            DebugReplayCommand.Unchanged -> {
                Log.i(TAG, "replay fixture ignored name=$raw")
            }
        }
    }

    companion object {
        const val ACTION = "app.galaxyvitals.DEBUG_ECG_REPLAY"
        const val EXTRA_FIXTURE = "fixture"
        private const val TAG = "EcgAcquisition"
    }
}
