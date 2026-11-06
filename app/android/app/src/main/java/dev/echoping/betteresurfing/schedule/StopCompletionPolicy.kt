package dev.echoping.betteresurfing.schedule

internal data class RestartRequest(val index: Int, val force: Boolean)

/** Decide from the latest intent and schedule after asynchronous cleanup, not before it. */
internal object StopCompletionPolicy {
    fun restart(manualIndex: Int?, activeIndex: Int, scheduledRun: Boolean): RestartRequest? = when {
        manualIndex != null -> RestartRequest(manualIndex, true)
        scheduledRun -> RestartRequest(activeIndex, false)
        else -> null
    }
}
