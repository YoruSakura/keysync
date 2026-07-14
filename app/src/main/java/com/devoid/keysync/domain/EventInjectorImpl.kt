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
        animators.values.toList().forEach { it.cancel() }
        animators.clear()
        taskQueue.close()
        scope.cancel()
    }

    override fun injectGesture(pointerID: Int, o1: Offset, o2: Offset) {

        animators[pointerID]?.let {
            it.cancel()
            animators.remove(pointerID)
        }
        val animator = ObjectRandomAnimator()
        animator.init(o1, o2, duration = 80)
        animator.addUpdateListener { animation ->
            val offset = animation.animatedValue as Offset
            enqueue {
                if (!animator.isRunning) return@enqueue
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
            animators.remove(pointerID)
        }
        animator.doOnStart {//inject action down once at $o1 if not injected
            addEventDown(pointerID, o1.x, o1.y)
        }
        animator.start()
        animators[pointerID] = animator
    }

    override fun transFormGesture(pointerID: Int, position: Offset) {
        enqueue {
            eventManager.getPointerLocation(pointerID)?.let {
                withContext(Dispatchers.Main){
                    injectGesture(pointerID,it,position)
                }
            }
        }
    }

    override fun releaseGesture(pointerID: Int) {
        animators[pointerID]?.let {
            it.cancel()
            animators.remove(pointerID)
        }
        releasePointer(pointerID)
    }

    override fun injectPointer(pointerID: Int, o1: Offset, o2: Offset) {
        val x = (o1.x.toInt()..o2.x.toInt()).random().toFloat()
        val y = (o1.y.toInt()..o2.y.toInt()).random().toFloat()
        addEventDown(pointerID, x, y)
    }

    override fun injectPointer(pointerID: Int, position: Offset) {
        addEventDown(pointerID, position.x, position.y)
    }

    override fun updatePointerPosition(pointerID: Int, position: Offset) {
        enqueue {
            if (!eventManager.offsetPointer(pointerID, position)) return@enqueue
            val event = eventManager.createMotionEvent(MotionEvent.ACTION_MOVE)
            event?.let {
                inject(it)
                it.recycle()
            }
        }
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
        animators.values.toList().forEach { it.cancel() }
        animators.clear()
        enqueue {
            val event = eventManager.createMotionEvent(MotionEvent.ACTION_CANCEL)
            event?.let {
                inject(it)
                it.recycle()
            }
            eventManager.clear()
        }
    }

    override suspend fun injected(pointerID: Int): Boolean {
        return eventManager.getPointerAction(pointerID, false) != -1
    }

    private companion object {
        const val TAG = "EventInjector"
    }

}
