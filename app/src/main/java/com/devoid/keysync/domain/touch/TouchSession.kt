package com.devoid.keysync.domain.touch

import java.util.ArrayDeque

/** Logical ownership, never an Android pointer index or a Linux slot. */
@JvmInline
value class ContactId(val value: Int)

data class TouchPoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
    operator fun plus(other: TouchPoint) = TouchPoint(x + other.x, y + other.y)
    operator fun minus(other: TouchPoint) = TouchPoint(x - other.x, y - other.y)
    operator fun times(scale: Double) = TouchPoint(x * scale, y * scale)
}

data class TouchContact(val owner: ContactId, val pointerId: Int, val position: TouchPoint)
enum class TouchAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }
data class TouchFrame(
    val action: TouchAction,
    val actionIndex: Int,
    val contacts: List<TouchContact>,
    val downTime: Long,
    val eventTime: Long,
    val displayId: Int
)

interface TouchBackend {
    /** Accepted by the injection API, NOT measured game delivery. No replay on uncertainty. */
    fun submit(frame: TouchFrame): Boolean
    fun cancel(): Boolean
    fun key(key: SimulatedKey, pressed: Boolean, downTime: Long, eventTime: Long): Boolean = false
}

data class SimulatedKey(val code: Int, val scanCode: Int)

sealed interface TouchCommand {
    data class Down(val id: ContactId, val point: TouchPoint) : TouchCommand
    data class Move(val points: Map<ContactId, TouchPoint>) : TouchCommand
    data class MoveBy(val deltas: Map<ContactId, TouchPoint>) : TouchCommand
    data class Up(val id: ContactId) : TouchCommand
    data class Rebase(val id: ContactId, val anchor: TouchPoint) : TouchCommand
    data class Key(val key: SimulatedKey, val pressed: Boolean) : TouchCommand
    data object ReleaseAll : TouchCommand
    data object Cancel : TouchCommand
    data object Reset : TouchCommand
}

/** Actor-owned state: plan an immutable next frame, inject, then commit. */
class TouchSessionManager(
    private val backend: TouchBackend,
    private val nowMillis: () -> Long,
    private val displayId: () -> Int = { 0 },
    private val onFailure: () -> Unit = {},
    private val onReset: () -> Unit = {}
) {
    var contacts: List<TouchContact> = emptyList()
        private set
    var faulted = false
        private set
    var rejectedTransitions = 0L
        private set
    var failures = 0L
        private set
    var rebases = 0L
        private set
    private var downTime = 0L
    private var lastEventTime = 0L
    private val keys = mutableMapOf<SimulatedKey, Long>()
    private val injectionTimes = ArrayDeque<Long>()
    fun backendPercentiles(): List<Double> = synchronized(injectionTimes) {
        val sorted = injectionTimes.sorted()
        if (sorted.isEmpty()) listOf(0.0, 0.0, 0.0)
        else listOf(.5, .95, .99).map { sorted[((sorted.size - 1) * it).toInt()] / 1_000_000.0 }
    }

    fun apply(command: TouchCommand) {
        if (command == TouchCommand.Reset || command == TouchCommand.Cancel) {
            releaseKeys()
            val accepted = if (contacts.isEmpty()) runCatching { backend.cancel() }.getOrDefault(false)
                else send(TouchAction.CANCEL, 0, contacts, emptyList())
            if (!accepted) runCatching { backend.cancel() }
            contacts = emptyList()
            downTime = 0L
            // A reset is an explicit session boundary, not an implicit retry of failed MOVE.
            faulted = false
            onReset()
            return
        }
        if (faulted) return
        when (command) {
            TouchCommand.ReleaseAll -> {
                releaseKeys()
                while (contacts.isNotEmpty()) up(contacts.last().owner)
            }
            is TouchCommand.Down -> down(command.id, command.point)
            is TouchCommand.Up -> up(command.id)
            is TouchCommand.Move -> move(command.points)
            is TouchCommand.MoveBy -> move(command.deltas.mapNotNull { (id, delta) ->
                contacts.firstOrNull { it.owner == id }?.let { id to (it.position + delta) }
            }.toMap())
            is TouchCommand.Key -> {
                val time = maxOf(nowMillis(), lastEventTime)
                val keyDownTime = keys[command.key] ?: time
                if (command.pressed) keys[command.key] = keyDownTime
                if (backend.key(command.key, command.pressed, keyDownTime, time)) {
                    if (!command.pressed) keys.remove(command.key)
                    lastEventTime = time
                } else recover()
            }
            is TouchCommand.Rebase -> {
                if (contacts.none { it.owner == command.id }) { reject(); return }
                up(command.id)
                if (!faulted) { down(command.id, command.anchor); rebases++ }
            }
            else -> Unit
        }
    }

    private fun down(id: ContactId, point: TouchPoint) {
        if (contacts.any { it.owner == id } || contacts.size >= MAX_CONTACTS) { reject(); return }
        val pointerId = (0 until MAX_CONTACTS).first { candidate -> contacts.none { it.pointerId == candidate } }
        val next = contacts + TouchContact(id, pointerId, point)
        val proposedDownTime = if (contacts.isEmpty()) nowMillis() else downTime
        send(if (contacts.isEmpty()) TouchAction.DOWN else TouchAction.POINTER_DOWN,
            next.lastIndex, next, next, proposedDownTime)
    }

    private fun up(id: ContactId) {
        val index = contacts.indexOfFirst { it.owner == id }
        if (index < 0) { reject(); return }
        // UP frame contains the departing finger, commit removes it afterwards.
        send(if (contacts.size == 1) TouchAction.UP else TouchAction.POINTER_UP,
            index, contacts, contacts.filter { it.owner != id })
    }

    private fun move(points: Map<ContactId, TouchPoint>) {
        if (points.isEmpty()) return
        if (points.keys.any { id -> contacts.none { it.owner == id } }) { reject(); return }
        val next = contacts.map { it.copy(position = points[it.owner] ?: it.position) }
        if (next == contacts) return
        send(TouchAction.MOVE, 0, next, next)
    }

    private fun send(action: TouchAction, index: Int, frameContacts: List<TouchContact>,
                     next: List<TouchContact>, proposedDownTime: Long = downTime): Boolean {
        val time = maxOf(nowMillis(), lastEventTime)
        val started = System.nanoTime()
        val accepted = runCatching { backend.submit(TouchFrame(action, index, frameContacts,
            proposedDownTime, time, displayId())) }.getOrDefault(false)
        synchronized(injectionTimes) {
            if (injectionTimes.size == 256) injectionTimes.removeFirst()
            injectionTimes.addLast(System.nanoTime() - started)
        }
        lastEventTime = time
        if (accepted) {
            contacts = next
            downTime = if (next.isEmpty()) 0L else proposedDownTime
        } else {
            recover()
        }
        return accepted
    }

    fun recover() {
        releaseKeys()
        runCatching { backend.cancel() }
        contacts = emptyList()
        downTime = 0L
        if (!faulted) { faulted = true; failures++; onFailure() }
    }

    private fun releaseKeys() {
        val time = maxOf(nowMillis(), lastEventTime)
        keys.forEach { (key, pressedAt) -> backend.key(key, false, pressedAt, time) }
        keys.clear()
    }

    private fun reject() { rejectedTransitions++ }
    companion object { const val MAX_CONTACTS = 10 }
}
