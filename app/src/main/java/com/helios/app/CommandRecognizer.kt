package com.helios.app

interface CommandRecognizer {
    val isAvailable: Boolean
    fun start(windowMs: Long, listener: Listener)
    fun stop()

    interface Listener {
        fun onPartial(text: String) {}
        fun onReady()
        fun onResult(candidates: List<String>)
        fun onTimeout()
        fun onError(code: Int, message: String)
    }
}
