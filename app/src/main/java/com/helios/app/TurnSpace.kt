package com.helios.app

import kotlin.math.abs
import kotlin.math.atan

enum class TurnState { MONITOR, BLOCKED, TURNING }
enum class SideTurnStatus { POSSIBLE, BLOCKED, UNKNOWN }
data class SideTurnEvidence(val status: SideTurnStatus, val angleDegrees: Float = 0f,
    val visibleWidthMeters: Float = 0f, val coverage: List<Float> = emptyList(),
    val opening: OpeningMeasurement? = null) {
    val score get() = angleDegrees * (0.5f + (coverage.average().takeIf { it.isFinite() } ?: 0.0).toFloat()*0.5f)
}

object TurnMotion {
    fun guidance(m: MotionSnapshot) = m.rotationRate<=1.2 && m.tiltRotationRate<=0.8
    fun clear(m: MotionSnapshot) = m.rotationRate<=0.6 && m.tiltRotationRate<=0.35
}

/** Observation priority only. No lateral path, body sweep or missing-depth completion. */
object TurnSpace {
    fun side(scene: ImageHeightScene, side: AvoidanceDirection, distance: Float): SideTurnEvidence =
        sides(scene,distance).let { if(side==AvoidanceDirection.LEFT) it.first else it.second }

    /** Share the obstacle-face scan across both sides; keep extension and near-point checks live. */
    fun sides(scene: ImageHeightScene, distance: Float): Pair<SideTurnEvidence,SideTurnEvidence> {
        val w=scene.width;val h=scene.height;val stride=maxOf(4,w/160)
        // Compare space beside the actual central obstacle, including free
        // pixels within the forward envelope. Starting at its bounds loses near openings.
        val depths=mutableMapOf<Int,Float>()
        val surface=(0 until w step stride).filter { x ->
            var support=0
            for(y in 0 until h step maxOf(4,h/90)) {
                val z=depthAt(scene.bytes,w,x,y,scene.scale,HorizontalProjection.MAX_OBSERVED_DEPTH_METERS) ?: continue
                val normal=scene.floor(x,y,z) || scene.ceilingSurface(x,y,z)
                if(!normal) depths[x]=minOf(depths[x] ?: z,z)
                if(abs(z-distance)<=0.15f && abs(scene.camera.lateralMeters(x.toFloat(),z))<=scene.camera.halfForwardMeters &&
                    !normal) support++
            }
            support>=2
        }
        return sideFromSurface(scene,AvoidanceDirection.LEFT,distance,stride,depths,surface) to
            sideFromSurface(scene,AvoidanceDirection.RIGHT,distance,stride,depths,surface)
    }

    private fun sideFromSurface(scene: ImageHeightScene, side: AvoidanceDirection, distance: Float,
        stride: Int, depths: Map<Int,Float>, surface: List<Int>): SideTurnEvidence {
        val w=scene.width;val h=scene.height
        var edge=if(side==AvoidanceDirection.LEFT) surface.minOrNull() else surface.maxOrNull()
        // Follow a continuous face to its edge; a gradually receding cabinet
        // remains an obstacle, even when its far end exceeds the reference Z.
        if(edge!=null) {
            val step=if(side==AvoidanceDirection.LEFT) -stride else stride
            var previous=depths[edge]
            while(previous!=null) {
                val next=edge!!+step
                if(next !in 0 until w) return SideTurnEvidence(SideTurnStatus.BLOCKED)
                val z=depths[next] ?: break
                if(abs(z-previous)>0.15f) break
                edge=next;previous=z
            }
        }
        val cols=(0 until w step stride).filter {
            if(edge!=null) { if(side==AvoidanceDirection.LEFT) it<edge else it>edge }
            else { val x=scene.camera.lateralMeters(it.toFloat(),distance)
                if(side==AvoidanceDirection.LEFT) x < -scene.camera.halfForwardMeters else x > scene.camera.halfForwardMeters }
        }
        if(cols.isEmpty()) return SideTurnEvidence(SideTurnStatus.UNKNOWN)
        val bands=ImageHeightBand.entries.map { band -> cols.map { x ->
            var valid=0;var total=0;var far=0;var near=0
            for(y in (h*band.startFraction).toInt() until (h*band.endFraction).toInt() step maxOf(4,h/90)) {
                total++
                val z=depthAt(scene.bytes,w,x,y,scene.scale,HorizontalProjection.MAX_OBSERVED_DEPTH_METERS) ?: continue
                valid++
                val normal=scene.floor(x,y,z) || scene.ceilingSurface(x,y,z)
                if(normal || z>maxOf(1f,distance+0.15f)) far++ else near++
            }
            Triple(valid.toFloat()/total.coerceAtLeast(1),near,
                if(near>=2) -1 else if(valid>=3 && valid.toFloat()/total>=0.35f && far>=valid*0.8f) 1 else 0)
        } }
        val coverage=bands.map { row -> row.map { it.first }.average().toFloat() }
        var start=-1;var bestStart=-1;var bestEnd=-1
        for(i in 0..cols.size) {
            val open=if(i==cols.size) false else {
                val hi=bands[0][i].third;val mid=bands[1][i].third;val low=bands[2][i].third
                hi!=-1 && mid!=-1 && low!=-1 &&
                    ((mid==1 && (hi==1 || low==1)) || (mid==0 && hi==1 && low==1))
            }
            if(open && start<0) start=i
            if(!open && start>=0) {
                if(i-1-start>bestEnd-bestStart) {bestStart=start;bestEnd=i-1}
                start=-1
            }
        }
        if(bestStart<0) return SideTurnEvidence(
            if(bands.any { row -> row.zipWithNext().any { it.first.third==-1 && it.second.third==-1 } })
                SideTurnStatus.BLOCKED else SideTurnStatus.UNKNOWN,coverage=coverage)
        val a=cols[bestStart].toFloat();val b=cols[bestEnd].toFloat()
        val angle=abs(atan((b-scene.camera.cx)/scene.camera.fx)-atan((a-scene.camera.cx)/scene.camera.fx))*180f/Math.PI.toFloat()
        val span=abs(b-a)*distance/scene.camera.fx
        val viewEdge=cols[bestStart]==0 || cols[bestEnd]>=w-stride
        return SideTurnEvidence(if(angle>=8f || (viewEdge && angle>=3f)) SideTurnStatus.POSSIBLE else SideTurnStatus.UNKNOWN,
            angle,span,coverage,OpeningMeasurement(span,distance,a,b,if(viewEdge) "field_of_view" else "observed_span"))
    }

    fun centralCoverage(scene: ImageHeightScene,distance: Float): List<Float> = ImageHeightBand.entries.map { band ->
        var total=0;var valid=0
        for(x in 0 until scene.width step maxOf(4,scene.width/160)) {
            if(abs(scene.camera.lateralMeters(x.toFloat(),distance))>scene.camera.halfForwardMeters) continue
            for(y in (scene.height*band.startFraction).toInt() until (scene.height*band.endFraction).toInt() step maxOf(4,scene.height/90)) {
                total++;if(depthAt(scene.bytes,scene.width,x,y,scene.scale,HorizontalProjection.MAX_OBSERVED_DEPTH_METERS)!=null) valid++
            }
        }
        valid.toFloat()/total.coerceAtLeast(1)
    }

    /** Every third needs measured support. Normal planes count as observed, never as holes. */
    fun centralClear(scene: ImageHeightScene, clearMeters: Float, assumeMissingClear: Boolean = false): Boolean =
        centralSupport(scene,clearMeters,true,assumeMissingClear)

    /** Coverage and hole shape only; measured near objects do not mean missing data. */
    fun centralObserved(scene: ImageHeightScene, distance: Float): Boolean =
        centralSupport(scene,distance,false)

    private fun centralSupport(scene: ImageHeightScene, clearMeters: Float, checkObstacles: Boolean,
        assumeMissingClear: Boolean = false): Boolean {
        val w=scene.width;val h=scene.height;val step=maxOf(4,w/160)
        for(band in ImageHeightBand.entries) {
            var total=0;var valid=0;var free=0;var unknownRun=0;var longest=0
            for(x in 0 until w step step) {
                if(abs(scene.camera.lateralMeters(x.toFloat(),clearMeters))>scene.camera.halfForwardMeters) continue
                var colTotal=0;var colValid=0
                for(y in (h*band.startFraction).toInt() until (h*band.endFraction).toInt() step maxOf(4,h/90)) {
                    total++;colTotal++
                    val z=depthAt(scene.bytes,w,x,y,scene.scale,HorizontalProjection.MAX_OBSERVED_DEPTH_METERS) ?: continue
                    valid++;colValid++
                    if(checkObstacles && (scene.floor(x,y,z) || scene.ceilingSurface(x,y,z) || z>=clearMeters)) free++
                }
                unknownRun=if(colValid.toFloat()/colTotal.coerceAtLeast(1)<0.35f) unknownRun+1 else 0
                longest=maxOf(longest,unknownRun)
            }
            if(total==0 || (!assumeMissingClear && (valid.toFloat()/total<0.65f ||
                longest*step*clearMeters/scene.camera.fx>=0.20f)) ||
                (checkObstacles && valid>0 && free.toFloat()/valid<0.98f)) return false
            // A nearer point can project into the physical forward envelope from outside the
            // narrow far-distance ROI. Check it across the whole frame as well.
            if(checkObstacles) for(y in (h*band.startFraction).toInt() until (h*band.endFraction).toInt() step maxOf(4,h/90))
                for(x in 0 until w step step) {
                    val z=depthAt(scene.bytes,w,x,y,scene.scale,HorizontalProjection.MAX_OBSERVED_DEPTH_METERS) ?: continue
                    if(z<clearMeters && abs(scene.camera.lateralMeters(x.toFloat(),z))<=scene.camera.halfForwardMeters &&
                        !scene.floor(x,y,z) && !scene.ceilingSurface(x,y,z)) {
                        // One isolated return is not an obstacle. Preserve small coherent
                        // surfaces, including near points outside the far-distance ROI.
                        val neighbours=listOf(x-step to y,x+step to y,x to y-step,x to y+step)
                        if(neighbours.any { (nx,ny) ->
                            nx in 0 until w && ny in (h*band.startFraction).toInt() until (h*band.endFraction).toInt() &&
                                depthAt(scene.bytes,w,nx,ny,scene.scale,HorizontalProjection.MAX_OBSERVED_DEPTH_METERS)?.let { nz ->
                                    nz<clearMeters && abs(nz-z)<=0.12f &&
                                    abs(scene.camera.lateralMeters(nx.toFloat(),nz))<=scene.camera.halfForwardMeters &&
                                    !scene.floor(nx,ny,nz) && !scene.ceilingSurface(nx,ny,nz)
                                }==true
                        }) return false
                    }
                }
        }
        return true
    }
}
