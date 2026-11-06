package com.helios.app

/** One transition feedback, delayed by conversation but never by an old frame's validity. */
internal class PendingClearance {
    private var pending:SpeechRequest?=null
    private var deliveredId:Long?=null
    fun clear() {pending=null}
    fun offer(request:SpeechRequest) {
        val parts=request.key.split(':')
        val id=parts.getOrNull(2)?.toLongOrNull() ?: return
        if(id!=deliveredId && parts.getOrNull(3) in setOf("HEADING_CLEAR","AREA_CLEARED")) pending=request
    }
    fun take(now:Long,state:NavigationValidity,idle:Boolean):SpeechRequest? {
        val request=pending ?: return null
        val parts=request.key.split(':').toMutableList()
        val id=parts.getOrNull(2)?.toLongOrNull()
        if(parts.size!=7 || id==null) {clear();return null}
        // Transient missing data cannot authorize movement. Wait for a fresh measured proof.
        if(!state.available || now-state.timeMs !in 0..700) return null
        if(state.episodeId!=null || state.clearedId!=id) {clear();return null}
        if(state.forwardClear!=true || !idle) return null
        clear()
        deliveredId=id
        // Refresh the key against current measured clearance, not the original view.
        state.evidenceRevision?.let {parts[6]="h$it"}
        return request.copy(key=parts.joinToString(":"),createdMs=now,expiresMs=now+1_500,
            riskNavigating=null,riskDirection=null,riskSpace=null,riskMode=RiskSpeechMode.CLEAR,
            clearanceFeedback=true)
    }
}
