package com.devoid.keysync.domain.touch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Accumulates wheel motion while a finger gesture is running, instead of replacing it with taps. */
class WheelGestureController(
    private val scope: CoroutineScope,
    private val emit: (TouchCommand) -> Unit
) {
    private sealed interface Gesture {
        val pixelsPerNotch: Double
        data class Scroll(
            val anchor: TouchPoint,
            val bounds: TouchRect,
            override val pixelsPerNotch: Double,
            var position: TouchPoint = anchor
        ) : Gesture
        data class Zoom(
            val center: TouchPoint,
            val initialRadius: Double,
            val minRadius: Double,
            val maxRadius: Double,
            override val pixelsPerNotch: Double,
            var radius: Double = initialRadius
        ) : Gesture
    }

    private var gesture: Gesture? = null
    private var pending = 0.0
    private var job: Job? = null
    val isZooming: Boolean
        @Synchronized get() = gesture is Gesture.Zoom

    @Synchronized
    fun scroll(delta: Float, cursor: TouchPoint, viewport: TouchViewport) {
        if (gesture is Gesture.Zoom) cancel()
        var current = gesture as? Gesture.Scroll
        if (current == null) {
            val anchor = viewport.bounds.clamp(cursor)
            val pixelsPerNotch = (viewport.height * .12).coerceIn(96.0, 320.0)
            // Desktop wheel-up reveals earlier content: the finger drags the content down.
            val end = viewport.bounds.clamp(anchor + TouchPoint(0.0, delta * pixelsPerNotch))
            if (end == anchor) return // No room for a drag: do not inject a click at the screen edge.
            current = Gesture.Scroll(anchor, viewport.bounds, pixelsPerNotch)
            gesture = current
            emit(TouchCommand.Down(SCROLL, anchor))
        }
        pending += delta * current.pixelsPerNotch
        start()
    }

    @Synchronized
    fun zoom(delta: Float, viewport: TouchViewport) {
        if (gesture is Gesture.Scroll) cancel()
        var current = gesture as? Gesture.Zoom
        if (current == null) {
            val shortSide = minOf(viewport.width, viewport.height)
            current = Gesture.Zoom(TouchPoint(viewport.width / 2, viewport.height / 2),
                shortSide * .15, shortSide * .04, shortSide * .4, shortSide * .04)
            gesture = current
            pressZoom(current)
        }
        pending += delta * current.pixelsPerNotch
        start()
    }

    private fun start() {
        if (job != null) return
        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            var idleFrames = 0
            while (true) {
                delay(16) // KeySync frame cadence, not an inferred MuMu timing unit.
                synchronized(this@WheelGestureController) {
                    if (job !== coroutineContext[Job]) return@launch
                    val current = gesture!!
                    if (abs(pending) < .001) {
                        if (++idleFrames == 4) { finish(); return@launch }
                    } else {
                        idleFrames = 0
                        advance(current)
                    }
                }
            }
        }
        job = nextJob
        nextJob.start()
    }

    private fun advance(current: Gesture) {
        val step = pending.coerceIn(-current.pixelsPerNotch / 4, current.pixelsPerNotch / 4)
        when (current) {
            is Gesture.Scroll -> {
                var next = current.bounds.clamp(current.position + TouchPoint(0.0, step))
                if (next == current.position) {
                    next = current.bounds.clamp(current.anchor + TouchPoint(0.0, step))
                    if (next == current.anchor) { pending = 0.0; return }
                    emit(TouchCommand.Up(SCROLL))
                    emit(TouchCommand.Down(SCROLL, current.anchor))
                    current.position = current.anchor
                }
                pending -= next.y - current.position.y
                current.position = next
                emit(TouchCommand.Move(mapOf(SCROLL to next)))
            }
            is Gesture.Zoom -> {
                var next = (current.radius + step).coerceIn(current.minRadius, current.maxRadius)
                if (next == current.radius) {
                    releaseZoom()
                    current.radius = current.initialRadius
                    pressZoom(current)
                    next = (current.radius + step).coerceIn(current.minRadius, current.maxRadius)
                }
                pending -= next - current.radius
                current.radius = next
                emit(TouchCommand.Move(zoomPoints(current)))
            }
        }
    }

    private fun zoomPoints(zoom: Gesture.Zoom) = mapOf(
        ZOOM_LEFT to (zoom.center - TouchPoint(zoom.radius, 0.0)),
        ZOOM_RIGHT to (zoom.center + TouchPoint(zoom.radius, 0.0))
    )
    private fun pressZoom(zoom: Gesture.Zoom) {
        zoomPoints(zoom).forEach { (id, point) -> emit(TouchCommand.Down(id, point)) }
    }
    private fun releaseZoom() {
        emit(TouchCommand.Up(ZOOM_RIGHT))
        emit(TouchCommand.Up(ZOOM_LEFT))
    }
    private fun finish() {
        when (gesture) {
            is Gesture.Scroll -> emit(TouchCommand.Up(SCROLL))
            is Gesture.Zoom -> releaseZoom()
            null -> Unit
        }
        gesture = null
        pending = 0.0
        job = null
    }

    @Synchronized
    fun cancel() { job?.cancel(); finish() }

    internal suspend fun awaitIdle() {
        val pendingJob = synchronized(this) { job }
        pendingJob?.join()
    }

    private companion object {
        val SCROLL = ContactId(-20_004)
        val ZOOM_LEFT = ContactId(-20_005)
        val ZOOM_RIGHT = ContactId(-20_006)
    }
}
