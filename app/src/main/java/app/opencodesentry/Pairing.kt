package app.opencodesentry

import android.net.Uri

/**
 * The desktop bridge's pairing link, exactly as encoded in the QR that
 * `opencode-bridge.py --pair` prints:
 *
 *     http://<host>:<port>/auth/connect/<code>
 *
 * Redeeming it is what creates the session credential, so the only rule the
 * scanner enforces is "it must be one of these". Everything else is rejected
 * with a hint instead of silently half-configuring the app.
 */
object Pairing {

    data class Link(
        /** Full one-time URL; loading it redeems the code. */
        val url: String,
        /** Scheme + authority of the server, e.g. `http://100.1.2.3:4096`. */
        val baseUrl: String,
        val host: String,
        val port: Int,
    )

    fun parse(raw: String): Link? {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val path = uri.path ?: return null
        if (!path.startsWith("/auth/connect/")) return null
        if (path.removePrefix("/auth/connect/").isBlank()) return null

        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            else -> 80
        }
        return Link(
            url = raw.trim(),
            baseUrl = "$scheme://$host:$port",
            host = host,
            port = port,
        )
    }
}
