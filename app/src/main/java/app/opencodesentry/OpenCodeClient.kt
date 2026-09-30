package app.opencodesentry

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A single event off the OpenCode SSE stream. */
data class ServerEvent(
    val type: String,
    val directory: String?,
    val data: JSONObject?,
)

interface StreamListener {
    fun onOpen()
    fun onEvent(event: ServerEvent)
    fun onClosed()
    fun onFailure(message: String)
}

/**
 * Thin client over the OpenCode v2 HTTP API.
 *
 * Auth is HTTP Basic with the username `opencode` and the password stored in
 * `~/.config/opencode/service.json` on the host.
 */
class OpenCodeClient(private val settings: Settings) {

    private val api: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * The server writes a `: heartbeat` comment every 15 seconds, so any
     * interval without a single byte means the socket is dead. 60s (four
     * missed heartbeats) detects a half-open connection quickly without
     * tripping on a momentarily busy server. okhttp-sse does not surface
     * comments to the listener, but the bytes still reset the read timer.
     */
    private val streaming: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun endpoint(path: String): String = settings.serverUrl.trimEnd('/') + path

    private fun authorization(): String =
        Credentials.basic(settings.username.ifBlank { Settings.DEFAULT_USERNAME }, settings.password)

    private fun get(path: String): Request = Request.Builder()
        .url(endpoint(path))
        .header("Authorization", authorization())
        .header("Accept", "application/json")
        .build()

    /** Round-trips `/api/info`; used by the "test connection" button. */
    fun probe(): Result<String> = runCatching {
        api.newCall(get("/api/info")).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${body.take(200)}")
            }
            JSONObject(body).optString("version", "unknown")
        }
    }

    fun openEventStream(listener: StreamListener): EventSource {
        val request = Request.Builder()
            .url(endpoint("/api/event"))
            .header("Authorization", authorization())
            .header("Accept", "text/event-stream")
            .build()

        return EventSources.createFactory(streaming).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: okhttp3.Response) {
                    listener.onOpen()
                }

                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    val parsed = runCatching { JSONObject(data) }.getOrNull() ?: return
                    val eventType = parsed.optString("type", type.orEmpty())
                    if (eventType.isEmpty()) return
                    listener.onEvent(
                        ServerEvent(
                            type = eventType,
                            directory = parsed.optJSONObject("location")?.optString("directory"),
                            data = parsed.optJSONObject("data"),
                        ),
                    )
                }

                override fun onClosed(eventSource: EventSource) = listener.onClosed()

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: okhttp3.Response?,
                ) {
                    val detail = buildString {
                        append(t?.message ?: "stream failed")
                        if (response != null) append(" (HTTP ${response.code})")
                    }
                    listener.onFailure(detail)
                }
            },
        )
    }

    /** Session IDs OpenCode currently considers busy. */
    fun activeSessions(): Result<Set<String>> = runCatching {
        api.newCall(get("/api/session/active")).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val data = JSONObject(body).opt("data")
            when (data) {
                is JSONObject -> data.keys().asSequence().toSet()
                is JSONArray -> (0 until data.length())
                    .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(String::isNotEmpty) }
                    .toSet()
                else -> emptySet()
            }
        }
    }

    /** Terminal state of a session: succeeded / failed / interrupted. */
    fun sessionOutcome(sessionID: String): String? = runCatching {
        api.newCall(get("/api/session/$sessionID")).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@use null
            val root = JSONObject(body)
            val session = root.optJSONObject("data") ?: root
            session.optString("outcome").takeIf(String::isNotEmpty)
        }
    }.getOrNull()

    /** Count of pending permission requests and pending forms. */
    fun pendingCounts(): Result<Pair<Int, Int>> = runCatching {
        val permissions = countOf(get("/api/permission/request"))
        val forms = countOf(get("/api/form"))
        permissions to forms
    }

    private fun countOf(request: Request): Int =
        api.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            JSONObject(body).optJSONArray("data")?.length() ?: 0
        }
}
