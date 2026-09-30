package app.opencodesentry

/** Small piece of shared state the settings screen polls for display. */
object ServiceStatus {
    @Volatile
    var running: Boolean = false

    /** Wall clock of this guard start, for the "已运行" readout. */
    @Volatile
    var startedAt: Long = 0L

    @Volatile
    var connection: String = "未启动"

    @Volatile
    var lastEvent: String = "—"

    @Volatile
    var lastError: String = ""
}
