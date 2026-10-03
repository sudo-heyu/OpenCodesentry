package app.opencodesentry

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.Html
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.opencodesentry.databinding.ActivityMainBinding
import app.opencodesentry.databinding.PageConfigBinding
import app.opencodesentry.databinding.PageConsoleBinding
import app.opencodesentry.databinding.PageOptionsBinding
import app.opencodesentry.databinding.PageStatusBinding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Four screens behind a bottom bar:
 *
 * - 主页  the OpenCode web console (official UI), entered by scanning the
 *         desktop pairing QR; also the place the alert credentials come from
 * - 状态  what the guard is doing right now, plus the permission self-check
 * - 选项  which events alert, and how they alert
 * - 配置  the two tailnet addresses and the API credentials
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: Settings
    private lateinit var alerter: Alerter

    private val handler = Handler(Looper.getMainLooper())
    private var loading = false

    private val status: PageStatusBinding get() = binding.pageStatus
    private val options: PageOptionsBinding get() = binding.pageOptions
    private val config: PageConfigBinding get() = binding.pageConfig
    private val console: PageConsoleBinding get() = binding.pageConsole

    /** Set once the console WebView has content; tab switches reuse it. */
    private var consoleLoaded = false
    private var consolePageVisible = false
    private lateinit var consoleBack: OnBackPressedCallback

    /** Currently selected bottom-bar page; survives configuration changes. */
    private var currentPage = 0

    private val statusTicker = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1_000)
        }
    }

    private val pickSound =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val picked: Uri? = result.data?.let {
                IntentCompat.getParcelableExtra(
                    it,
                    RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    Uri::class.java,
                )
            }
            if (picked == null) {
                settings.soundUri = Settings.SOUND_SILENT
                settings.soundLabel = "静音"
            } else {
                settings.soundUri = picked.toString()
                settings.soundLabel = runCatching {
                    RingtoneManager.getRingtone(this, picked)?.getTitle(this)
                }.getOrNull() ?: "自定义"
            }
            renderSound()
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            toast(if (granted) "已授予通知权限" else "通知权限被拒绝")
            renderStatus()
        }

    private val scanPairing =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val url = result.data?.getStringExtra(ScanActivity.EXTRA_URL)
                ?: return@registerForActivityResult
            onPairingScanned(url)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        settings = Settings(this)
        seedFromIntent(intent)
        alerter = Alerter(this, settings)
        alerter.ensureChannels()

        consoleBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                console.webConsole.goBack()
            }
        }
        onBackPressedDispatcher.addCallback(this, consoleBack)
        currentPage = savedInstanceState?.getInt(KEY_PAGE, 0) ?: 0

        bindConsolePage()
        bindBottomNav()
        bindStatusPage()
        bindOptionsPage()
        bindConfigPage()
        binding.btnHelp.setOnClickListener { showHelp() }
        loadFromSettings()
        renderStatus()

        // First run only: the guide is the one thing a new user cannot guess.
        // After this it lives behind the header button and never auto-opens.
        if (!settings.helpSeen) {
            settings.helpSeen = true
            binding.root.post { showHelp() }
        }
    }

    override fun onResume() {
        super.onResume()
        alerter.ensureChannels()
        // Opening the app counts as acknowledging the alert: silence it.
        alerter.stop()
        loadFromSettings()
        // Self-heal: if the user expects the guard to run but the process was
        // killed, bring it back the moment the app is opened.
        if (settings.enabled && !ServiceStatus.running) {
            NotifyService.start(this)
        }
        if (consolePageVisible && !consoleLoaded) openConsole()
        handler.post(statusTicker)
    }

    override fun onPause() {
        handler.removeCallbacks(statusTicker)
        super.onPause()
    }

    override fun onDestroy() {
        console.webConsole.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_PAGE, currentPage)
        super.onSaveInstanceState(outState)
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    /**
     * `enableEdgeToEdge()` draws behind the system bars. BottomNavigationView
     * pads itself for the navigation bar, but the header does not, so the
     * header has to be pushed below the status bar by hand.
     *
     * The activity uses `adjustNothing`, so the window is never resized for
     * the keyboard: the bottom bar stays exactly where it is and the keyboard
     * simply covers it. The pages get the keyboard height as bottom padding
     * instead, so their fields can still be scrolled clear of it.
     */
    private fun applyWindowInsets() {
        val navContentHeight = (NAV_CONTENT_DP * resources.displayMetrics.density).toInt()
        val headerTopExtra = (HEADER_TOP_EXTRA_DP * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            Logx.i(
                "insets bars.bottom=${bars.bottom} ime.bottom=${ime.bottom} " +
                    "navBars.bottom=${insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom}",
            )
            binding.appBar.updatePadding(top = bars.top + headerTopExtra)
            binding.pageContainer.updatePadding(
                bottom = (ime.bottom - bars.bottom).coerceAtLeast(0),
            )
            // A fixed height is used for the bar (wrap_content mis-measures and
            // swallows the whole screen), so the gesture-bar inset has to be
            // added back on top of the content height by hand.
            val params = binding.bottomNav.layoutParams
            val wanted = navContentHeight + bars.bottom
            if (params.height != wanted) {
                params.height = wanted
                binding.bottomNav.layoutParams = params
            }
            insets
        }
    }

    private fun bindBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_status -> selectPage(1)
                R.id.nav_options -> selectPage(2)
                R.id.nav_config -> selectPage(3)
                else -> selectPage(0)
            }
            true
        }
        bindBottomNavInitial()
    }

    private fun bindBottomNavInitial() {
        binding.bottomNav.selectedItemId = NAV_IDS[currentPage.coerceIn(0, NAV_IDS.lastIndex)]
        selectPage(currentPage)
    }

    private fun selectPage(index: Int) {
        currentPage = index
        val pages = listOf(console.root, status.root, options.root, config.root)
        pages.forEachIndexed { i, view ->
            view.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        binding.tvHeaderTitle.text = when (index) {
            1 -> getString(R.string.tab_status)
            2 -> getString(R.string.tab_options)
            3 -> getString(R.string.tab_config)
            else -> getString(R.string.tab_home)
        }
        consolePageVisible = index == 0
        consoleBack.isEnabled = consolePageVisible && console.webConsole.canGoBack()
        if (consolePageVisible) openConsole()
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private fun bindStatusPage() {
        status.swEnabled.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            settings.enabled = checked
            if (checked) {
                val blocked = buildList {
                    if (!settings.isConfigured()) add("先在「配置」页填写电脑端地址和密码")
                    if (!isIgnoringBatteryOptimizations()) add("把「电池优化白名单」设为已就绪，否则后台无法自动重启")
                }
                if (blocked.isNotEmpty()) toast("请先：" + blocked.joinToString("；"))
                NotifyService.start(this)
            } else {
                NotifyService.stop(this)
            }
            renderStatus()
        }

        status.rowKeeper.setOnClickListener { onKeeperRow() }
        status.rowAutostart.setOnClickListener { onAutostartRow() }
        status.rowHighPower.setOnClickListener { onBackgroundRow() }
        status.rowBattery.setOnClickListener {
            if (isIgnoringBatteryOptimizations()) toast("已在电池优化白名单中") else requestIgnoreBatteryOptimizations()
        }
        status.rowNotify.setOnClickListener {
            if (notificationsEnabled()) {
                toast("通知权限已授予")
            } else {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        status.rowDnd.setOnClickListener {
            if (dndAccessGranted()) toast("勿扰访问已授予") else openDndSettings()
        }
        status.btnTestAlert.setOnClickListener { sendTestAlert() }
        status.btnStopAlert.setOnClickListener {
            alerter.stop()
            toast("已停止响铃")
        }
        status.btnClearDiagnostics.setOnClickListener {
            settings.clearDiagnostics()
            toast("已清零")
            renderDiagnostics()
        }
    }

    private fun renderStatus() {
        val running = ServiceStatus.running
        status.viewStatusDot.backgroundTintList =
            ColorStateList.valueOf(getColor(if (running) R.color.state_ok else R.color.state_idle))
        status.tvGuardState.text = if (running) "运行中" else "已停止"
        status.tvGuardHint.text =
            if (running) "守护已常驻，熄屏也会提醒" else "打开下面的开关即可常驻后台"

        if (status.swEnabled.isChecked != settings.enabled) {
            loading = true
            status.swEnabled.isChecked = settings.enabled
            loading = false
        }

        status.tvConnection.text = ServiceStatus.connection
        status.tvLastEvent.text = ServiceStatus.lastEvent
        if (ServiceStatus.lastError.isBlank()) {
            status.tvLastError.visibility = View.GONE
        } else {
            status.tvLastError.text = "错误：${ServiceStatus.lastError}"
            status.tvLastError.visibility = View.VISIBLE
        }

        paintState(status.tvKeeperState, keeperEnabled())
        // The two vendor switches have no query API, so they are labelled as
        // actions, with the detected brand appended for orientation.
        val vendorSuffix = VendorShortcuts.vendorSuffix()
        status.tvAutostartTitle.text = "自启动$vendorSuffix"
        status.tvBackgroundTitle.text = "后台运行$vendorSuffix"
        paintAction(status.tvAutostartState)
        paintAction(status.tvHighPowerState)
        paintState(status.tvBatteryState, isIgnoringBatteryOptimizations())
        paintState(status.tvNotifyState, notificationsEnabled())
        paintState(status.tvDndState, dndAccessGranted())
        paintState(status.tvExactState, canScheduleExactAlarms())
        status.tvStatusPhoneIp.text = TailnetIp.describe(settings.phoneTailnetIp)

        renderDiagnostics()
    }

    /**
     * The revival counters, so "did it actually stay alive" is a number and
     * not a feeling. Everything here is read from preferences on purpose —
     * the events being counted are process deaths.
     */
    private fun renderDiagnostics() {
        val now = System.currentTimeMillis()
        val startedAt = when {
            ServiceStatus.running && ServiceStatus.startedAt > 0 -> ServiceStatus.startedAt
            settings.guardStartedAt > 0 -> settings.guardStartedAt
            else -> 0L
        }
        status.tvDiagUptime.text = if (startedAt == 0L) {
            "尚未启动"
        } else {
            "${formatDuration(now - startedAt)} · 第 ${settings.guardStartCount} 次启动"
        }

        status.tvDiagHeals.text = if (settings.healCount == 0) {
            "0"
        } else {
            "${settings.healCount} 次 · 最近 ${formatDuration(now - settings.lastHealAt)}前"
        }

        status.tvDiagHeartbeat.text = when {
            !keeperEnabled() -> "未开启"
            settings.lastHeartbeatAt == 0L -> "等待首次心跳…"
            else -> "${formatDuration(now - settings.lastHeartbeatAt)}前"
        }
    }

    private fun paintState(view: TextView, ready: Boolean) {
        val color = getColor(if (ready) R.color.state_ok else R.color.state_bad)
        view.text = if (ready) "已就绪" else "待处理"
        view.setTextColor(color)
        view.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, CHIP_TINT_ALPHA))
    }

    /**
     * Rows whose state cannot be detected (the vendor autostart switches have
     * no public API) are labelled as actions instead of lying about them.
     * The wording is deliberately "open the settings page", not "go and grant
     * it": the app has no way to know whether the switch is already on, and a
     * label that reads like a pending error makes users think the app ignored
     * the permission they just granted.
     */
    private fun paintAction(view: TextView) {
        val color = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)
        view.text = "打开设置"
        view.setTextColor(color)
        view.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, CHIP_TINT_ALPHA))
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds 秒"
            seconds < 3_600 -> "${seconds / 60} 分钟"
            seconds < 86_400 -> "${seconds / 3_600} 小时 ${(seconds % 3_600) / 60} 分"
            else -> "${seconds / 86_400} 天 ${(seconds % 86_400) / 3_600} 小时"
        }
    }

    // ------------------------------------------------------------------
    // Keep-alive rows
    // ------------------------------------------------------------------

    private fun onKeeperRow() {
        if (keeperEnabled()) {
            toast("无障碍守护已开启")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (keeperEnabled()) "无障碍守护" else "开启无障碍守护")
            .setMessage(
                "这是让守护被系统清理后能自动复活的关键一步，也是不使用 root 时唯一可行的方式。\n\n" +
                    "① 到「应用信息」页，点右上角 ⋮ → 允许受限设置\n" +
                    "    （Android 13+ 对侧载应用的限制；不做这一步，无障碍开关是灰的）\n" +
                    "② 再到「无障碍」中找到「OpenCode 通知」并开启\n\n" +
                    "该服务不读取屏幕内容、不感知任何界面事件，只用于后台自愈。" +
                    if (keeperEnabled()) "\n\n当前状态：已开启 ✓" else "",
            )
            .setPositiveButton("去无障碍设置") { _, _ -> VendorShortcuts.openAccessibilitySettings(this) }
            .setNeutralButton("去应用信息") { _, _ -> VendorShortcuts.openAppDetails(this) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onAutostartRow() {
        if (!VendorShortcuts.openAutostart(this)) {
            VendorShortcuts.openAppDetails(this)
            toast("未能直接打开「自启动」页，已跳到应用信息；若你已开启可忽略")
        }
    }

    private fun onBackgroundRow() {
        if (!VendorShortcuts.openBackground(this)) {
            VendorShortcuts.openAppDetails(this)
            toast("未能直接打开「后台运行」页，已跳到应用信息；若你已开启可忽略")
        }
    }

    /** Shows the scope / rules / permission-location guide. */
    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(
                Html.fromHtml(HelpContent.html(), Html.FROM_HTML_MODE_LEGACY),
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    /** True when this app's accessibility service is enabled in system settings. */
    private fun keeperEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == packageName }
    }

    // ------------------------------------------------------------------
    // 选项
    // ------------------------------------------------------------------

    private fun bindOptionsPage() {
        options.swEventDone.setOnCheckedChangeListener { _, v ->
            if (!loading) settings.notifyTaskDone = v
        }
        options.swEventPermission.setOnCheckedChangeListener { _, v ->
            if (!loading) settings.notifyPermission = v
        }
        options.swEventQuestion.setOnCheckedChangeListener { _, v ->
            if (!loading) settings.notifyQuestion = v
        }
        options.swEventError.setOnCheckedChangeListener { _, v ->
            if (!loading) settings.notifyError = v
        }

        options.swVibrate.setOnCheckedChangeListener { _, v ->
            if (loading) return@setOnCheckedChangeListener
            settings.vibrate = v
            if (v) alerter.previewAlert()
        }
        options.swSound.setOnCheckedChangeListener { _, v ->
            if (loading) return@setOnCheckedChangeListener
            settings.soundEnabled = v
            renderSound()
            if (v) alerter.previewAlert()
        }
        options.swVoice.setOnCheckedChangeListener { _, v ->
            if (loading) return@setOnCheckedChangeListener
            settings.voiceEnabled = v
            if (v) alerter.previewVoice(AlertKind.TASK_DONE)
        }

        options.acVoice.setSimpleItems(Voice.entries.map { it.label }.toTypedArray())
        options.acVoice.setOnItemClickListener { _, _, position, _ ->
            settings.voiceId = Voice.entries[position].id
            alerter.previewVoice(AlertKind.TASK_DONE)
        }

        options.sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (loading) return
                settings.volume = value
                options.tvVolume.text = "音量 $value%"
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        options.btnPickSound.setOnClickListener { openSoundPicker() }

        options.etAlertMax.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveAlertMax()
        }
        options.etAlertMax.setOnEditorActionListener { _, _, _ ->
            saveAlertMax()
            false
        }

        options.btnPreviewAlert.setOnClickListener { alerter.previewAlert() }
        options.btnStopAlert.setOnClickListener {
            alerter.stop()
            toast("已停止响铃")
        }
        options.btnPreviewDone.setOnClickListener { alerter.previewVoice(AlertKind.TASK_DONE) }
        options.btnPreviewPermission.setOnClickListener { alerter.previewVoice(AlertKind.PERMISSION) }
        options.btnPreviewQuestion.setOnClickListener { alerter.previewVoice(AlertKind.QUESTION) }
        options.btnPreviewError.setOnClickListener { alerter.previewVoice(AlertKind.ERROR) }
        options.btnPreviewInterrupted.setOnClickListener { alerter.previewVoice(AlertKind.INTERRUPTED) }

        options.swLive.setOnCheckedChangeListener { _, v ->
            if (loading) return@setOnCheckedChangeListener
            settings.liveStream = v
            applyToService()
        }
        options.swAlarmClock.setOnCheckedChangeListener { _, v ->
            if (loading) return@setOnCheckedChangeListener
            settings.alarmClockWake = v
            applyToService()
        }
        options.etWatchdog.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveWatchdog()
        }
        options.etWatchdog.setOnEditorActionListener { _, _, _ ->
            saveWatchdog()
            false
        }
    }

    private fun renderSound() {
        options.tvSound.text = if (settings.soundEnabled) {
            "当前：${settings.soundLabel}"
        } else {
            "已关闭"
        }
    }

    private fun openSoundPicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            )
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                settings.soundUri?.takeIf { it != Settings.SOUND_SILENT }?.let(Uri::parse),
            )
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择提示音")
        }
        runCatching { pickSound.launch(intent) }.onFailure { toast("无法打开铃声选择器") }
    }

    private fun saveWatchdog() {
        settings.watchdogMinutes = options.etWatchdog.text.toString().toIntOrNull() ?: 5
        loading = true
        options.etWatchdog.setText(settings.watchdogMinutes.toString())
        loading = false
        applyToService()
    }

    private fun saveAlertMax() {
        settings.alertMaxSeconds = options.etAlertMax.text.toString().toIntOrNull()
            ?: Settings.DEFAULT_ALERT_MAX_SECONDS
        loading = true
        options.etAlertMax.setText(settings.alertMaxSeconds.toString())
        loading = false
    }

    // ------------------------------------------------------------------
    // 主页 / 控制台
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun bindConsolePage() {
        val web = console.webConsole
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        // The WebView sits between the app bar and the bottom bar, so it must
        // not report the system bars as safe-area insets: the official web
        // client honours env(safe-area-inset-*) and would pad itself twice.
        ViewCompat.setOnApplyWindowInsetsListener(web) { _, _ -> WindowInsetsCompat.CONSUMED }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val uri = request.url
                val scheme = uri.scheme.orEmpty()
                if (scheme != "http" && scheme != "https") {
                    openExternally(uri)
                    return true
                }
                val expected = settings.hostAddress
                if (expected.isNotBlank() && uri.host != expected) {
                    openExternally(uri)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                console.progressConsole.visibility = View.GONE
                consoleBack.isEnabled = consolePageVisible && view.canGoBack()
                capturePairingCredential()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) showConsoleUnreachable()
            }
        }
        console.btnConsoleScan.setOnClickListener { launchScanner() }
        console.btnConsoleRetry.setOnClickListener { openConsole(force = true) }
    }

    private fun launchScanner() {
        runCatching { scanPairing.launch(Intent(this, ScanActivity::class.java)) }
            .onFailure { toast("无法打开扫码界面，请在「配置」页手动填写") }
    }

    /**
     * The QR was scanned: point the settings at that server and load the
     * one-time link, which redeems the code and leaves a session cookie in the
     * WebView jar. [capturePairingCredential] turns that cookie into the API
     * password the guard uses.
     */
    private fun onPairingScanned(url: String) {
        val link = Pairing.parse(url) ?: return
        settings.hostAddress = link.host
        settings.hostPort = link.port
        loading = true
        config.etHost.setText(link.host)
        config.etPort.setText(link.port.toString())
        loading = false

        consoleLoaded = true
        showConsoleWebView()
        console.progressConsole.visibility = View.VISIBLE
        console.webConsole.loadUrl(link.url)
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    private fun openConsole(force: Boolean = false) {
        if (!settings.isConfigured()) {
            showConsoleSetup(
                R.string.console_setup_title,
                R.string.console_setup_body,
                retry = false,
            )
            return
        }
        if (!consoleLoaded || force) {
            consoleLoaded = true
            console.progressConsole.visibility = View.VISIBLE
            showConsoleWebView()
            thread {
                val link = OpenCodeClient(Settings(this)).pairingLink()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    link.fold(
                        onSuccess = { console.webConsole.loadUrl(it.url) },
                        onFailure = { showConsoleUnreachable() },
                    )
                }
            }
        } else {
            showConsoleWebView()
        }
    }

    private fun showConsoleWebView() {
        console.consoleSetup.visibility = View.GONE
        console.webConsole.visibility = View.VISIBLE
    }

    private fun showConsoleSetup(titleRes: Int, bodyRes: Int, retry: Boolean) {
        console.progressConsole.visibility = View.GONE
        console.webConsole.visibility = View.GONE
        console.consoleSetup.visibility = View.VISIBLE
        console.tvConsoleTitle.setText(titleRes)
        console.tvConsoleBody.setText(bodyRes)
        console.btnConsoleRetry.visibility = if (retry) View.VISIBLE else View.GONE
    }

    private fun showConsoleUnreachable() {
        console.progressConsole.visibility = View.GONE
        if (settings.isConfigured()) {
            consoleLoaded = false
            showConsoleSetup(
                R.string.console_unreachable_title,
                R.string.console_unreachable_body,
                retry = true,
            )
        }
    }

    /**
     * Redeeming the pairing link leaves an `opencode_session_<port>` cookie.
     * Its value is a session token the API also accepts as the Basic password,
     * so one scan configures both the console and the alert guard. OpenCode
     * embeds the expiry (epoch seconds) as the token's numeric prefix, which
     * is what lets the config page warn before alerts silently stop.
     */
    private fun capturePairingCredential() {
        val url = settings.serverUrl
        if (url.isBlank()) return
        val cookie = CookieManager.getInstance().getCookie(url) ?: return
        val token = cookie
            .split(";")
            .map(String::trim)
            .firstOrNull { it.startsWith(PAIR_COOKIE_PREFIX) }
            ?.substringAfter("=")
            ?.takeIf(String::isNotBlank)
            ?: return
        if (token == settings.password) return

        settings.password = token
        settings.pairExpiresAt = token
            .takeWhile(Char::isDigit)
            .takeIf { it.length >= TOKEN_EXPIRY_DIGITS }
            ?.toLongOrNull()
            ?.times(1_000L)
            ?: 0L
        renderPairState()
        toast(getString(R.string.console_pair_ok))
        if (settings.enabled) NotifyService.sendAction(this, NotifyService.ACTION_START)
    }

    private fun openExternally(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { toast("没有可打开该链接的应用") }
    }

    // ------------------------------------------------------------------
    // 配置
    // ------------------------------------------------------------------

    private fun bindConfigPage() {
        config.btnSave.setOnClickListener {
            saveConfiguration()
            consoleLoaded = false
            toast("已保存")
            renderPhoneIpState()
            applyToService()
            renderStatus()
            renderPairState()
        }

        config.btnPairScan.setOnClickListener { launchScanner() }

        config.btnDetectIp.setOnClickListener {
            val detected = TailnetIp.detect()
            if (detected == null) {
                config.etPhoneIp.setText("")
                toast("未检测到 tailnet 地址，请确认 Tailscale 已连接")
            } else {
                config.etPhoneIp.setText(detected)
                settings.phoneTailnetIp = detected
                toast("已填入本机地址 $detected")
            }
            renderPhoneIpState()
        }

        config.btnTest.setOnClickListener { runConnectionTest() }

        config.etPhoneIp.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                settings.phoneTailnetIp = config.etPhoneIp.text.toString()
                renderPhoneIpState()
            }
        }
    }

    private fun saveConfiguration() {
        settings.phoneTailnetIp = config.etPhoneIp.text.toString()
        settings.hostAddress = config.etHost.text.toString()
        settings.hostPort = config.etPort.text.toString().toIntOrNull() ?: Settings.DEFAULT_PORT
        settings.username = config.etUser.text.toString().ifBlank { Settings.DEFAULT_USERNAME }
        settings.password = config.etPassword.text.toString()
    }

    private fun renderPhoneIpState() {
        val text = TailnetIp.describe(config.etPhoneIp.text.toString().trim())
        config.tvPhoneIpState.text = text
        status.tvStatusPhoneIp.text = text
    }

    /** Shows where the current credential came from and when it expires. */
    private fun renderPairState() {
        val expiry = settings.pairExpiresAt
        config.tvPairState.text = when {
            expiry <= 0L -> getString(R.string.config_pair_none)
            System.currentTimeMillis() > expiry -> getString(
                R.string.config_pair_expired,
                formatDate(expiry),
            )
            else -> getString(R.string.config_pair_valid, formatDate(expiry))
        }
    }

    private fun formatDate(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMillis))

    private fun runConnectionTest() {
        saveConfiguration()
        renderPhoneIpState()

        val pending = buildString {
            appendLine("本机：${TailnetIp.describe(settings.phoneTailnetIp)}")
            appendLine("电脑端：${settings.serverUrl.ifBlank { "未填写" }}")
            append("正在测试连接…")
        }
        config.tvStatus.text = pending

        thread {
            val result = OpenCodeClient(Settings(this)).probe()
            val report = buildString {
                appendLine("本机：${TailnetIp.describe(settings.phoneTailnetIp)}")
                appendLine("电脑端：${settings.serverUrl.ifBlank { "未填写" }}")
                appendLine("用户名：${settings.username}")
                append(
                    result.fold(
                        onSuccess = { "连接成功 · OpenCode $it" },
                        onFailure = { "连接失败：${it.message}" },
                    ),
                )
            }
            runOnUiThread { config.tvStatus.text = report }
        }
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    private fun loadFromSettings() {
        loading = true

        // First run: offer the address Tailscale actually gave this device,
        // instead of making the user go hunting in the Tailscale app.
        if (settings.phoneTailnetIp.isEmpty()) {
            TailnetIp.detect()?.let { settings.phoneTailnetIp = it }
        }
        config.etPhoneIp.setText(settings.phoneTailnetIp)
        config.etHost.setText(settings.hostAddress)
        config.etPort.setText(settings.hostPort.toString())
        config.etUser.setText(settings.username)
        config.etPassword.setText(settings.password)

        status.swEnabled.isChecked = settings.enabled

        options.swEventDone.isChecked = settings.notifyTaskDone
        options.swEventPermission.isChecked = settings.notifyPermission
        options.swEventQuestion.isChecked = settings.notifyQuestion
        options.swEventError.isChecked = settings.notifyError

        options.swVibrate.isChecked = settings.vibrate
        options.swSound.isChecked = settings.soundEnabled
        options.swVoice.isChecked = settings.voiceEnabled
        options.acVoice.setText(Voice.fromId(settings.voiceId).label, false)
        options.sbVolume.progress = settings.volume
        options.tvVolume.text = "音量 ${settings.volume}%"
        options.etAlertMax.setText(settings.alertMaxSeconds.toString())

        options.swLive.isChecked = settings.liveStream
        options.swAlarmClock.isChecked = settings.alarmClockWake
        options.etWatchdog.setText(settings.watchdogMinutes.toString())

        renderSound()
        renderPairState()
        loading = false
        renderPhoneIpState()
    }

    private fun applyToService() {
        if (settings.enabled) NotifyService.sendAction(this, NotifyService.ACTION_START)
        renderStatus()
    }

    /** Posts a real notification so the whole alert path gets exercised. */
    private fun sendTestAlert() {
        if (settings.enabled) {
            NotifyService.sendAction(this, NotifyService.ACTION_TEST)
        } else {
            alerter.alert(AlertKind.TASK_DONE, "这是一条测试提醒")
        }
    }

    // ------------------------------------------------------------------
    // System settings helpers
    // ------------------------------------------------------------------

    private fun notificationsEnabled(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val power = getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }
            .onFailure {
                runCatching {
                    startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }.onFailure { toast("请手动在电池设置中加入白名单") }
            }
    }

    private fun dndAccessGranted(): Boolean =
        getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    private fun openDndSettings() {
        runCatching {
            startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }.onFailure { toast("请手动在勿扰设置中授权") }
    }

    private fun canScheduleExactAlarms(): Boolean =
        getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Debug builds only. `tools/mac/e2e-emulator.sh` seeds the connection by
     * launching this activity with extras, because settings now live in an
     * encrypted file that cannot be written from outside the app. Release
     * builds ignore the extras.
     */
    private fun seedFromIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val host = intent?.getStringExtra(EXTRA_SEED_HOST) ?: return
        val password = intent.getStringExtra(EXTRA_SEED_PASSWORD) ?: return
        settings.hostAddress = host
        settings.hostPort = intent.getIntExtra(EXTRA_SEED_PORT, Settings.DEFAULT_PORT)
        settings.username = intent.getStringExtra(EXTRA_SEED_USER) ?: Settings.DEFAULT_USERNAME
        settings.password = password
        settings.phoneTailnetIp = intent.getStringExtra(EXTRA_SEED_PHONE_IP).orEmpty()
        settings.enabled = true
    }

    private companion object {
        /** Bottom bar content height, excluding the gesture-bar inset. */
        const val NAV_CONTENT_DP = 56

        /** Breathing room between the status bar and the header content. */
        const val HEADER_TOP_EXTRA_DP = 8

        /** Alpha (0-255) of the status-chip background tint. */
        const val CHIP_TINT_ALPHA = 40

        /** Cookie name prefix OpenCode uses for redeemed pairing sessions. */
        const val PAIR_COOKIE_PREFIX = "opencode_session_"

        /** The token's numeric prefix carries its expiry in epoch seconds. */
        const val TOKEN_EXPIRY_DIGITS = 10

        /** Saved-state key for the selected bottom-bar page. */
        const val KEY_PAGE = "selected_page"

        /** Bottom-bar item ids, in page order. */
        val NAV_IDS = intArrayOf(R.id.nav_home, R.id.nav_status, R.id.nav_options, R.id.nav_config)

        // Extras consumed by [seedFromIntent] in debug builds only.
        const val EXTRA_SEED_HOST = "seed_host"
        const val EXTRA_SEED_PORT = "seed_port"
        const val EXTRA_SEED_USER = "seed_user"
        const val EXTRA_SEED_PASSWORD = "seed_password"
        const val EXTRA_SEED_PHONE_IP = "seed_phone_ip"
    }
}
