package com.devoid.keysync.domain.touch

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.devoid.keysync.model.EventInjector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Compatibility adapter: existing mappings now share the new session and pipeline. */
class TouchCoreEventInjector(
    backend: TouchBackend,
    onFailure: () -> Unit = {},
    onReset: () -> Unit = {},
    displayId: () -> Int = { 0 },
    moveWindowMillis: Long = 4,
    nowMillis: () -> Long = SystemClock::uptimeMillis
) : EventInjector {
    private val session = TouchSessionManager(backend, nowMillis, displayId, onFailure, onReset)
    val pipeline = TouchPipeline(session, moveWindowMillis)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<Int, Job>()
    private val lock = Any()
    private var closed = false
    init { TouchDebugRegistry.attach(pipeline) }

    override fun injectPointer(pointerID: Int, position: Offset) = synchronized(lock) {
        if (!closed) pipeline.submit(TouchCommand.Down(ContactId(pointerID), position.touchPoint()))
    }
    override fun injectPointer(pointerID: Int, o1: Offset, o2: Offset) {
        // Stable centre, no random jitter that can land on adjacent UI controls.
        injectPointer(pointerID, o1 + (o2 - o1) * .5f)
    }
    override fun updatePointerPosition(pointerID: Int, position: Offset) = synchronized(lock) {
        if (!closed) pipeline.submit(TouchCommand.MoveBy(mapOf(ContactId(pointerID) to position.touchPoint())))
    }
    fun moveTo(pointerID: Int, point: TouchPoint) = synchronized(lock) {
        if (!closed) pipeline.submit(TouchCommand.Move(mapOf(ContactId(pointerID) to point)))
    }
    override fun rebasePointer(pointerID: Int, position: Offset) = synchronized(lock) {
        cancelJob(pointerID)
        if (!closed) pipeline.submit(TouchCommand.Rebase(ContactId(pointerID), position.touchPoint()))
    }
    override fun injectGesture(pointerID: Int, o1: Offset, o2: Offset) = synchronized(lock) {
        cancelJob(pointerID)
        if (!closed) {
            pipeline.submit(TouchCommand.Down(ContactId(pointerID), o1.touchPoint()))
            pipeline.submit(TouchCommand.Move(mapOf(ContactId(pointerID) to o2.touchPoint())))
        }
    }
    override fun transFormGesture(pointerID: Int, position: Offset) = synchronized(lock) {
        cancelJob(pointerID)
        if (!closed) pipeline.submit(TouchCommand.Move(mapOf(ContactId(pointerID) to position.touchPoint())))
    }
    override fun releaseGesture(pointerID: Int) = releasePointer(pointerID)
    override fun releasePointer(pointerID: Int) = synchronized(lock) {
        cancelJob(pointerID)
        if (!closed) pipeline.submit(TouchCommand.Up(ContactId(pointerID)))
    }
    override fun injectSwipe(pointerID: Int, start: Offset, end: Offset) = synchronized(lock) {
        val replacing = jobs.containsKey(pointerID)
        cancelJob(pointerID)
        if (closed) return@synchronized
        val id = ContactId(pointerID)
        if (replacing) pipeline.submit(TouchCommand.Up(id))
        pipeline.submit(TouchCommand.Down(id, start.touchPoint()))
        // Delay belongs to gesture scheduling, never inside the touch actor.
        val job = scope.launch {
            repeat(4) { step ->
                delay(12)
                synchronized(lock) {
                    if (jobs[pointerID] !== coroutineContext[Job] || closed) return@launch
                    pipeline.submit(TouchCommand.Move(mapOf(id to (start + (end - start) * ((step + 1) / 4f)).touchPoint())))
                }
            }
            delay(12)
            synchronized(lock) {
                if (jobs[pointerID] === coroutineContext[Job] && !closed) {
                    pipeline.submit(TouchCommand.Up(id)); jobs.remove(pointerID)
                }
            }
        }
        jobs[pointerID] = job
    }
    override fun clear() = reset(TouchCommand.Cancel)
    override fun cancelSystemTouch() = reset(TouchCommand.Reset)
    private fun reset(command: TouchCommand) = synchronized(lock) {
        jobs.values.forEach(Job::cancel); jobs.clear()
        if (!closed) pipeline.submit(command)
    }
    override fun shutdown() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        jobs.values.forEach(Job::cancel); jobs.clear()
        scope.launch { pipeline.shutdown(); scope.cancel() }
    }
    override suspend fun injected(pointerID: Int): Boolean {
        pipeline.awaitIdle()
        return session.contacts.any { it.owner == ContactId(pointerID) }
    }
    fun logDiagnostics() {
        runCatching { Log.i("KeySyncTouchCore", pipeline.diagnostics().toString()) }
    }
    internal suspend fun awaitScheduledGestures() {
        val pending = synchronized(lock) { jobs.values.toList() }
        pending.forEach { it.join() }
        pipeline.awaitIdle()
    }
    private fun cancelJob(id: Int) { jobs.remove(id)?.cancel() }
}

fun Offset.touchPoint() = TouchPoint(x.toDouble(), y.toDouble())
fun TouchPoint.offset() = Offset(x.toFloat(), y.toFloat())
