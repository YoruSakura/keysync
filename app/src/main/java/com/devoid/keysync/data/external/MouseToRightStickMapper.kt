package com.devoid.keysync.data.external

import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class StickPosition(
    val x: Int,
    val y: Int
) {
    companion object {
        val CENTER = StickPosition(0, 0)
    }
}

/**
 * Converts relative mouse motion into a velocity-based right-stick position.
 *
 * Relative deltas are deliberately not accumulated: a mouse is a velocity
 * source here, while a gamepad stick is expected to return to its center.
 */
internal class MouseToRightStickMapper(
    private val fullScaleVelocityPixelsPerSecond: Double = 1_800.0,
    private val noiseVelocityPixelsPerSecond: Double = 8.0,
    private val responseExponent: Double = 1.30,
    private val outputDeadzone: Double = 0.12,
    private val axisMaximum: Int = AXIS_MAXIMUM
) {
    init {
        require(fullScaleVelocityPixelsPerSecond > noiseVelocityPixelsPerSecond)
        require(noiseVelocityPixelsPerSecond >= 0.0)
        require(responseExponent > 0.0)
        require(outputDeadzone in 0.0..<1.0)
        require(axisMaximum > 0)
    }

    fun map(deltaX: Int, deltaY: Int, elapsedNanos: Long): StickPosition {
        if (deltaX == 0 && deltaY == 0) return StickPosition.CENTER

        val sampleSeconds = elapsedNanos
            .coerceIn(MIN_SAMPLE_NANOS, MAX_SAMPLE_NANOS)
            .toDouble() / NANOS_PER_SECOND
        val velocityX = deltaX / sampleSeconds
        val velocityY = deltaY / sampleSeconds
        val speed = hypot(velocityX, velocityY)
        if (speed <= noiseVelocityPixelsPerSecond) return StickPosition.CENTER

        val normalizedSpeed = (
            (speed - noiseVelocityPixelsPerSecond) /
                (fullScaleVelocityPixelsPerSecond - noiseVelocityPixelsPerSecond)
            ).coerceIn(0.0, 1.0)
        val curvedMagnitude = normalizedSpeed.pow(responseExponent)
        val compensatedMagnitude = outputDeadzone + curvedMagnitude * (1.0 - outputDeadzone)

        // Dividing by the two-dimensional magnitude keeps diagonals on the
        // same unit circle as horizontal and vertical motion.
        val scale = compensatedMagnitude * axisMaximum / speed
        return StickPosition(
            x = (velocityX * scale).roundToInt().coerceIn(-axisMaximum, axisMaximum),
            y = (velocityY * scale).roundToInt().coerceIn(-axisMaximum, axisMaximum)
        )
    }

    companion object {
        const val AXIS_MAXIMUM = 32_767
        const val DEFAULT_SAMPLE_NANOS = 8_000_000L
        const val MAX_CONTINUOUS_SAMPLE_GAP_NANOS = 20_000_000L
        private const val MIN_SAMPLE_NANOS = 1_000_000L
        private const val MAX_SAMPLE_NANOS = 16_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
