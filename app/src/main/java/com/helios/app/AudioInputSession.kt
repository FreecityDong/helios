package com.helios.app

/** Main-thread audio ownership with a generation token for late recorder callbacks. */
class AudioInputSession {
    var running = false
        private set
    private var generation = 0L
    fun begin(): Long? {
        if (running) return null
        running = true
        return ++generation
    }
    fun current(token: Long): Boolean = running && generation == token
    fun end() { running = false; generation++ }
}
