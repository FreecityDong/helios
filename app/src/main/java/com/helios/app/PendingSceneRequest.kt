package com.helios.app

internal data class DeferredSceneRequest(val detailed:Boolean,val question:String?)

/** Speech is accepted while moving; only the image request waits for current STILL. */
internal class PendingSceneRequest {
    private var request:DeferredSceneRequest?=null
    val waiting get()=request!=null
    fun offer(value:DeferredSceneRequest) {request=value}
    fun clear():Boolean {val had=request!=null;request=null;return had}
    fun take(motion:MotionSnapshot,canStart:Boolean):DeferredSceneRequest? {
        if(!canStart || !stationary(motion)) return null
        return request.also {request=null}
    }
    companion object {
        fun stationary(motion:MotionSnapshot)=motion.state==MotionState.STILL && !motion.stale && motion.calibrated
    }
}
