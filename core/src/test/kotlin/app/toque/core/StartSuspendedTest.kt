package app.toque.core

import app.toque.core.machine.MachineLoop
import app.toque.core.machine.MonotonicClock
import app.toque.core.machine.SimBoard
import app.toque.core.machine.SimTransport
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartSuspendedTest {
    private fun waitFor(ms: Long, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok() && System.currentTimeMillis() < end) Thread.sleep(25)
    }

    @Test fun aLoopStartedSuspendedKeepsThePortFreeUntilResumed() {
        val opens = AtomicInteger()
        val board = SimBoard(MonotonicClock)
        val loop = MachineLoop(transportFactory = { opens.incrementAndGet(); SimTransport(board) }, startSuspended = true)
        try {
            loop.start()
            Thread.sleep(500)
            assertEquals("la liaison ne doit pas s'ouvrir avant le choix", 0, opens.get())
            assertTrue(loop.suspended)
            loop.resumePort()
            waitFor(3_000) { opens.get() > 0 }
            assertEquals(1, opens.get())
            assertFalse(loop.suspended)
        } finally {
            loop.shutdown()
        }
    }

    @Test fun aNormalLoopOpensTheLinkRightAway() {
        val opens = AtomicInteger()
        val board = SimBoard(MonotonicClock)
        val loop = MachineLoop(transportFactory = { opens.incrementAndGet(); SimTransport(board) })
        try {
            loop.start()
            waitFor(3_000) { opens.get() > 0 }
            assertEquals(1, opens.get())
            assertFalse(loop.suspended)
        } finally {
            loop.shutdown()
        }
    }
}
