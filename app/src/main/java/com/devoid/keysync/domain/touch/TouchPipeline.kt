package com.devoid.keysync.domain.touch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import kotlinx.serialization.Serializable

/** One ordered stream. Only ADJACENT MOVE commands merge; edges are immutable barriers. */
class TouchPipeline(
    private val session: TouchSessionManager,
    private val moveWindowMillis: Long = 4,
    private val maxQueue: Int = 4096
) {
    private data class Entry(val command: TouchCommand?, val queuedNanos: Long,
                             val barrier: CompletableDeferred<Unit>? = null)
    private val lock = Any()
    private val queue = ArrayDeque<Entry>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var closed = false
    private var overflow = false
    private var coalesced = 0L
    private var highWater = 0
    private val queueTimes = ArrayDeque<Long>()
    private val processingTimes = ArrayDeque<Long>()
    private var snapshot = TouchDiagnostics()

    init {
        require(moveWindowMillis in 0..16 && maxQueue > 0)
        scope.launch {
            for (ignored in wake) {
                while (true) {
                    val head = synchronized(lock) { queue.peekFirst() } ?: break
                    if (isMove(head.command) && moveWindowMillis > 0) {
                        val remaining = moveWindowMillis - (System.nanoTime() - head.queuedNanos) / 1_000_000
                        // Edges/barriers flush immediately; timer never blocks the actor for a swipe.
                        val alone = synchronized(lock) { queue.size == 1 && !overflow }
                        if (alone && remaining > 0) {
                            withTimeoutOrNull(remaining) { wake.receive() }
                            continue
                        }
                    }
                    val entry = synchronized(lock) { queue.pollFirst() } ?: continue
                    val started = System.nanoTime()
                    val overloaded = synchronized(lock) { overflow.also { overflow = false } }
                    if (overloaded) session.recover()
                    try {
                        entry.command?.let(session::apply)
                    } catch (_: Exception) {
                        session.recover()
                    } finally {
                        val finished = System.nanoTime()
                        synchronized(lock) {
                            record(queueTimes, started - entry.queuedNanos)
                            record(processingTimes, finished - started)
                            snapshot = TouchDiagnostics(session.contacts.size, session.faulted,
                                session.failures, session.rejectedTransitions, session.rebases,
                                queue.size, highWater, coalesced)
                        }
                        entry.barrier?.complete(Unit)
                    }
                }
            }
        }
    }

    fun submit(command: TouchCommand) = synchronized(lock) {
        if (closed) return@synchronized
        val previous = queue.peekLast()
        val merged = merge(previous?.command, command)
        if (merged != null) {
            queue.removeLast()
            queue.addLast(previous!!.copy(command = merged))
            coalesced++
        } else {
            if (queue.size >= maxQueue) {
                // Never silently drop a release: fault the stream and require a session reset.
                overflow = true
                queue.forEach { it.barrier?.complete(Unit) }
                queue.clear()
            }
            queue.addLast(Entry(command, System.nanoTime()))
            highWater = maxOf(highWater, queue.size)
        }
        wake.trySend(Unit)
    }

    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (closed) { done.complete(Unit) }
            else { queue.addLast(Entry(null, System.nanoTime(), done)); wake.trySend(Unit) }
        }
        done.await()
    }

    fun diagnostics(): TouchDiagnostics = synchronized(lock) {
        snapshot.copy(queueDepth = queue.size, queueMillisP50P95P99 = percentiles(queueTimes),
            backendMillisP50P95P99 = session.backendPercentiles(), processingMillisP50P95P99 = percentiles(processingTimes))
    }

    suspend fun shutdown() {
        val done = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (closed) { done.complete(Unit) }
            else {
                closed = true
                queue.addLast(Entry(TouchCommand.Cancel, System.nanoTime(), done))
                wake.trySend(Unit)
            }
        }
        done.await()
        TouchDebugRegistry.detach(this)
        wake.close()
        scope.cancel()
    }

    private fun merge(a: TouchCommand?, b: TouchCommand): TouchCommand? = when {
        a is TouchCommand.Move && b is TouchCommand.Move -> TouchCommand.Move(a.points + b.points)
        a is TouchCommand.MoveBy && b is TouchCommand.MoveBy -> {
            val deltas = a.deltas.toMutableMap()
            b.deltas.forEach { (id, delta) -> deltas[id] = (deltas[id] ?: TouchPoint(0.0, 0.0)) + delta }
            TouchCommand.MoveBy(deltas)
        }
        else -> null
    }
    private fun isMove(command: TouchCommand?) = command is TouchCommand.Move || command is TouchCommand.MoveBy
    private fun record(samples: ArrayDeque<Long>, value: Long) {
        if (samples.size == 256) samples.removeFirst()
        samples.addLast(value)
    }
    private fun percentiles(samples: ArrayDeque<Long>): List<Double> {
        if (samples.isEmpty()) return listOf(0.0, 0.0, 0.0)
        val sorted = samples.sorted()
        return listOf(.5, .95, .99).map { p -> sorted[((sorted.size - 1) * p).toInt()] / 1_000_000.0 }
    }
}

@Serializable data class TouchDiagnostics(
    val activeContacts: Int = 0, val faulted: Boolean = false, val failures: Long = 0,
    val rejected: Long = 0, val rebases: Long = 0, val queueDepth: Int = 0,
    val highWater: Int = 0, val coalesced: Long = 0,
    val queueMillisP50P95P99: List<Double> = emptyList(),
    val backendMillisP50P95P99: List<Double> = emptyList(),
    val processingMillisP50P95P99: List<Double> = emptyList()
)
