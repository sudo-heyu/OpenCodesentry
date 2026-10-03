package app.opencodesentry

import java.util.concurrent.ConcurrentHashMap

/**
 * Decides whether a `permission.asked` deserves an alert.
 *
 * OpenCode emits the event even when the request is approved without the user:
 * the official web client's autoApprove mode, `opencode run --auto` or a
 * pre-approving ruleset all answer it within milliseconds, and the server
 * follows with `permission.replied`. Only a request that is still unanswered
 * after the grace period is a real prompt, so the service holds the alert
 * until [fired] and drops it when [replied] arrives first.
 *
 * Safe to call from any thread: the SSE thread asks and replies while the
 * delayed check runs on the service scope.
 */
class PermissionGate {

    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Registers a request. False when it was already waiting. */
    fun asked(requestID: String): Boolean = pending.add(requestID)

    /** Marks a request as answered. True when it was still waiting. */
    fun replied(requestID: String): Boolean = pending.remove(requestID)

    /** Grace period is over: true when the request still needs the user. */
    fun fired(requestID: String): Boolean = pending.remove(requestID)
}
