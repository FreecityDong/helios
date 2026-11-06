package com.helios.app

/** Ownership transfers on offer/take. Overwritten and late resources are closed immediately. */
class LatestResource<T : AutoCloseable> : AutoCloseable {
    private var value: T? = null
    private var closed = false
    var replaced: Long = 0
        private set
    @Synchronized fun offer(next: T) {
        if (closed) { next.close(); return }
        val old = value
        value = next
        if (old != null) { replaced++; old.close() }
    }
    @Synchronized fun take(): T? = value.also { value = null }
    @Synchronized fun size(): Int = if (value == null) 0 else 1
    @Synchronized override fun close() {
        closed = true
        val old = value
        value = null
        old?.close()
    }
}
