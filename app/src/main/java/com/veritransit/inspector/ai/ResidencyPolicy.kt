package com.veritransit.inspector.ai

/**
 * When the engine may drop the resident model. Pure policy with no Android
 * imports: the engine supplies the clock and acts on the verdicts, so the
 * numbers stay testable without a device.
 */
object ResidencyPolicy {

    /**
     * Idle residency: the four weight-shared HTP contexts hold several GB of
     * cDSP that other clients — and our own next load after a process change
     * — need to get. A cold load back costs tens of seconds, so the timeout
     * must be long enough that a normal inspection burst (a scan every minute
     * or two) never pays it. Ten minutes of true idle means the shift has
     * moved on and the memory is worth more than the residency.
     */
    const val IDLE_UNLOAD_AFTER_MS = 10 * 60 * 1000L

    /**
     * Background grace: the screen goes dark at a weighbridge for a minute at
     * a time; unloading on every onStop would make the next scan wait for a
     * cold load. Two minutes of *continuous* background means the officer has
     * actually left the app.
     */
    const val BACKGROUND_UNLOAD_AFTER_MS = 2 * 60 * 1000L

    /** True once [nowMs] is at least [timeoutMs] past the last real use. */
    fun idleUnloadDue(lastUsedAtMs: Long, nowMs: Long, timeoutMs: Long = IDLE_UNLOAD_AFTER_MS): Boolean =
        nowMs - lastUsedAtMs >= timeoutMs

    /**
     * True once the app has been continuously backgrounded long enough.
     * [backgroundedAtMs] null means foregrounded — never due.
     */
    fun backgroundUnloadDue(backgroundedAtMs: Long?, nowMs: Long, graceMs: Long = BACKGROUND_UNLOAD_AFTER_MS): Boolean =
        backgroundedAtMs != null && nowMs - backgroundedAtMs >= graceMs
}
