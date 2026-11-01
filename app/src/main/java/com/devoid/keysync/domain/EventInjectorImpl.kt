package com.devoid.keysync.domain

import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import com.devoid.keysync.model.EventInjector
import com.devoid.keysync.model.EventManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

abstract class EventInjectorImpl(private val eventManager: EventManager = EventManagerImpl()) :
    EventInjector {
    private val animators = mutableMapOf<Int, ObjectRandomAnimator>()
    private val gestureGenerationLock = Any()
    private val gestureGenerations = mutableMapOf<Int, Long>()
    private val pointerMoveLock = Any()
    private val pointerMoveEpochs = mutableMapOf<Int, Long>()
    private val pendingRebaseEpochs = mutableMapOf<Int, Long>()
    private val pendingPointerMoves = mutableMapOf<PointerMoveKey, Offset>()
    private val scheduledPointerMoves = mutableSetOf<PointerMoveKey>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val taskQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    /** Conflated wake-up: many mouse samples become one pending frame. */
    private val moveWake = Channel<Unit>(Channel.CONFLATED)
    /** Returns true only when the selected backend accepted the event. */
    protected abstract fun inject(event: InputEvent): Boolean
    protected open fun cancelSystemTouchStream(): Boolean = false
    protected open fun onLocalInputReset() = Unit

    init {
        scope.launch {
            while (isActive) {
                // Keep edge events ahead of a pending MOVE frame. This is the
                // ordering boundary that prevents a click or CANCEL from
                // being delivered after stale camera motion.
                val queuedTask = taskQueue.tryReceive().getOrNull()
                if (queuedTask != null) {
                    runTask(queuedTask)
                    continue
                }
                try {
                    select<Unit> {
                        taskQueue.onReceive { task -> runTask(task) }
                        moveWake.onReceive { drainPendingMoves() }
                    }
                } catch (_: ClosedReceiveChannelException) {
                    break
                } catch (_: kotlinx.coroutines.CancellationException) {
                    break
                }
            }
        }
    }

    private fun enqueue(task: suspend () -> Unit) {
        if (taskQueue.trySend(task).isFailure) {
            Log.e(TAG, "Unable to enqueue input injection task")
        }
    }

    private suspend fun runTask(task: suspend () -> Unit) {
        try {
            task.invoke()
        } catch (error: Throwable) {
            // Android's Log implementation is unavailable in local JVM tests.
            runCatching { Log.e(TAG, "Input injection task failed", error) }
        }
    }

    internal suspend fun awaitIdle() {
        val signal = CompletableDeferred<Unit>()
        enqueue {
            // A control barrier also drains the conflated MOVE channel, so
            // tests and lifecycle callers observe a genuinely idle engine.
            drainPendingMoves()
            signal.complete(Unit)
        }
        signal.await()
    }

    internal fun close() {
        prepareForPointerReset()
        taskQueue.close()
        scope.cancel()
    }

    override fun injectGesture(pointerID: Int, o1: Offset, o2: Offset) {
        cancelGestureAnimator(pointerID)
        val generation = nextGestureGeneration(pointerID)
        startGestureAnimation(pointerID, o1, o2, generation)
    }

    private fun startGestureAnimation(
        pointerID: Int,
        o1: Offset,
        o2: Offset,
        generation: Long
    ) {
        if (!isCurrentGestureGeneration(pointerID, generation)) return
        val animator = ObjectRandomAnimator()
        animator.init(o1, o2, duration = 80)
        animator.addUpdateListener { animation ->
            val offset = animation.animatedValue as Offset
            enqueue {
                if (!isCurrentGestureGeneration(pointerID, generation)) return@enqueue
                if (!eventManager.updatePointer(pointerID, offset.x, offset.y)) {
                    return@enqueue
                }
                val event = eventManager.createMotionEvent(MotionEvent.ACTION_MOVE)
                event?.let {
                    if (!inject(it)) eventManager.clear()
                    it.recycle()
                }
            }
        }
        animator.doOnEnd {
            if (animators[pointerID] === animator) animators.remove(pointerID)
        }
        animator.doOnStart {//inject action down once at $o1 if not injected
            addEventDown(pointerID, o1.x, o1.y)
        }
        animators[pointerID] = animator
        animator.start()
    }

    override fun transFormGesture(pointerID: Int, position: Offset) {
        cancelGestureAnimator(pointerID)
        val generation = nextGestureGeneration(pointerID)
        enqueue {
            if (!isCurrentGestureGeneration(pointerID, generation)) return@enqueue
            eventManager.getPointerLocation(pointerID)?.let {
                withContext(Dispatchers.Main){
                    startGestureAnimation(pointerID, it, position, generation)
                }
            }
        }
    }

    override fun releaseGesture(pointerID: Int) {
        nextGestureGeneration(pointerID)
        cancelGestureAnimator(pointerID)
        releasePointer(pointerID)
    }

    override fun injectPointer(pointerID: Int, o1: Offset, o2: Offset) {
        discardPointerMoves(pointerID)
        val x = (minOf(o1.x, o2.x).toInt()..maxOf(o1.x, o2.x).toInt())
            .random()
            .toFloat()
        val y = (minOf(o1.y, o2.y).toInt()..maxOf(o1.y, o2.y).toInt())
            .random()
            .toFloat()
        addEventDown(pointerID, x, y)
    }

    override fun injectPointer(pointerID: Int, position: Offset) {
        discardPointerMoves(pointerID)
        addEventDown(pointerID, position.x, position.y)
    }

    override fun updatePointerPosition(pointerID: Int, position: Offset) {
        val (moveKey, shouldSchedule) = synchronized(pointerMoveLock) {
            val key = PointerMoveKey(pointerID, pointerMoveEpochs[pointerID] ?: 0L)
            pendingPointerMoves[key] =
                (pendingPointerMoves[key] ?: Offset.Zero) + position
            key to scheduledPointerMoves.add(key)
        }
        if (shouldSchedule) requestPointerMove(moveKey)
    }

    /**
     * Ends one relative gesture and starts it again at [position] without
     * discarding moves queued before this call. A new move epoch ensures that
     * later deltas cannot be merged into the pre-rebase move task.
     */
    override fun rebasePointer(pointerID: Int, position: Offset) {
        nextGestureGeneration(pointerID)
        cancelGestureAnimator(pointerID)
        val newEpoch = synchronized(pointerMoveLock) {
            val epoch = (pointerMoveEpochs[pointerID] ?: 0L) + 1L
            pendingRebaseEpochs[pointerID] = epoch
            epoch
        }
        enqueue {
            // Flush deltas queued before the rebase while the old pointer is
            // still alive. New deltas carry [newEpoch] and wait for the fresh
            // DOWN below.
            drainPendingMoves(beforeEpoch = newEpoch)
            synchronized(pointerMoveLock) {
                pointerMoveEpochs[pointerID] = newEpoch
                pendingRebaseEpochs.remove(pointerID)
            }
            releasePointerNow(pointerID)
            addEventDownNow(pointerID, position.x, position.y)
        }
    }

    /**
     * Injects one complete, short touch swipe as an indivisible queue task.
     * Real event-time separation is required because some games discard a
     * DOWN/MOVE/UP sequence whose frames all carry the same uptime timestamp.
     */
    override fun injectSwipe(pointerID: Int, start: Offset, end: Offset) {
        discardPointerMoves(pointerID)
        enqueue {
            if (eventManager.addPointer(pointerID, start.x, start.y) < 0) return@enqueue
            val downAction = eventManager.getPointerAction(pointerID, false)
            if (!injectAndRecycle(eventManager.createMotionEvent(downAction))) {
                eventManager.clear()
                return@enqueue
            }

            for (step in 1..SWIPE_MOVE_STEPS) {
                delay(SWIPE_FRAME_DELAY_MILLIS)
                val fraction = step.toFloat() / SWIPE_MOVE_STEPS
                val position = start + (end - start) * fraction
                if (eventManager.updatePointer(pointerID, position.x, position.y)) {
                    if (!injectAndRecycle(eventManager.createMotionEvent(MotionEvent.ACTION_MOVE))) {
                        eventManager.clear()
                        return@enqueue
                    }
                }
            }

            delay(SWIPE_FRAME_DELAY_MILLIS)
            val upAction = eventManager.getPointerAction(pointerID, true)
            injectAndRecycle(eventManager.createMotionEvent(upAction))
            eventManager.removePointer(pointerID)
        }
    }

    private fun injectAndRecycle(event: MotionEvent?): Boolean {
        if (event == null) return true
        return try {
            inject(event)
        } finally {
            event.recycle()
        }
    }


    private fun addEventDown(pointerID: Int, x: Float, y: Float) {
        enqueue {
            addEventDownNow(pointerID, x, y)
        }
    }

    private suspend fun addEventDownNow(pointerID: Int, x: Float, y: Float) {
        if (eventManager.addPointer(pointerID, x, y) < 0) return
        val action = eventManager.getPointerAction(pointerID, false)
        val event = eventManager.createMotionEvent(action)
        event?.let {
            if (!inject(it)) eventManager.clear()
            it.recycle()
        }
    }

    override fun releasePointer(pointerID: Int) {
        discardPointerMoves(pointerID)
        enqueue {
            releasePointerNow(pointerID)
        }
    }

    private suspend fun releasePointerNow(pointerID: Int) {
        val action = eventManager.getPointerAction(pointerID, true)
        if (action == -1) return
        val event = eventManager.createMotionEvent(action)
        event?.let {
            inject(it)
            it.recycle()
        }
        eventManager.removePointer(pointerID)
    }

    override fun clear() {
        prepareForPointerReset()
        enqueue {
            try {
                val event = eventManager.createMotionEvent(MotionEvent.ACTION_CANCEL)
                event?.let {
                    inject(it)
                    it.recycle()
                }
                eventManager.clear()
            } finally {
                onLocalInputReset()
            }
        }
    }

    override fun cancelSystemTouch() {
        prepareForPointerReset()
        enqueue {
            try {
                val cancelledBySystem = runCatching { cancelSystemTouchStream() }
                    .getOrDefault(false)
                if (!cancelledBySystem) {
                    val event = eventManager.createMotionEvent(MotionEvent.ACTION_CANCEL)
                    event?.let {
                        inject(it)
                        it.recycle()
                    }
                }
                eventManager.clear()
            } finally {
                onLocalInputReset()
            }
        }
    }

    override fun shutdown() {
        prepareForPointerReset()
        enqueue {
            try {
                val event = eventManager.createMotionEvent(MotionEvent.ACTION_CANCEL)
                event?.let {
                    inject(it)
                    it.recycle()
                }
                eventManager.clear()
            } finally {
                onLocalInputReset()
                taskQueue.close()
                scope.cancel()
            }
        }
    }

    override suspend fun injected(pointerID: Int): Boolean {
        return eventManager.getPointerAction(pointerID, false) != -1
    }

    private fun requestPointerMove(moveKey: PointerMoveKey) {
        // The key remains in [scheduledPointerMoves] until the frame drains;
        // a conflated signal is enough to wake the actor once for any number
        // of samples accumulated in the meantime.
        moveWake.trySend(Unit)
    }

    private suspend fun drainPendingMoves(beforeEpoch: Long? = null) {
        val pending = synchronized(pointerMoveLock) {
            val selected = pendingPointerMoves.filter { (key, _) ->
                beforeEpoch == null || key.epoch < beforeEpoch
            }
            selected.keys.forEach { key ->
                pendingPointerMoves.remove(key)
                scheduledPointerMoves.remove(key)
            }
            selected
        }
        if (pending.isEmpty()) return

        var changed = false
        val retry = LinkedHashMap<PointerMoveKey, Offset>()
        pending.forEach { (key, offset) ->
            val currentEpoch = synchronized(pointerMoveLock) {
                pointerMoveEpochs[key.pointerId] ?: 0L
            }
            val rebaseEpoch = synchronized(pointerMoveLock) {
                pendingRebaseEpochs[key.pointerId]
            }
            val eligible = when {
                beforeEpoch != null -> key.epoch < beforeEpoch
                rebaseEpoch != null -> key.epoch < rebaseEpoch
                else -> key.epoch == currentEpoch
            }
            if (!eligible || !eventManager.offsetPointer(key.pointerId, offset)) {
                // A pending rebase defers its newer samples until the fresh
                // DOWN. An edge command may also not have created the pointer
                // yet; retain both cases for the next frame.
                retry[key] = offset
            } else {
                changed = true
            }
        }
        if (changed) {
            val event = eventManager.createMotionEvent(MotionEvent.ACTION_MOVE)
            if (event != null) {
                if (!inject(event)) eventManager.clear()
                event.recycle()
            }
        }

        val hasMore = synchronized(pointerMoveLock) {
            retry.forEach { (key, offset) ->
                pendingPointerMoves[key] =
                    (pendingPointerMoves[key] ?: Offset.Zero) + offset
                scheduledPointerMoves.add(key)
            }
            pendingPointerMoves.isNotEmpty()
        }
        if (hasMore) moveWake.trySend(Unit)
    }

    private fun discardPointerMoves(pointerID: Int) {
        synchronized(pointerMoveLock) {
            advancePointerMoveEpochLocked(pointerID)
            pendingPointerMoves.keys.removeAll { it.pointerId == pointerID }
            scheduledPointerMoves.removeAll { it.pointerId == pointerID }
            pendingRebaseEpochs.remove(pointerID)
        }
    }

    private fun advancePointerMoveEpoch(pointerID: Int) {
        synchronized(pointerMoveLock) {
            advancePointerMoveEpochLocked(pointerID)
        }
    }

    private fun advancePointerMoveEpochLocked(pointerID: Int) {
        pointerMoveEpochs[pointerID] = (pointerMoveEpochs[pointerID] ?: 0L) + 1L
    }

    private fun discardAllPointerMoves() {
        synchronized(pointerMoveLock) {
            pointerMoveEpochs.keys.toList().forEach { pointerID ->
                pointerMoveEpochs[pointerID] = (pointerMoveEpochs[pointerID] ?: 0L) + 1L
            }
            pendingPointerMoves.clear()
            scheduledPointerMoves.clear()
            pendingRebaseEpochs.clear()
        }
    }

    private fun prepareForPointerReset() {
        invalidateAllGestureGenerations()
        discardAllPointerMoves()
        val runningAnimators = animators.values.toList()
        animators.clear()
        runningAnimators.forEach { it.cancel() }
    }

    private fun cancelGestureAnimator(pointerID: Int) {
        animators.remove(pointerID)?.cancel()
    }

    private fun nextGestureGeneration(pointerID: Int): Long =
        synchronized(gestureGenerationLock) {
            val generation = (gestureGenerations[pointerID] ?: 0L) + 1L
            gestureGenerations[pointerID] = generation
            generation
        }

    private fun isCurrentGestureGeneration(pointerID: Int, generation: Long): Boolean =
        synchronized(gestureGenerationLock) {
            gestureGenerations[pointerID] == generation
        }

    private fun invalidateAllGestureGenerations() {
        synchronized(gestureGenerationLock) {
            gestureGenerations.keys.toList().forEach { pointerID ->
                gestureGenerations[pointerID] = (gestureGenerations[pointerID] ?: 0L) + 1L
            }
        }
    }

    private companion object {
        const val TAG = "EventInjector"
        const val SWIPE_MOVE_STEPS = 4
        const val SWIPE_FRAME_DELAY_MILLIS = 12L
    }

    private data class PointerMoveKey(val pointerId: Int, val epoch: Long)

}
