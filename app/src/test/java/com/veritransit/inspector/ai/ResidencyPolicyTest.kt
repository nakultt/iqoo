package com.veritransit.inspector.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The unload-timing policy, pinned so a tuning change is a conscious one. */
class ResidencyPolicyTest {

    @Test
    fun `idle unload is not due inside the window`() {
        // Used 30 s ago; nothing is due for a long while yet.
        assertFalse(ResidencyPolicy.idleUnloadDue(lastUsedAtMs = 1_000_000L, nowMs = 1_030_000L))
    }

    @Test
    fun `idle unload is due at and past the timeout`() {
        val lastUsed = 1_000_000L
        val atTimeout = lastUsed + ResidencyPolicy.IDLE_UNLOAD_AFTER_MS
        assertTrue(ResidencyPolicy.idleUnloadDue(lastUsed, atTimeout))
        assertTrue(ResidencyPolicy.idleUnloadDue(lastUsed, atTimeout + 1))
    }

    @Test
    fun `background unload is never due while foregrounded`() {
        assertFalse(ResidencyPolicy.backgroundUnloadDue(backgroundedAtMs = null, nowMs = Long.MAX_VALUE / 2))
    }

    @Test
    fun `background unload is due only after continuous backgrounding`() {
        val bgAt = 5_000_000L
        assertFalse(ResidencyPolicy.backgroundUnloadDue(bgAt, nowMs = bgAt + ResidencyPolicy.BACKGROUND_UNLOAD_AFTER_MS - 1))
        assertTrue(ResidencyPolicy.backgroundUnloadDue(bgAt, nowMs = bgAt + ResidencyPolicy.BACKGROUND_UNLOAD_AFTER_MS))
    }

    @Test
    fun `an earlier background timestamp that was cleared does not count`() {
        // Foregrounded (null) after being backgrounded: the stale timestamp
        // must not survive as null replaces it — checked via the null branch.
        assertEquals(false, ResidencyPolicy.backgroundUnloadDue(null, nowMs = 0L))
    }

    @Test
    fun `policy timeouts relate like the shift actually runs`() {
        // A backgrounded officer returning within the grace period must never
        // have been unloaded by the idle path alone: the background grace is
        // shorter than the idle timeout, so backgrounding is the tighter of
        // the two triggers only when it fires first.
        assertTrue(ResidencyPolicy.BACKGROUND_UNLOAD_AFTER_MS < ResidencyPolicy.IDLE_UNLOAD_AFTER_MS)
    }
}
