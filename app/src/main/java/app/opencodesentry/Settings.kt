// EncryptedSharedPreferences is still the standard Keystore-backed store for a
// handful of small secrets. Jetpack Security has marked its crypto API
// deprecated without shipping a drop-in replacement, so the deprecation is
// suppressed file-locally rather than scattered through the call sites.
@file:Suppress("DEPRECATION")

package app.opencodesentry

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * All user-configurable state, backed by a single SharedPreferences file.
 *
 * The file also holds the OpenCode service password, so it is encrypted at
 * rest with a Keystore-backed AES key instead of being written in the clear.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences = openEncrypted(context.applicationContext)

    /** This phone's own address inside the tailnet, as entered by the user. */
    var phoneTailnetIp: String
        get() = prefs.getString(KEY_PHONE_IP, "")!!.trim()
        set(value) = prefs.edit { putString(KEY_PHONE_IP, value.trim()) }

    /** The machine running OpenCode: a tailnet IP, or a MagicDNS name. */
    var hostAddress: String
        get() = prefs.getString(KEY_HOST, "")!!.trim()
        set(value) = prefs.edit { putString(KEY_HOST, value.trim()) }

    /** Port the bridge (or Tailscale Serve) listens on, on the host. */
    var hostPort: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT).coerceIn(1, 65535)
        set(value) = prefs.edit { putInt(KEY_PORT, value.coerceIn(1, 65535)) }

    /**
     * Request base URL, derived from [hostAddress] and [hostPort].
     *
     * A bare host gets `http://` and the port; an address that already carries
     * a scheme (for example a `tailscale serve` HTTPS name) is used verbatim.
     */
    val serverUrl: String
        get() = buildServerUrl(hostAddress, hostPort)

    var username: String
        get() = prefs.getString(KEY_USERNAME, DEFAULT_USERNAME)!!.trim()
        set(value) = prefs.edit { putString(KEY_USERNAME, value.trim()) }

    /** The service password found in `~/.config/opencode/service.json`. */
    var password: String
        get() = prefs.getString(KEY_PASSWORD, "")!!
        set(value) = prefs.edit { putString(KEY_PASSWORD, value) }

    /** User's intent: the guard should be running. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /** Whether the first-run guide has been shown; it is never shown twice. */
    var helpSeen: Boolean
        get() = prefs.getBoolean(KEY_HELP_SEEN, false)
        set(value) = prefs.edit { putBoolean(KEY_HELP_SEEN, value) }

    /** true = hold an SSE connection (instant); false = only poll on alarms. */
    var liveStream: Boolean
        get() = prefs.getBoolean(KEY_LIVE_STREAM, true)
        set(value) = prefs.edit { putBoolean(KEY_LIVE_STREAM, value) }

    /** Minutes between watchdog wake-ups / polling rounds. */
    var watchdogMinutes: Int
        get() = prefs.getInt(KEY_WATCHDOG_MINUTES, 5).coerceIn(1, 120)
        set(value) = prefs.edit { putInt(KEY_WATCHDOG_MINUTES, value.coerceIn(1, 120)) }

    /** Use `setAlarmClock`, which survives Doze but pins an alarm icon. */
    var alarmClockWake: Boolean
        get() = prefs.getBoolean(KEY_ALARM_CLOCK, false)
        set(value) = prefs.edit { putBoolean(KEY_ALARM_CLOCK, value) }

    var notifyTaskDone: Boolean
        get() = prefs.getBoolean(KEY_EVENT_DONE, true)
        set(value) = prefs.edit { putBoolean(KEY_EVENT_DONE, value) }

    var notifyPermission: Boolean
        get() = prefs.getBoolean(KEY_EVENT_PERMISSION, true)
        set(value) = prefs.edit { putBoolean(KEY_EVENT_PERMISSION, value) }

    var notifyQuestion: Boolean
        get() = prefs.getBoolean(KEY_EVENT_QUESTION, true)
        set(value) = prefs.edit { putBoolean(KEY_EVENT_QUESTION, value) }

    var notifyError: Boolean
        get() = prefs.getBoolean(KEY_EVENT_ERROR, true)
        set(value) = prefs.edit { putBoolean(KEY_EVENT_ERROR, value) }

    var vibrate: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) = prefs.edit { putBoolean(KEY_VIBRATE, value) }

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_SOUND_ENABLED, value) }

    /** Chosen ringtone, or null for the system default alarm sound. */
    var soundUri: String?
        get() = prefs.getString(KEY_SOUND_URI, null)
        set(value) = prefs.edit { putString(KEY_SOUND_URI, value) }

    var soundLabel: String
        get() = prefs.getString(KEY_SOUND_LABEL, "系统默认闹钟铃声")!!
        set(value) = prefs.edit { putString(KEY_SOUND_LABEL, value) }

    var voiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_VOICE_ENABLED, value) }

    /** Id of the bundled Edge TTS voice used for spoken alerts. */
    var voiceId: String
        get() = prefs.getString(KEY_VOICE_ID, Voice.DEFAULT.id)!!
        set(value) = prefs.edit { putString(KEY_VOICE_ID, Voice.fromId(value).id) }

    /**
     * Hard cap on how long an alert may play, in seconds. Stops a long alarm
     * ringtone from going on forever when nobody is around to dismiss it.
     * 0 disables the cap.
     */
    var alertMaxSeconds: Int
        get() = prefs.getInt(KEY_ALERT_MAX_SECONDS, DEFAULT_ALERT_MAX_SECONDS).coerceIn(0, 300)
        set(value) = prefs.edit { putInt(KEY_ALERT_MAX_SECONDS, value.coerceIn(0, 300)) }

    /** 0..100, applied on top of the alarm stream volume. */
    var volume: Int
        get() = prefs.getInt(KEY_VOLUME, 100).coerceIn(0, 100)
        set(value) = prefs.edit { putInt(KEY_VOLUME, value.coerceIn(0, 100)) }

    /**
     * Wall-clock expiry of a credential obtained by scanning the pairing QR.
     *
     * OpenCode session tokens embed their expiry as the first ten digits of
     * the token itself, so the app can warn before alerts silently stop.
     * 0 means "not paired by QR" (a hand-entered password never expires).
     */
    var pairExpiresAt: Long
        get() = prefs.getLong(KEY_PAIR_EXPIRES_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_PAIR_EXPIRES_AT, value) }

    /**
     * Session IDs OpenCode considered busy at the last check. Used to notice
     * completions that happened while the live stream was down.
     */
    var lastActiveSessions: Set<String>
        get() = prefs.getStringSet(KEY_LAST_ACTIVE, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_LAST_ACTIVE, value) }

    // ------------------------------------------------------------------
    // Diagnostics
    //
    // These are stored in preferences rather than in memory on purpose: the
    // thing being measured is the process dying, so the counters have to
    // outlive the process to mean anything.
    // ------------------------------------------------------------------

    /** How many times the guard service instance has been created. */
    var guardStartCount: Int
        get() = prefs.getInt(KEY_GUARD_STARTS, 0)
        set(value) = prefs.edit { putInt(KEY_GUARD_STARTS, value) }

    /** Wall clock of the most recent guard start; 0 means "never ran". */
    var guardStartedAt: Long
        get() = prefs.getLong(KEY_GUARD_STARTED_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_GUARD_STARTED_AT, value) }

    /** Revivals triggered by [KeepAlive] after the guard had already run. */
    var healCount: Int
        get() = prefs.getInt(KEY_HEAL_COUNT, 0)
        set(value) = prefs.edit { putInt(KEY_HEAL_COUNT, value) }

    var lastHealAt: Long
        get() = prefs.getLong(KEY_LAST_HEAL_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_HEAL_AT, value) }

    /** Last time the accessibility keeper's heartbeat ran. */
    var lastHeartbeatAt: Long
        get() = prefs.getLong(KEY_LAST_HEARTBEAT, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_HEARTBEAT, value) }

    /** When the accessibility keeper was last (re)bound by the system. */
    var keeperConnectedAt: Long
        get() = prefs.getLong(KEY_KEEPER_CONNECTED_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_KEEPER_CONNECTED_AT, value) }

    fun clearDiagnostics() {
        prefs.edit {
            putInt(KEY_GUARD_STARTS, 0)
            putLong(KEY_GUARD_STARTED_AT, 0L)
            putInt(KEY_HEAL_COUNT, 0)
            putLong(KEY_LAST_HEAL_AT, 0L)
        }
    }

    fun isConfigured(): Boolean = serverUrl.isNotBlank() && password.isNotBlank()

    companion object {
        const val DEFAULT_USERNAME = "opencode"
        const val DEFAULT_PORT = 4096
        const val DEFAULT_ALERT_MAX_SECONDS = 20

        /** Stored in [soundUri] when the user picks "silent" in the picker. */
        const val SOUND_SILENT = "silent"

        /**
         * Turns what the user typed into a request base URL.
         *
         * - `100.101.102.103` + 4096      -> `http://100.101.102.103:4096`
         * - `my-mac` + 4096          -> `http://my-mac:4096`
         * - `https://host.ts.net`        -> used verbatim (port is ignored)
         * - blank                        -> "" (not configured)
         */
        fun buildServerUrl(hostAddress: String, port: Int): String {
            val host = hostAddress.trim().trimEnd('/')
            if (host.isEmpty()) return ""
            if (host.startsWith("http://") || host.startsWith("https://")) return host
            return "http://$host:${port.coerceIn(1, 65535)}"
        }

        private const val KEY_PHONE_IP = "phone_tailnet_ip"
        private const val KEY_HOST = "host_address"
        private const val KEY_PORT = "host_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HELP_SEEN = "help_seen"
        private const val KEY_LIVE_STREAM = "live_stream"
        private const val KEY_WATCHDOG_MINUTES = "watchdog_minutes"
        private const val KEY_ALARM_CLOCK = "alarm_clock_wake"
        private const val KEY_EVENT_DONE = "event_task_done"
        private const val KEY_EVENT_PERMISSION = "event_permission"
        private const val KEY_EVENT_QUESTION = "event_question"
        private const val KEY_EVENT_ERROR = "event_error"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_SOUND_URI = "sound_uri"
        private const val KEY_SOUND_LABEL = "sound_label"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_VOICE_ID = "voice_id"
        private const val KEY_ALERT_MAX_SECONDS = "alert_max_seconds"
        private const val KEY_VOLUME = "volume"
        private const val KEY_LAST_ACTIVE = "last_active_sessions"
        private const val KEY_PAIR_EXPIRES_AT = "pair_expires_at"

        private const val KEY_GUARD_STARTS = "diag_guard_starts"
        private const val KEY_GUARD_STARTED_AT = "diag_guard_started_at"
        private const val KEY_HEAL_COUNT = "diag_heal_count"
        private const val KEY_LAST_HEAL_AT = "diag_last_heal_at"
        private const val KEY_LAST_HEARTBEAT = "diag_last_heartbeat"
        private const val KEY_KEEPER_CONNECTED_AT = "diag_keeper_connected_at"
    }
}

private const val PREFS_FILE = "opencode_notify_secure"

/** File used by builds before the credential was encrypted at rest. */
private const val LEGACY_PREFS_FILE = "opencode_notify"

/**
 * Opens the encrypted store, creating the Keystore master key on first use.
 *
 * The plaintext file written by an older build is deleted first, so its
 * contents (including the password) never stay on disk; the encrypted store
 * uses a different name so there is no ambiguity between the two.
 */
private fun openEncrypted(context: Context): SharedPreferences {
    context.deleteSharedPreferences(LEGACY_PREFS_FILE)
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return try {
        encryptedPrefs(context, masterKey)
    } catch (_: Exception) {
        // Corrupt store, or the Keystore key was lost (e.g. after a reinstall):
        // start clean rather than crashing on every read.
        context.deleteSharedPreferences(PREFS_FILE)
        encryptedPrefs(context, masterKey)
    }
}

private fun encryptedPrefs(context: Context, masterKey: MasterKey): SharedPreferences =
    EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
