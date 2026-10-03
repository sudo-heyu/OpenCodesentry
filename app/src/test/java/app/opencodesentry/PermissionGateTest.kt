package app.opencodesentry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Permission alerts must survive auto-approval. A live OpenCode 2.0.22 server
 * emits `permission.asked` even when a client (`opencode run --auto`, the
 * official web client's autoApprove) answers it immediately, so only a request
 * that is still unanswered after the grace period may ring.
 */
class PermissionGateTest {

    @Test
    fun `auto-approved request is dropped`() {
        val gate = PermissionGate()
        assertTrue(gate.asked("per_1"))
        assertTrue(gate.replied("per_1"))
        assertFalse(gate.fired("per_1"))
    }

    @Test
    fun `unanswered request fires once`() {
        val gate = PermissionGate()
        assertTrue(gate.asked("per_1"))
        assertTrue(gate.fired("per_1"))
        assertFalse(gate.fired("per_1"))
    }

    @Test
    fun `repeated ask schedules only once`() {
        val gate = PermissionGate()
        assertTrue(gate.asked("per_1"))
        assertFalse(gate.asked("per_1"))
    }

    @Test
    fun `reply for an unknown request is ignored`() {
        assertFalse(PermissionGate().replied("per_nope"))
    }

    @Test
    fun `requests are independent`() {
        val gate = PermissionGate()
        assertTrue(gate.asked("per_1"))
        assertTrue(gate.asked("per_2"))
        assertTrue(gate.replied("per_1"))
        assertFalse(gate.fired("per_1"))
        assertTrue(gate.fired("per_2"))
    }
}
