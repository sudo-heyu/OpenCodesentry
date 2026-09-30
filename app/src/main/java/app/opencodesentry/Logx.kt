package app.opencodesentry

import android.util.Log

/**
 * Single log tag for the whole app, so a device run can be reviewed with:
 *
 *     adb logcat -s OpenCodeNotify
 */
internal object Logx {

    const val TAG = "OpenCodeNotify"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }
}
