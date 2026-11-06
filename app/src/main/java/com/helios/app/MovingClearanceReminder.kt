package com.helios.app

internal object ClearanceProof {
    fun ready(now:Long,state:NavigationValidity)=state.available && now-state.timeMs in 0..700 &&
        state.episodeId==null && state.forwardClear==true
}

/** Positive walking feedback uses measured space and actual audio starts, never a timer alone. */
internal class MovingClearanceReminder {
    private var spokenMs:Long?=null
    private var attemptedMs:Long?=null
    fun reset() {spokenMs=null;attemptedMs=null}
    fun spoken(now:Long) {spokenMs=now}
    fun take(now:Long,state:NavigationValidity,moving:Boolean,idle:Boolean,intervalMs:Long):Boolean {
        if(!moving || !idle || !ClearanceProof.ready(now,state)) return false
        if(spokenMs?.let {now-it in 0 until intervalMs}==true ||
            attemptedMs?.let {now-it in 0 until 1_000L}==true) return false
        attemptedMs=now
        return true
    }
}
