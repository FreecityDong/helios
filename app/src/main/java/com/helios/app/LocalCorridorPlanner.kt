package com.helios.app

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

data class OpeningMeasurement(val widthMeters: Float?,val distanceMeters: Float,
    val startPixel: Float?=null,val endPixel: Float?=null,val boundary: String="unknown",
    val nextColumn: Int? = null)

/** Experimental horizontal observation corridors; ground/near-body support is not certified. */
data class CorridorConfig(val bodyWidthMeters: Float = 0.60f,
    val sideMarginMeters: Float = 0.15f, val positionErrorMeters: Float = 0.05f) {
    init { require(bodyWidthMeters in 0.3f..1.2f && sideMarginMeters in 0.05f..0.5f &&
        positionErrorMeters in 0.01f..0.3f) }
    val requiredWidth get() = bodyWidthMeters+2*(sideMarginMeters+positionErrorMeters)
}

data class CorridorEvidence(val direction: AvoidanceDirection, val accepted: Boolean,
    val reason: String, val offsetMeters: Float = 0f, val requiredWidthMeters: Float = 1f,
    val minimumObservedWidthMeters: Float = 0f, val coverage: Float = 0f,
    val minimumGapMeters: Float = 0f,
    val observationEligible: Boolean = false, val obstacleOpeningWidthMeters: Float = 0f,
    val diagnosticsAvailable: Boolean = false, val coverageFailure: String = "not_evaluated",
    val fieldOfViewCoverage: Float = 0f, val reliableDepthWithinView: Float = 0f,
    val freeWithinReliableDepth: Float = 0f, val outOfViewFraction: Float = 0f,
    val invalidDepthFraction: Float = 0f, val occludedFraction: Float = 0f,
    val observedFreeFraction: Float = 0f, val minimumVisibleWidthMeters: Float = 0f,
    val sideVisibleWidthAtObstacleMeters: Float = 0f,
    val blockedHeightBand: ImageHeightBand? = null, val groundAssessment: String = "not_evaluated",
    val measuredSideOpeningWidthMeters: Float? = null,
    val openingMeasurement: OpeningMeasurement? = null,
    val sideSpace: SideSpaceEvidence? = null) {
    val availableForObservation get() = accepted || observationEligible
}

data class CorridorDecision(val left: CorridorEvidence, val right: CorridorEvidence) {
    fun bestReobserve(): AvoidanceDirection? = listOf(left,right).filter { it.sideSpace?.eligible==true }
        .maxByOrNull { it.sideSpace!!.angleSpanDegrees }?.direction
    fun best(previous: AvoidanceDirection?): AvoidanceDirection? {
        if (!left.availableForObservation && !right.availableForObservation) return null
        if (!left.availableForObservation) return AvoidanceDirection.RIGHT
        if (!right.availableForObservation) return AvoidanceDirection.LEFT
        if (left.accepted && !right.accepted) return AvoidanceDirection.LEFT
        if (right.accepted && !left.accepted) return AvoidanceDirection.RIGHT
        // Switching is only useful if the new corridor has a substantial advantage.
        if (abs(left.minimumGapMeters-right.minimumGapMeters) < 0.15f && previous != null) return previous
        return if (left.minimumGapMeters > right.minimumGapMeters) AvoidanceDirection.LEFT else AvoidanceDirection.RIGHT
    }
}

class LocalCorridorPlanner(private val config: CorridorConfig = CorridorConfig(),
    private val highObstacleMeters: Float = 2f) {
    private data class Ray(val z: Float?, val validRatio: Float,val band: ImageHeightBand = ImageHeightBand.FRONT)
    private data class Point(val x: Float,val z: Float,val band: ImageHeightBand,val column: Int)

    fun evaluate(bytes: ByteArray,width: Int,height: Int,scaleMm: Float,
        camera: HorizontalProjection?,obstacleMeters: Float?,imageScene: ImageHeightScene? = null): CorridorDecision {
        fun unavailable(reason: String) = CorridorDecision(
            CorridorEvidence(AvoidanceDirection.LEFT,false,reason,requiredWidthMeters=config.requiredWidth),
            CorridorEvidence(AvoidanceDirection.RIGHT,false,reason,requiredWidthMeters=config.requiredWidth))
        if (camera == null || !camera.matches(width,height)) return unavailable("intrinsics_unavailable")
        if (bytes.size.toLong() < width.toLong()*height*2 || !scaleMm.isFinite() || scaleMm <= 0f)
            return unavailable("depth_unavailable")
        val targetZ=obstacleMeters?.takeIf { it.isFinite() && it in 0.25f..2.2f }
            ?: return unavailable("no_current_obstacle")
        // Near-body space is outside the chest camera's coverage. Do not invent
        // a route around a very close object from a distant background.
        if (targetZ < 1.0f && imageScene==null) return unavailable("near_body_coverage_unknown")
        val stride=maxOf(4,width/160)
        val count=(width+stride-1)/stride
        val rays=Array(count) { Ray(null,0f) }
        for (col in rays.indices) {
            val values=ArrayList<Float>()
            var total=0
            val x=minOf(col*stride,width-1)
            var closestBand=ImageHeightBand.FRONT
            var closest=Float.POSITIVE_INFINITY
            // The middle band supplies horizontal clearance/coverage.
            // High near hazards veto separately; far overhead returns must not
            // replace the middle band's background in every image column.
            val yStart=if (imageScene!=null) height/3 else (height*0.35f).toInt()
            val yEnd=if (imageScene!=null) height*2/3 else (height*0.70f).toInt()
            for (y in yStart until yEnd step maxOf(4,height/90)) {
                total++
                val z=depthAt(bytes,width,x,y,scaleMm,6f) ?: continue
                if(imageScene?.floor(x,y,z)==true || imageScene?.ceilingSurface(x,y,z)==true) {
                    continue
                }
                values.add(z)
                if (z<closest) { closest=z;closestBand=ImageHeightBand.FRONT }
            }
            val reliableRatio=values.size.toFloat()/total.coerceAtLeast(1)
            if(imageScene!=null) for(y in 0 until height/3 step maxOf(4,height/90)) {
                val z=depthAt(bytes,width,x,y,scaleMm) ?: continue
                if(z<=highObstacleMeters && !imageScene.floor(x,y,z) && !imageScene.ceilingSurface(x,y,z)) {
                    values.add(z)
                    if(z<closest) { closest=z;closestBand=ImageHeightBand.HIGH }
                }
            }
            if (imageScene!=null) for (y in height*2/3 until height step maxOf(4,height/90)) {
                val z=depthAt(bytes,width,x,y,scaleMm) ?: continue
                if (imageScene.groundHazard(x,y,z)) {
                    values.add(z)
                    if (z<closest) { closest=z;closestBand=ImageHeightBand.GROUND }
                }
            }
            values.sort()
            rays[col]=Ray(values.firstOrNull(),reliableRatio,closestBand)
        }
        // Every reliable nearer return blocks its ray. A continuous neighbour
        // pair supplies occupied geometry; isolated returns remain unknown.
        val points=ArrayList<Point>()
        for (col in rays.indices) {
            val ray=rays[col]; val z=ray.z ?: continue
            if (ray.validRatio < 0.65f) continue
            val connected=(col>0 && rays[col-1].z?.let { abs(it-z)<=0.15f } == true) ||
                (col<rays.lastIndex && rays[col+1].z?.let { abs(it-z)<=0.15f } == true)
            if (connected) points.add(Point(camera.lateralMeters((col*stride).toFloat(),z),z,ray.band,col))
        }
        val radius=config.requiredWidth/2
        val end=minOf(3.2f,targetZ+0.5f)
        // This observed section is a side-observation aid, not a claim that
        // the user's unobserved first step or ground surface is traversable.
        val start=1.0f
        // Measure beside the observed obstacle, not beside the speech envelope.
        val frontSurface=points.filter { abs(it.x)<=camera.halfForwardMeters && abs(it.z-targetZ)<=0.15f }
        fun measure(direction: AvoidanceDirection): OpeningMeasurement {
            val edge=if (direction==AvoidanceDirection.RIGHT) frontSurface.maxByOrNull { it.column }
                else frontSurface.minByOrNull { it.column }
            if (edge==null) return OpeningMeasurement(null,targetZ,boundary="obstacle_edge_unknown")
            val step=if (direction==AvoidanceDirection.RIGHT) 1 else -1
            // Preserve the measured column: reprojecting X at another Z can land
            // back on the obstacle. Follow its continuous surface beyond the
            // speech envelope before measuring the adjacent opening.
            var col=edge.column+step
            var previousDepth=edge.z
            while(col in rays.indices) {
                val ray=rays[col];val depth=ray.z ?: break
                // The nearest sample can switch image thirds on the same cabinet;
                // an image-band change is not a measured physical edge.
                if(ray.validRatio<0.65f || abs(depth-previousDepth)>0.15f) break
                previousDepth=depth;col+=step
            }
            if(col !in rays.indices) return OpeningMeasurement(null,targetZ,boundary="obstacle_continues_out_of_view")
            var first: Int?=null;var last: Int?=null;var boundary="field_of_view"
            while (col in rays.indices) {
                val ray=rays[col]
                if (ray.validRatio<0.65f || ray.z==null) { boundary="invalid_depth";break }
                if (ray.z<=targetZ+0.10f) { boundary="near_surface";break }
                if (first==null) first=col
                last=col;col+=step
            }
            // No observed free span is an unmeasured opening, not a measured
            // zero-width passage. Keep route rejection independent of this label.
            // The analyser needs the opening's original start, including a
            // missing first column, rather than its reliable tail.
            val openingStart=if(first!=null) first else col
            if (first==null || last==null || first==last) return OpeningMeasurement(null,targetZ,boundary=boundary,nextColumn=openingStart)
            val a=(first*stride).toFloat();val b=(last*stride).toFloat()
            return OpeningMeasurement(abs(b-a)*targetZ/camera.fx,targetZ,minOf(a,b),maxOf(a,b),boundary,first)
        }
        val leftOpening=measure(AvoidanceDirection.LEFT);val rightOpening=measure(AvoidanceDirection.RIGHT)
        val leftSpace=imageScene?.let { SideSpaceAnalyzer.evaluate(it,leftOpening.nextColumn,AvoidanceDirection.LEFT,targetZ,stride) }
        val rightSpace=imageScene?.let { SideSpaceAnalyzer.evaluate(it,rightOpening.nextColumn,AvoidanceDirection.RIGHT,targetZ,stride) }
        if(targetZ<1f) {
            fun near(direction: AvoidanceDirection,opening: OpeningMeasurement,space: SideSpaceEvidence?)=
                CorridorEvidence(direction,false,"near_body_coverage_unknown",requiredWidthMeters=config.requiredWidth,
                    diagnosticsAvailable=true,coverageFailure="near_body_unknown",
                    measuredSideOpeningWidthMeters=opening.widthMeters,openingMeasurement=opening,sideSpace=space)
            return CorridorDecision(near(AvoidanceDirection.LEFT,leftOpening,leftSpace),near(AvoidanceDirection.RIGHT,rightOpening,rightSpace))
        }
        fun inspect(direction: AvoidanceDirection,offset: Float): CorridorEvidence {
            val signed=if (direction==AvoidanceDirection.LEFT) -offset else offset
            fun center(z: Float)=signed*((z-0.25f)/(targetZ-0.45f).coerceAtLeast(0.35f)).coerceIn(0f,1f)
            var minimumGap=Float.POSITIVE_INFINITY
            var blockedBand: ImageHeightBand?=null
            for (p in points) {
                if (p.z !in (if (imageScene!=null) 0.25f else start-0.10f)..(end+0.10f)) continue
                // Check the swept footprint along the diagonal, including its
                // front/back extent, rather than distance at only one endpoint.
                var z=if (imageScene!=null) 0.25f else start
                while (z <= end+0.001f) {
                    val gap=hypot(p.x-center(z),p.z-z)-radius
                    if (gap<minimumGap) { minimumGap=gap;blockedBand=if (gap<=0f) p.band else null }
                    z+=0.10f
                }
            }
            val occupied=minimumGap <= 0f
            var queried=0; var observed=0; var visible=0; var reliable=0
            var invalid=0; var occluded=0; var minVisibleWidth=Float.POSITIVE_INFINITY
            var minWidth=Float.POSITIVE_INFINITY
            var anchorCount=0; var anchorWidth=Float.POSITIVE_INFINITY
            var z=start
            while (z <= end+0.001f) {
                val pathX=center(z)
                val u0=camera.pixel(pathX-radius,z)
                val u1=camera.pixel(pathX+radius,z)
                val viewLeft=camera.lateralMeters(0f,z)
                val viewRight=camera.lateralMeters((width-1).toFloat(),z)
                val visibleWidth=(minOf(pathX+radius,viewRight)-maxOf(pathX-radius,viewLeft)).coerceAtLeast(0f)
                minVisibleWidth=minOf(minVisibleWidth,visibleWidth)
                var sectionValid=true
                if (u0 < 0f || u1 > width-1f) sectionValid=false
                val first=floor(u0/stride).toInt(); val last=ceil(u1/stride).toInt()
                for (col in first..last) {
                    queried++
                    val ray=rays.getOrNull(col)
                    when {
                        ray == null -> sectionValid=false
                        ray.validRatio<0.65f || ray.z == null -> {
                            visible++;invalid++;sectionValid=false
                        }
                        else -> {
                            visible++;reliable++
                            if (ray.z>z+0.10f) observed++ else { occluded++;sectionValid=false }
                        }
                    }
                }
                var sectionWidth=0f
                if (sectionValid) {
                    fun free(col: Int)=rays.getOrNull(col)?.let { it.validRatio>=0.65f &&
                        it.z?.let { depth->depth>z+0.10f } == true } == true
                    var a=first; var b=last
                    while (a>0 && free(a-1)) a--
                    while (b<rays.lastIndex && free(b+1)) b++
                    sectionWidth=(b-a)*stride*z/camera.fx
                    minWidth=minOf(minWidth,sectionWidth)
                } else minWidth=0f
                if (z-targetZ in 0.199f..0.501f) {
                    anchorCount++
                    anchorWidth=minOf(anchorWidth,sectionWidth)

                }
                z+=0.10f
            }
            val freeFraction=observed.toFloat()/queried.coerceAtLeast(1)
            // Preserve the existing selection/coverage behavior for collided candidates.
            val coverage=if (occupied) 0f else freeFraction
            val full=!occupied && minWidth>=config.requiredWidth
            // A visible opening beside the obstacle can support a STOP/OBSERVE
            // suggestion even when the initial body sweep is outside the FOV.
            // It never upgrades the unknown entry/ground to a traversable route.
            val observe=!full && coverage>=0.60f && anchorCount>=3 &&
                anchorWidth>=config.requiredWidth && minimumGap>0f
            val outOfView=queried-visible
            val failure=when {
                occupied -> "occupied"
                full -> "none"
                outOfView>=maxOf(invalid,occluded) && outOfView>0 -> "out_of_view"
                invalid>=occluded && invalid>0 -> "invalid_depth"
                occluded>0 -> "occluded_or_near_return"
                else -> "insufficient_opening"
            }
            val sideVisibleWidth=if (direction==AvoidanceDirection.RIGHT)
                (camera.lateralMeters((width-1).toFloat(),targetZ)-camera.halfForwardMeters).coerceAtLeast(0f)
                else (-camera.halfForwardMeters-camera.lateralMeters(0f,targetZ)).coerceAtLeast(0f)
            return CorridorEvidence(direction,full,
                if (occupied) "occupied_corridor" else if (full) "observed_horizontal_corridor" else if (observe) "visible_opening_entry_unknown"
                    else "unknown_or_occluded_corridor",
                offset,config.requiredWidth,if (!occupied && minWidth.isFinite()) minWidth else 0f,coverage,
                if (minimumGap.isFinite()) minimumGap else 0f,observe,
                if (!occupied && anchorWidth.isFinite()) anchorWidth else 0f,
                diagnosticsAvailable=true,coverageFailure=failure,
                fieldOfViewCoverage=visible.toFloat()/queried.coerceAtLeast(1),
                reliableDepthWithinView=reliable.toFloat()/visible.coerceAtLeast(1),
                freeWithinReliableDepth=observed.toFloat()/reliable.coerceAtLeast(1),
                outOfViewFraction=outOfView.toFloat()/queried.coerceAtLeast(1),
                invalidDepthFraction=invalid.toFloat()/queried.coerceAtLeast(1),
                occludedFraction=occluded.toFloat()/queried.coerceAtLeast(1),
                observedFreeFraction=freeFraction,minimumVisibleWidthMeters=minVisibleWidth,
                sideVisibleWidthAtObstacleMeters=sideVisibleWidth,blockedHeightBand=blockedBand,
                groundAssessment=if (imageScene == null) "not_evaluated" else if (imageScene.ground == null) "ground_unknown" else "measured_plane_near_support_unverified",
                measuredSideOpeningWidthMeters=(if (direction==AvoidanceDirection.LEFT) leftOpening else rightOpening).widthMeters,
                openingMeasurement=if (direction==AvoidanceDirection.LEFT) leftOpening else rightOpening,
                sideSpace=if(direction==AvoidanceDirection.LEFT) leftSpace else rightSpace)
        }
        fun side(direction: AvoidanceDirection): CorridorEvidence {
            val choices=listOf(0.70f,0.85f,1.0f,1.15f).map { inspect(direction,it) }
            return choices.filter { it.accepted }.maxByOrNull { it.minimumGapMeters } ?:
                choices.filter { it.observationEligible }.maxByOrNull { it.minimumGapMeters } ?:
                choices.maxBy { it.coverage }
        }
        return CorridorDecision(side(AvoidanceDirection.LEFT),side(AvoidanceDirection.RIGHT))
    }
}

/** Current observations only: positive confirmation is delayed, contradictions revoke immediately. */
class CorridorDirectionEvaluator(private val planner: LocalCorridorPlanner = LocalCorridorPlanner()) {
    private var candidate: AvoidanceDirection? = null
    private var confirmed: AvoidanceDirection? = null
    private var since=0L
    private var samples=0
    private var lastMs=Long.MIN_VALUE
    private var lastPose: DoubleArray?=null
    private var reobserveMode=false
    fun reset() { candidate=null;confirmed=null;since=0;samples=0;lastMs=Long.MIN_VALUE;lastPose=null;reobserveMode=false }
    fun evaluate(now: Long,bytes: ByteArray,width: Int,height: Int,scale: Float,
        camera: HorizontalProjection?,meters: Float?,motion: MotionSnapshot,pose: DoubleArray?,
        imageScene: ImageHeightScene? = null): AvoidanceSnapshot {
        if (!motion.calibrated || motion.stale || motion.state !in setOf(MotionState.MOVING,MotionState.STILL) ||
            motion.rotationRate>0.55 || motion.tiltRotationRate>0.50) {
            reset();return AvoidanceSnapshot(reason="corridor_motion_unavailable",geometryMode="physical_corridor")
        }
        val norm=pose?.takeIf { it.size==4 && it.all(Double::isFinite) }
            ?.let { kotlin.math.sqrt(it.sumOf { v->v*v }) }
        if (norm == null || norm !in 0.9..1.1) {
            reset();return AvoidanceSnapshot(reason="corridor_pose_unavailable",geometryMode="physical_corridor")
        }
        val normalized=DoubleArray(4) { pose[it]/norm }
        val rotated=lastPose?.let { q -> 2*kotlin.math.acos(abs(q.indices.sumOf { q[it]*normalized[it] }).coerceIn(0.0,1.0))>0.12 } == true
        if (lastMs != Long.MIN_VALUE && (now-lastMs !in 0..800 || rotated)) reset()
        val decision=planner.evaluate(bytes,width,height,scale,camera,meters,imageScene)
        val route=decision.best(confirmed ?: candidate)
        val nextMode=route==null
        val best=route ?: decision.bestReobserve()
        if(nextMode!=reobserveMode) { candidate=null;confirmed=null;samples=0;since=now;reobserveMode=nextMode }
        fun supported(direction: AvoidanceDirection): Boolean {
            val e=if(direction==AvoidanceDirection.LEFT) decision.left else decision.right
            return if(reobserveMode) e.sideSpace?.eligible==true else e.availableForObservation
        }
        if (confirmed != null && !supported(confirmed!!))
            confirmed=null
        if (best != candidate) { candidate=best;since=now;samples=0 }
        if (now != lastMs && best != null) samples++
        lastMs=now;lastPose=normalized
        if (best != null && samples>=3 && now-since>=400) confirmed=best
        val selected=confirmed?.takeIf { supported(it) }
        return AvoidanceSnapshot(direction=selected.takeUnless { reobserveMode },reobserveDirection=selected.takeIf { reobserveMode },
            reason=if (selected != null) {
                val evidence=if (selected==AvoidanceDirection.LEFT) decision.left else decision.right
                if(reobserveMode) "reobserve_${selected.name.lowercase()}"
                else if (evidence.observationEligible) "corridor_observe_${selected.name.lowercase()}"
                else "corridor_confirmed_${selected.name.lowercase()}"
            }
                else if (best != null) "corridor_collecting" else "corridor_unavailable",
            supportingSamples=samples,geometryMode="physical_corridor",geometryDistanceMeters=meters,
            corridorLeft=decision.left,corridorRight=decision.right)
    }
}
