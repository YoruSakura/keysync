package com.devoid.keysync.service

internal class InputCaptureRecoveryController(
    private val stableChecksRequired: Int = 2,
    private val onInterrupted: () -> Unit,
    private val onRestored: () -> Unit
) {
    private var interrupted = true
    private var stableChecks = 0

    init {
        require(stableChecksRequired > 0)
    }

    fun reportUnavailable() {
        stableChecks = 0
        if (interrupted) return

        interrupted = true
        onInterrupted()
    }

    fun reportAvailable() {
        if (!interrupted) return

        stableChecks++
        if (stableChecks < stableChecksRequired) return

        interrupted = false
        stableChecks = stableChecksRequired
        onRestored()
    }

    fun reset() {
        interrupted = true
        stableChecks = 0
    }

    fun isInterrupted(): Boolean = interrupted
}
