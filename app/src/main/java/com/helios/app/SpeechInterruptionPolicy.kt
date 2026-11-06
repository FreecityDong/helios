package com.helios.app

internal object SpeechInterruptionPolicy {
    fun identity(r:SpeechRequest):String {
        val p=r.key.split(':')
        return if(p.take(2)==listOf("risk","nav") && p.size>=3) "risk:nav:${p[2]}:${r.riskBand}" else r.key
    }
    fun sameHazard(a:SpeechRequest,b:SpeechRequest)=identity(a)==identity(b)
    fun freshUrgent(r:SpeechRequest)=r.priority==SpeechPriority.URGENT_SAFETY && !r.riskDetail &&
        r.riskMode !in setOf(RiskSpeechMode.REPEAT,RiskSpeechMode.DIRECTION_UPDATE,RiskSpeechMode.CLEAR)
    fun deferToVisualTask(r:SpeechRequest,busy:Boolean)=busy && r.key.startsWith("risk:") &&
        r.priority!=SpeechPriority.FALL && !freshUrgent(r)
    fun mayInterrupt(incoming:SpeechRequest,current:SpeechRequest?):Boolean {
        if(current==null) return true
        if(incoming.priority==SpeechPriority.FALL) return current.priority!=SpeechPriority.FALL
        if(current.priority==SpeechPriority.FALL) return false
        if(incoming.priority==SpeechPriority.FIND_SUCCESS) return current.priority.ordinal<incoming.priority.ordinal && current.priority!=SpeechPriority.URGENT_SAFETY
        if(current.priority==SpeechPriority.FIND_SUCCESS) return incoming.key.startsWith("risk:") && freshUrgent(incoming)
        if(!incoming.key.startsWith("risk:")) return incoming.priority.ordinal>current.priority.ordinal
        if(current.key.startsWith("risk:")) {
            if(incoming.riskDetail || incoming.riskMode in setOf(RiskSpeechMode.CLEAR,RiskSpeechMode.REPEAT,RiskSpeechMode.DIRECTION_UPDATE)) return false
            if(incoming.priority.ordinal>current.priority.ordinal) return true
            return freshUrgent(incoming) && !sameHazard(incoming,current) &&
                incoming.riskMeters?.let { n->current.riskMeters?.let {n<=it-.20f} }==true
        }
        // A whole user answer is protected from lower/repeated background warnings.
        return freshUrgent(incoming)
    }
}

internal class RiskDetailBuffer {
    private val latest=linkedMapOf<String,SpeechRequest>()
    fun offer(r:SpeechRequest) {
        val identity=SpeechInterruptionPolicy.identity(r)
        if(r.riskMode==RiskSpeechMode.CLEAR || r.key.split(':').getOrNull(3) in setOf("HEADING_CLEAR","AREA_CLEARED","LEFT_FORWARD_VIEW")) {
            val episode=r.key.split(':').take(3).joinToString(":")+":"
            latest.keys.removeAll {it.startsWith(episode)};return
        }
        val text=if(r.riskMode==RiskSpeechMode.DIRECTION_UPDATE) r.text else parts(r.text).second
        if(text.isNullOrBlank()) {latest.remove(identity);return}
        val p=r.key.split(':').toMutableList()
        if(p.size==7) p[5]=p[5]+"-detail"
        latest[identity]=r.copy(id=r.id+"-detail",key=p.joinToString(":"),text=text,riskDetail=true,riskMode=RiskSpeechMode.DIRECTION_UPDATE,
            riskNavigating=if(r.riskSpace!=null) null else r.riskNavigating)
        while(latest.size>16)latest.remove(latest.keys.first())
    }
    fun take(core:SpeechRequest,now:Long,valid:(SpeechRequest)->Boolean):SpeechRequest? {
        val value=latest.remove(SpeechInterruptionPolicy.identity(core)) ?: return null
        if(now-value.createdMs !in 0..8_000 || !valid(value)) return null
        // The live navigation validity check must succeed before creating a fresh supplement.
        return value.copy(createdMs=now,expiresMs=now+1_500)
    }
    fun clear() {latest.clear()}
    companion object {
        fun parts(text:String):Pair<String,String?> {
            val first=text.indexOf('。')
            return if(first>=0 && first<text.lastIndex) text.substring(0,first+1) to text.substring(first+1).takeIf {it.isNotBlank()}
                else text to null
        }
    }
}
