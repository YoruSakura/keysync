package com.devoid.keysync.service

import androidx.compose.ui.geometry.Offset

/**
 * Keeps the logical cursor position current for clicks while limiting visual
 * state updates to one per display frame.
 */
internal class SoftwareCursorController(
    initialPosition: Offset,
    maximumPosition: Offset,
    private val scheduleFrame: (() -> Unit) -> Unit,
    private val renderPosition: (Offset) -> Unit
) {
    @Volatile var position: Offset = initialPosition
        private set
    private var maximumPosition = maximumPosition

    private var frameScheduled = false
    private var generation = 0L

    /** Moves the cursor and returns the delta which survived screen clamping. */
    @Synchronized fun moveBy(delta: Offset): Offset {
        if (!delta.x.isFinite() || !delta.y.isFinite()) return Offset.Zero

        val previousPosition = position
        val nextPosition = Offset(
            x = (previousPosition.x + delta.x).coerceIn(0f, maximumPosition.x),
            y = (previousPosition.y + delta.y).coerceIn(0f, maximumPosition.y)
        )
        if (nextPosition == previousPosition) return Offset.Zero

        position = nextPosition
        scheduleRender()
        return nextPosition - previousPosition
    }

    /** Updates the active display bounds and immediately clamps stale coordinates. */
    @Synchronized fun updateMaximumPosition(maximumPosition: Offset): Offset {
        if (
            !maximumPosition.x.isFinite() || !maximumPosition.y.isFinite() ||
            maximumPosition.x < 0f || maximumPosition.y < 0f
        ) return position

        this.maximumPosition = maximumPosition
        val clampedPosition = Offset(
            x = position.x.coerceIn(0f, maximumPosition.x),
            y = position.y.coerceIn(0f, maximumPosition.y)
        )
        if (clampedPosition != position) {
            position = clampedPosition
            scheduleRender()
        }
        return position
    }

    private fun scheduleRender() {
        if (!frameScheduled) {
            frameScheduled = true
            val scheduledGeneration = generation
            scheduleFrame frame@{
                synchronized(this) {
                if (scheduledGeneration != generation) return@frame
                frameScheduled = false
                renderPosition(position)
                }
            }
        }
    }

    @Synchronized fun cancel() {
        generation++
        frameScheduled = false
    }
}
