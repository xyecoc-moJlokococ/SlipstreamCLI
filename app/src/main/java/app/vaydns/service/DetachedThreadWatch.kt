package app.vaydns.service

/**
 * Counts "detached native thread" incidents in a sliding window and decides when the only clean
 * remedy left is a full process restart.
 *
 * Background: when the native slipstream client can't be stopped within STOP_JOIN_TIMEOUT it is
 * *detached* (leaked) rather than joined -- normally harmless, and the EADDRINUSE-escaping start
 * path just rebinds the next client on a fresh port. But a detached thread that is stuck in a
 * synchronous native call (observed live: an infinite loop in picoquic's stream-reassembly splay
 * tree inside handle_dns_response, 0 syscalls, 100% of one core) can never see the shutdown/stale
 * signal and burns a CPU core forever. Each recovery that hits this leaks another such thread, so
 * cores get eaten one by one and even the fresh client degrades as it starves for CPU. Rebinding on
 * a new port papers over the port conflict but not the burned cores. Past a threshold of these
 * incidents in a window, killing and relaunching the process (which is the only thing that actually
 * reaps a wedged native thread) is cheaper than accumulating burned cores.
 *
 * Pure/testable like [BridgeFailureWatch]; the caller feeds it EADDRINUSE incidents and acts on the
 * fire signal.
 */
class DetachedThreadWatch(
    private val incidentThreshold: Int,
    private val windowMs: Long
) {
    // Timestamps of recent incidents, oldest first. Bounded by the window, so this never grows past
    // incidentThreshold entries in practice.
    private val incidents = ArrayDeque<Long>()

    /**
     * Record one detached-thread incident (an EADDRINUSE on start = a previous client's thread is
     * still holding the port, i.e. it didn't die). Returns true when [incidentThreshold] incidents
     * have occurred within [windowMs] -- the caller should then trigger a clean process restart.
     *
     * A single incident (or two) is left to the fresh-port escape path; only a genuine spiral fires,
     * since a process restart drops the user's live connections and shouldn't happen on one blip.
     */
    fun onIncident(now: Long): Boolean {
        incidents.addLast(now)
        while (incidents.isNotEmpty() && now - incidents.first() > windowMs) {
            incidents.removeFirst()
        }
        return incidents.size >= incidentThreshold
    }

    /** Number of incidents currently inside the window (for logging/diagnostics). */
    fun countInWindow(): Int = incidents.size

    /** Clear the incident history -- call on a fresh tunnel session. */
    fun reset() {
        incidents.clear()
    }
}
