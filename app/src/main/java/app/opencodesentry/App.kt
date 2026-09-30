package app.opencodesentry

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Applies Material You dynamic colour when the platform supports it, so the
 * three screens pick up the device wallpaper palette instead of a fixed one.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
