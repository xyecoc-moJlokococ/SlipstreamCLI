package app.vaydns.service

/**
 * Counts detached native-thread incidents in a sliding window.
 * Pure multiplatform.
 */
class DetachedThreadWatch(
    private val incidentThreshold: Int,
    private val windowMs: Long
) {
    private val incidents = ArrayDeque<Long>()

    fun onIncident(now: Long): Boolean {
        incidents.addLast(now)
        while (incidents.isNotEmpty() && now - incidents.first() > windowMs) {
            incidents.removeFirst()
        }
        return incidents.size >= incidentThreshold
    }

    fun countInWindow(): Int = incidents.size

    fun reset() {
        incidents.clear()
    }
}
