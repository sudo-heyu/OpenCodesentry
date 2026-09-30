package app.opencodesentry

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings

/**
 * Deep links into the manufacturer's own "keep this app alive" switches.
 *
 * Every OEM hides its autostart / background-run switches behind a different
 * activity, and none of them is queryable — there is no public API that reports
 * whether a vendor toggle is on. So the honest thing the app can do is open the
 * right screen and let the user confirm it.
 *
 * The candidate lists contain every known brand and are tried in order; only
 * the current device's packages ever resolve, so a single cross-brand list is
 * both simpler and more robust than branching on [Build.MANUFACTURER]. Brand
 * detection is still used, but only to label the rows and to pick the right
 * instructions in [HelpContent].
 *
 * Resolution alone is not enough: some of these activities are guarded by
 * system-only permissions (vivo's high-power page, OPPO's safecenter), so
 * starting them throws SecurityException and the loop has to move on to the
 * next candidate. All of it is wrapped, because a missing activity in a future
 * ROM build must never crash the app; a false return means the caller should
 * fall back to the app-details page.
 *
 * The packages are listed in `<queries>` in the manifest, otherwise package
 * visibility filtering (Android 11+) would hide them from `resolveActivity`.
 */
object VendorShortcuts {

    /** The ROM family, used for labels and instructions only. */
    enum class Vendor(val label: String) {
        XIAOMI("小米 / Redmi"),
        HUAWEI("华为 / 荣耀"),
        OPPO("OPPO / 一加 / realme"),
        VIVO("vivo / iQOO"),
        SAMSUNG("三星"),
        MEIZU("魅族"),
        OTHER(""),
    }

    fun vendor(): Vendor = vendorOf("${Build.MANUFACTURER} ${Build.BRAND}")

    /** Pure so it can be unit-tested without the device's own build props. */
    internal fun vendorOf(brand: String): Vendor {
        val b = brand.lowercase()
        return when {
            b.contains("xiaomi") || b.contains("redmi") || b.contains("poco") ->
                Vendor.XIAOMI
            b.contains("huawei") || b.contains("honor") -> Vendor.HUAWEI
            b.contains("oppo") || b.contains("oneplus") || b.contains("realme") ->
                Vendor.OPPO
            b.contains("vivo") || b.contains("iqoo") -> Vendor.VIVO
            b.contains("samsung") -> Vendor.SAMSUNG
            b.contains("meizu") -> Vendor.MEIZU
            else -> Vendor.OTHER
        }
    }

    /** `（小米 / Redmi）`, or empty when the brand is unknown. */
    fun vendorSuffix(): String = vendor().label.let { if (it.isEmpty()) "" else "（$it）" }

    /** Autostart / "allow the system to launch me" screens, best effort first. */
    private val AUTOSTART = listOf(
        // 小米 / Redmi
        "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        // 华为 / 荣耀
        "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
        // OPPO / 一加 / realme
        "com.coloros.safecenter/.startupapp.StartupAppListActivity",
        "com.coloros.safecenter/.permission.startupapp.StartupAppListActivity",
        "com.coloros.safecenter/com.coloros.privacypermissionsentry.PermissionTopActivity",
        "com.oppo.safe/.permission.startup.StartupAppListActivity",
        "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
        // vivo / iQOO
        "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
        "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
        "com.iqoo.secure/.phoneoptimize.BgStartUpManager",
        "com.vivo.permissionmanager/.activity.PurviewTabActivity",
        "com.iqoo.secure/.safeguard.PurviewTabActivity",
        // 三星
        "com.samsung.android.sm_cn/com.samsung.android.sm.autorun.ui.AutoRunActivity",
        "com.samsung.android.sm/com.samsung.android.sm.autorun.ui.AutoRunActivity",
        "com.samsung.android.sm_cn/com.samsung.android.sm.ui.ram.AutoRunActivity",
        "com.samsung.android.sm/com.samsung.android.sm.ui.ram.AutoRunActivity",
        // 魅族
        "com.meizu.safe/.SecurityCenterActivity",
    )

    /**
     * Background-run / battery screens. Deliberately contains *no* autostart
     * component, so this row can never land on the wrong screen.
     */
    private val BACKGROUND = listOf(
        // 小米 / Redmi
        "com.miui.powerkeeper/.ui.HiddenAppsContainerManagementActivity",
        // 华为 / 荣耀
        "com.huawei.systemmanager/.power.ui.HwPowerManagerActivity",
        // OPPO / 一加 / realme
        "com.coloros.safecenter/.appfrozen.activity.AppFrozenSettingsActivity",
        "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
        "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerSaverModeActivity",
        "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerConsumptionActivity",
        "com.oppo.safe/.SecureSafeMainActivity",
        // vivo / iQOO
        "com.vivo.abe/com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity",
        "com.iqoo.powersaving/.PowerSavingManagerActivity",
        // 三星
        "com.samsung.android.sm_cn/com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.samsung.android.sm/com.samsung.android.sm.ui.battery.BatteryActivity",
        // 通用
        "com.android.settings/.Settings\$HighPowerApplicationsActivity",
    )

    fun openAutostart(context: Context): Boolean = openFirst(context, AUTOSTART)

    fun openBackground(context: Context): Boolean = openFirst(context, BACKGROUND)

    /** The system app-details page; always available, used as the fallback. */
    fun openAppDetails(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    fun openAccessibilitySettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    private fun openFirst(context: Context, candidates: List<String>): Boolean {
        val manager = context.packageManager
        for (name in candidates) {
            val component = ComponentName.unflattenFromString(name) ?: continue
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (manager.resolveActivity(intent, 0) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                Logx.i("vendor shortcut -> $name")
                return true
            }
        }
        Logx.w("vendor shortcut not available on this build")
        return false
    }
}
