package com.helios.app

/** Current visible geometry; never an instruction or a record of a user turn. */
enum class SpatialHint { LEFT, RIGHT, BOTH, BLOCKED, UNKNOWN }

internal class SpatialHintStability {
    var current=SpatialHint.UNKNOWN; private set
    private var candidate=SpatialHint.UNKNOWN
    private var since=0L
    private var count=0
    fun reset() { current=SpatialHint.UNKNOWN;candidate=SpatialHint.UNKNOWN;since=0;count=0 }
    fun observe(value:SpatialHint,now:Long):SpatialHint {
        if(value!=candidate) {candidate=value;since=now;count=0}
        count++
        // Revoke invalid geometry immediately; confirm new geometry before describing it.
        if(current!=value) current=SpatialHint.UNKNOWN
        if(value==SpatialHint.UNKNOWN || count>=3 && now-since>=500) current=value
        return current
    }
    companion object {
        fun classify(left:SideTurnStatus,right:SideTurnStatus)=when {
            left==SideTurnStatus.POSSIBLE && right==SideTurnStatus.POSSIBLE -> SpatialHint.BOTH
            left==SideTurnStatus.POSSIBLE -> SpatialHint.LEFT
            right==SideTurnStatus.POSSIBLE -> SpatialHint.RIGHT
            left==SideTurnStatus.BLOCKED && right==SideTurnStatus.BLOCKED -> SpatialHint.BLOCKED
            else -> SpatialHint.UNKNOWN
        }
    }
}
