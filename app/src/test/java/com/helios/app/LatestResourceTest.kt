package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class LatestResourceTest {
    private class Resource : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }
    @Test fun overwrittenTakenAndLateResourcesHaveOneOwner() {
        val slot = LatestResource<Resource>()
        val first = Resource(); val second = Resource(); val late = Resource()
        slot.offer(first); slot.offer(second)
        assertEquals(1, first.closes)
        val taken = slot.take()!!
        slot.close(); slot.close()
        assertEquals(0, taken.closes)
        taken.close()
        slot.offer(late)
        assertEquals(1, second.closes)
        assertEquals(1, late.closes)
        assertEquals(0, slot.size())
    }
    @Test fun stopClosesPendingFrameAndProcessingFailureClosesTakenFrame() {
        val slot = LatestResource<Resource>()
        val pending = Resource(); slot.offer(pending); slot.close()
        assertEquals(1, pending.closes)
        val processing = Resource()
        runCatching { processing.use { error("decode failed") } }
        assertEquals(1, processing.closes)
    }
    @Test fun concurrentProducerConsumerAndStopReleaseEveryResourceExactlyOnce() {
        val slot = LatestResource<Resource>()
        val resources = List(2000) { Resource() }
        val producer = Thread { resources.forEach { slot.offer(it) } }
        val consumer = Thread { repeat(3000) { slot.take()?.close() } }
        producer.start(); consumer.start()
        slot.close()
        producer.join(); consumer.join()
        assertTrue(resources.all { it.closes == 1 })
        assertEquals(0, slot.size())
    }
}
