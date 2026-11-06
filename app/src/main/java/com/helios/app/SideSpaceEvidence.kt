package com.helios.app

import kotlin.math.abs
import kotlin.math.atan

data class BandSpaceCoverage(val validRatio: Float,val zeroRatio: Float,val outOfRangeRatio: Float,
    val largestUnknownMeters: Float)

/** A request to observe a possible opening. Never free-space completion or route approval. */
data class SideSpaceEvidence(val eligible: Boolean,val reason: String,val visibleWidthMeters: Float = 0f,
    val angleSpanDegrees: Float = 0f,val extendsToViewEdge: Boolean = false,
    val upper: BandSpaceCoverage? = null,val middle: BandSpaceCoverage? = null,val lower: BandSpaceCoverage? = null)

object SideSpaceAnalyzer {
    private data class Column(val valid: Int,val total: Int,val zero: Int,val outOfRange: Int,
        val near: Int,val far: Int) {
        val ratio get() = valid.toFloat()/total.coerceAtLeast(1)
        // This relaxed support is for finding an observation target only.
        val opening get() = valid>=3 && ratio>=0.35f && near==0 && far>=valid*0.8f
    }
    fun evaluate(scene: ImageHeightScene,startColumn: Int?,direction: AvoidanceDirection,targetZ: Float,
        stride: Int): SideSpaceEvidence {
        val w=scene.width;val h=scene.height;val count=(w+stride-1)/stride
        if(startColumn==null || startColumn !in 0 until count) return SideSpaceEvidence(false,"no_visible_obstacle_edge")
        val step=if(direction==AvoidanceDirection.RIGHT) 1 else -1
        val columns=generateSequence(startColumn) { (it+step).takeIf { col -> col in 0 until count } }.toList()
        val cutoff=maxOf(1f,targetZ+0.15f)
        fun band(b: ImageHeightBand)=columns.map { col ->
            val x=minOf(col*stride,w-1);var total=0;var valid=0;var zero=0;var range=0;var near=0;var far=0
            for(y in (h*b.startFraction).toInt() until (h*b.endFraction).toInt() step maxOf(4,h/90)) {
                total++;val i=(y*w+x)*2
                val raw=(scene.bytes[i].toInt() and 255) or ((scene.bytes[i+1].toInt() and 255) shl 8)
                val z=raw*scene.scale/1000f
                if(raw==0) { zero++;continue }
                if(z !in 0.25f..6f) { range++;continue }
                valid++
                val normal=scene.floor(x,y,z) || scene.ceilingSurface(x,y,z)
                if(z<=cutoff && !normal) near++
                if(z>cutoff) far++
            }
            Column(valid,total,zero,range,near,far)
        }
        val high=band(ImageHeightBand.HIGH);val mid=band(ImageHeightBand.FRONT);val low=band(ImageHeightBand.GROUND)
        fun coverage(values: List<Column>,indices: IntRange): BandSpaceCoverage {
            val selected=indices.map { values[it] };val total=selected.sumOf { it.total }.coerceAtLeast(1)
            var run=0;var longest=0
            for(c in selected) {
                run=if(c.ratio<0.65f) run+1 else 0;longest=maxOf(longest,run)
            }
            return BandSpaceCoverage(selected.sumOf { it.valid }.toFloat()/total,
                selected.sumOf { it.zero }.toFloat()/total,selected.sumOf { it.outOfRange }.toFloat()/total,
                longest*stride*targetZ/scene.camera.fx)
        }
        // Intersect POSITION intervals, rather than taking the larger band's width.
        var bestStart=-1;var bestEnd=-1;var begin=-1
        for(i in 0..columns.size) {
            val common=i<columns.size && high[i].opening && low[i].opening
            if(common && begin<0) begin=i
            if(!common && begin>=0) {
                if(i-1-begin>bestEnd-bestStart) { bestStart=begin;bestEnd=i-1 }
                begin=-1
            }
        }
        val all=columns.indices
        if(bestStart<0) return SideSpaceEvidence(false,"upper_lower_openings_unaligned",
            upper=coverage(high,all),middle=coverage(mid,all),lower=coverage(low,all))
        val indices=bestStart..bestEnd
        val x0=columns[bestStart]*stride;val x1=columns[bestEnd]*stride
        val visible=abs(x1-x0)*targetZ/scene.camera.fx
        val angle=abs(atan((x1-scene.camera.cx)/scene.camera.fx)-atan((x0-scene.camera.cx)/scene.camera.fx))*180f/Math.PI.toFloat()
        val edge=bestEnd==columns.lastIndex
        val middle=coverage(mid,indices)
        val blocked=indices.any { mid[it].near>0 }
        val support=indices.sumOf { mid[it].far }>=3
        val eligible=!blocked && support && angle>=8f && (edge || visible>=1f) &&
            coverage(high,indices).validRatio>=0.65f && coverage(low,indices).validRatio>=0.65f
        return SideSpaceEvidence(eligible,when {
            blocked -> "middle_near_surface"
            !support -> "middle_space_unobserved"
            eligible && middle.largestUnknownMeters>0f -> "missing_depth_reobserve"
            eligible && edge -> "view_edge_reobserve"
            else -> "insufficient_opening_evidence"
        },visible,angle,edge,coverage(high,indices),middle,coverage(low,indices))
    }
}
