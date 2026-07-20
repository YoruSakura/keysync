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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class EventInjectorImpl(private val eventManager: EventManager = EventManagerImpl()) :
    EventInjector {
    private val animators = mutableMapOf<Int, ObjectRandomAnimator>()
    private val gestureGenerationLock = Any()
    private val gestureGenerations = mutableMapOf<Int, Long>()
    private val pointerMoveLock = Any()
    private val pointerMoveEpochs = mutableMapOf<Int, Long>()
    private val pendingPointerMoves = mutableMapOf<PointerMoveKey, Offset>()
    private val scheduledPointerMoves = mutableSetOf<PointerMoveKey>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val taskQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    abstract fun inject(event: InputEvent)

    init {
        scope.launch {
            for (task in taskQueue) {
                try {
                    task.invoke()
                } catch (error: Throwable) {
                    // Android's Log implementation is unavailable in local JVM tests.
                    runCatching { Log.e(TAG, "Input injection task failed", error) }
                }
            }
        }
    }

    private fun enqueue(task: suspend () -> Unit) {
        if (taskQueue.trySend(task).isFailure) {
            Log.e(TAG, "Unable to enqueue input injection task")
        }
    }

    internal suspend fun awaitIdle() {
        val signal = CompletableDeferred<Unit>()
        enqueue { signal.complete(Unit) }
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
                    inject(it)
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
        val x = (o1.x.toInt()..o2.x.toInt()).random().toFloat()
        val y = (o1.y.toInt()..o2.y.toInt()).random().toFloat()
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
        if (shouldSchedule) enqueuePointerMove(moveKey)
    }


    private fun addEventDown(pointerID: Int, x: Float, y: Float) {
        enqueue {
            if (eventManager.addPointer(pointerID, x, y) < 0) return@enqueue
            val action = eventManager.getPointerAction(pointerID, false)
            val event = eventManager.createMotionEvent(action)
            event?.let {
                inject(it)
                it.recycle()
            }
        }
    }

    override fun releasePointer(pointerID: Int) {
        discardPointerMoves(pointerID)
        enqueue {
            val action = eventManager.getPointerAction(pointerID, true)
            if (action == -1) return@enqueue
            val event = eventManager.createMotionEvent(action)
            event?.let {
                inject(it)
                it.recycle()
            }
            eventManager.removePointer(pointerID)
        }
    }

    override fun clear() {
        prepareForPointerReset()
        enqueue {
            val event = eventManager.createMotionEvent(MotionEvent.ACTION_CANCEL)
            event?.let {
                inject(it)
                it.recycle()
            }
            eventManager.clear()
        }
    }

    override fun shutdown() {
        prepareForPointerReset()
        enqueue {
            val event = eventManager.createMotionEvent(MotionEvent.ACTION_CANCEL)
            event?.let {
                inject(it)
                it.recycle()
            }
            eventManager.clear()
            taskQueue.close()
            scope.cancel()
        }
    }

    override suspend fun injected(pointerID: Int): Boolean {
        return eventManager.getPointerAction(pointerID, false) != -1
    }

    private fun enqueuePointerMove(moveKey: PointerMoveKey) {
        enqueue {
            val offset = synchronized(pointerMoveLock) {
                pendingPointerMoves.remove(moveKey)
            }
            try {
                if (offset != null && eventManager.offsetPointer(moveKey.pointerId, offset)) {
                    val event = eventManager.createMotionEvent(MotionEvent.ACTION_MOVE)
                    event?.let {
                        inject(it)
                        it.recycle()
                    }
                }
            } finally {
                val shouldReschedule = synchronized(pointerMoveLock) {
                    if (pendingPointerMoves.containsKey(moveKey)) {
                        true
                    } else {
                        scheduledPointerMoves.remove(moveKey)
                        false
                    }
                }
                if (shouldReschedule) enqueuePointerMove(moveKey)
            }
        }
    }

    private fun discardPointerMoves(pointerID: Int) {
        synchronized(pointerMoveLock) {
            pointerMoveEpochs[pointerID] = (pointerMoveEpochs[pointerID] ?: 0L) + 1L
            pendingPointerMoves.keys.removeAll { it.pointerId == pointerID }
            scheduledPointerMoves.removeAll { it.pointerId == pointerID }
        }
    }

    private fun discardAllPointerMoves() {
        synchronized(pointerMoveLock) {
            pointerMoveEpochs.keys.toList().forEach { pointerID ->
                pointerMoveEpochs[pointerID] = (pointerMoveEpochs[pointerID] ?: 0L) + 1L
            }
            pendingPointerMoves.clear()
            scheduledPointerMoves.clear()
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
    }

    private data class PointerMoveKey(val pointerId: Int, val epoch: Long)

}
