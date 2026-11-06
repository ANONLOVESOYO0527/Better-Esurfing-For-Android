package dev.echoping.betteresurfing.service

internal object EngineStartPolicy {
    // Evaluate on the engine command queue, after any earlier Stop completes.
    fun shouldStart(force: Boolean, running: Boolean): Boolean = force || !running
}
