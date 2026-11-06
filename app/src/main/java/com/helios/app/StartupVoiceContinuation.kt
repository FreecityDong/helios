package com.helios.app

/** A user can skip a startup sentence without losing its initialization continuation. */
internal class StartupVoiceContinuation {
    private var pending:Pair<Long,()->Unit>?=null
    fun hold(generation:Long,action:()->Unit) {pending=generation to action}
    fun take(generation:Long,eligible:Boolean): (() -> Unit)? {
        val value=pending ?: return null
        if(value.first!=generation) {pending=null;return null}
        if(!eligible) return null
        pending=null
        return value.second
    }
}
