package com.devoid.keysync.domain.touch

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TouchPipelineTest {
    private val id = ContactId(42)
    private val point = TouchPoint(500.0, 200.0)

    @Test fun `high frequency samples merge only between ordered edge barriers`() = runBlocking {
        for (samples in listOf(125, 500, 1000)) {
            val backend = RecordingTouchBackend()
            val session = TouchSessionManager(backend, { 100L })
            val pipeline = TouchPipeline(session)
            pipeline.submit(TouchCommand.Down(id, point))
            repeat(samples) { pipeline.submit(TouchCommand.MoveBy(mapOf(id to TouchPoint(.1, -.1)))) }
            pipeline.submit(TouchCommand.Rebase(id, point))
            repeat(samples) { pipeline.submit(TouchCommand.MoveBy(mapOf(id to TouchPoint(.2, .2)))) }
            pipeline.awaitIdle()
            val frame = backend.frames.last()
            assertEquals(500.0 + samples * .2, frame.contacts.single().position.x, 1e-7)
            assertEquals(200.0 + samples * .2, frame.contacts.single().position.y, 1e-7)
            assertEquals(2, backend.frames.count { it.action == TouchAction.DOWN })
            assertEquals(1, backend.frames.count { it.action == TouchAction.UP })
            assertFalse(pipeline.diagnostics().faulted)
            pipeline.shutdown()
            assertTrue(session.contacts.isEmpty())
        }
    }

    @Test fun `queued old movement cannot leak into a released and recreated contact`() = runBlocking {
        val backend = RecordingTouchBackend()
        val session = TouchSessionManager(backend, { 100L })
        val pipeline = TouchPipeline(session)
        pipeline.submit(TouchCommand.Down(id, point))
        repeat(1000) { pipeline.submit(TouchCommand.MoveBy(mapOf(id to TouchPoint(1.0, 1.0)))) }
        pipeline.submit(TouchCommand.Up(id))
        pipeline.submit(TouchCommand.Down(id, point))
        pipeline.submit(TouchCommand.MoveBy(mapOf(id to TouchPoint(3.0, 4.0))))
        pipeline.awaitIdle()
        assertEquals(TouchPoint(503.0, 204.0), backend.frames.last().contacts.single().position)
        pipeline.shutdown()
    }

    @Test fun `queue overflow fails closed instead of silently losing an UP`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = object : TouchBackend {
            override fun submit(frame: TouchFrame): Boolean {
                if (frame.action == TouchAction.DOWN) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
                return true
            }
            override fun cancel() = true
        }
        val session = TouchSessionManager(backend, { 100L })
        val pipeline = TouchPipeline(session, maxQueue = 4)
        pipeline.submit(TouchCommand.Down(id, point))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(20) {
            pipeline.submit(TouchCommand.Up(id))
            pipeline.submit(TouchCommand.Down(id, point))
        }
        release.countDown()
        pipeline.awaitIdle()
        assertTrue(session.faulted)
        assertTrue(session.contacts.isEmpty())
        assertEquals(1L, session.failures)
        pipeline.submit(TouchCommand.Reset)
        pipeline.submit(TouchCommand.Down(id, point))
        pipeline.awaitIdle()
        assertFalse(session.faulted)
        pipeline.shutdown()
    }
}
