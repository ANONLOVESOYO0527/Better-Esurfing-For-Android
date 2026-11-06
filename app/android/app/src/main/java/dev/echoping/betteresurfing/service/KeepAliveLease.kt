package dev.echoping.betteresurfing.service

/** Owns the paired CPU/Wi-Fi lease, including rollback after partial acquisition. */
internal class KeepAliveLease(
    private val acquireCpu: () -> Unit,
    private val releaseCpu: () -> Unit,
    private val acquireWifi: () -> Unit,
    private val releaseWifi: () -> Unit,
) : AutoCloseable {
    private var active = false

    fun update(eligible: Boolean) {
        if (!eligible) { close(); return }
        if (active) return
        try {
            acquireCpu()
            acquireWifi()
            active = true
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun close() {
        active = false
        try { releaseWifi() } finally { releaseCpu() }
    }
}
