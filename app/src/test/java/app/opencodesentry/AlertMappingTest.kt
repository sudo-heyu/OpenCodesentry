package app.opencodesentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event mapping is the part that decides whether the phone rings, so it is
 * pinned down here. Event names come from a live OpenCode 2.0.18 server.
 */
class AlertMappingTest {

    @Test
    fun `terminal and blocking events map to alerts`() {
        assertEquals(AlertKind.TASK_DONE, AlertKind.fromEventType("session.idle"))
        assertEquals(AlertKind.TASK_DONE, AlertKind.fromEventType("session.execution.succeeded"))
        assertEquals(AlertKind.PERMISSION, AlertKind.fromEventType("permission.asked"))
        assertEquals(AlertKind.QUESTION, AlertKind.fromEventType("form.created"))
        assertEquals(AlertKind.QUESTION, AlertKind.fromEventType("session.form.created"))
        assertEquals(AlertKind.ERROR, AlertKind.fromEventType("session.error"))
        assertEquals(AlertKind.ERROR, AlertKind.fromEventType("session.execution.failed"))
        assertEquals(AlertKind.INTERRUPTED, AlertKind.fromEventType("session.execution.interrupted"))
    }

    @Test
    fun `the chatty stream is ignored`() {
        // OpenCode emits one of these for every token; alerting on any of them
        // would make the phone unusable.
        val noise = listOf(
            "session.reasoning.delta",
            "session.text.delta",
            "session.tool.input.delta",
            "session.step.started",
            "session.step.ended",
            "session.tool.called",
            "session.tool.success",
            "session.usage.updated",
            "message.updated",
            "shell.exited",
            "server.connected",
            "server.event",
        )
        for (type in noise) {
            assertNull("$type should not alert", AlertKind.fromEventType(type))
        }
    }

    @Test
    fun `every kind has a bundled clip for every voice`() {
        for (kind in AlertKind.entries) {
            for (voice in Voice.entries) {
                assertTrue(
                    "missing clip for $kind/${voice.id}",
                    kind.clipFor(voice) != 0,
                )
            }
        }
    }

    @Test
    fun `each kind and voice pair maps to a distinct clip`() {
        val clips = AlertKind.entries.flatMap { kind ->
            Voice.entries.map { voice -> kind.clipFor(voice) }
        }
        assertEquals("a clip is reused across scenarios", clips.size, clips.toSet().size)
    }

    @Test
    fun `unknown voice ids fall back to the default`() {
        assertEquals(Voice.DEFAULT, Voice.fromId(null))
        assertEquals(Voice.DEFAULT, Voice.fromId(""))
        assertEquals(Voice.DEFAULT, Voice.fromId("nope"))
        assertEquals(Voice.YUNXI, Voice.fromId("yunxi"))
    }

    @Test
    fun `vibration patterns are well formed`() {
        for (kind in AlertKind.entries) {
            val pattern = kind.vibrationPattern
            assertTrue("$kind pattern too short", pattern.size >= 2)
            assertEquals("$kind must start with a 0 delay", 0L, pattern[0])
            assertTrue("$kind has a non-positive wait", pattern.drop(1).all { it > 0 })
        }
    }
}
