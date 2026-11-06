package com.helios.app

import kotlin.math.abs
import kotlin.math.acos

enum class AvoidanceDirection { LEFT, RIGHT }

data class AvoidanceSnapshot(
    val direction: AvoidanceDirection? = null,
    val reason: String = "insufficient_history",
    val leftClearanceMeters: Float? = null,
    val centerClearanceMeters: Float? = null,
    val rightClearanceMeters: Float? = null,
    val supportingSamples: Int = 0,
    val obstacleCleared: Boolean = false,
    val directionReady: Boolean = false,
    val leftValidRatio: Float = 0f,
    val centerValidRatio: Float = 0f,
    val rightValidRatio: Float = 0f,
    val guidanceLevel: RiskLevel? = null,
    val remembered: Boolean = false,
    val attachDirectionToRisk: Boolean = false,
    val forwardClassId: Int? = null,
    val directionEvidenceExpiresMs: Long? = null,
    val geometryMode: String = "image_fraction_fallback",
    val geometryDistanceMeters: Float? = null,
    val corridorLeft: CorridorEvidence? = null,
    val corridorRight: CorridorEvidence? = null,
    val reobserveDirection: AvoidanceDirection? = null,
    val centralCoverage: List<Float> = emptyList(),
    val spaceHint: SpatialHint? = null,
    val turnState: TurnState? = null, val turnLeft: SideTurnEvidence? = null, val turnRight: SideTurnEvidence? = null,
)

/** Short-lived camera-relative depth evidence; it cannot establish a walkable route. */
class AvoidanceDirectionEvaluator {
    private data class Lane(val validRatio: Float, val nearRatio: Float, val clearance: Float?) {
        val usableSide: Boolean get() = validRatio >= 0.65f && nearRatio <= 0.06f &&
            (clearance ?: 0f) >= 1.40f
        val clearCenter: Boolean get() = validRatio >= 0.45f && nearRatio <= 0.06f &&
            (clearance ?: 0f) >= 1.80f
    }
    private data class Triple(val timeMs: Long, val left: Float?, val center: Float,
        val right: Float?, val candidate: AvoidanceDirection?, val forwardClassId: Int?)
    private data class Remembered(val direction: AvoidanceDirection, val timeMs: Long,
        val orientation: DoubleArray)
    private data class DepthPoseSample(val timeMs: Long, val orientation: DoubleArray,
        val left: Float?, val right: Float?, val candidate: AvoidanceDirection?)
    private val depthPoseHistory = ArrayDeque<DepthPoseSample>()
    private val history = ArrayDeque<Triple>()
    private var lastSampleMs = Long.MIN_VALUE
    private var latest = AvoidanceSnapshot()
    private var warnedObstacle = false
    private var warnedMs = 0L
    private var clearSinceMs = 0L
    private var directionSent = false
    private var warnedLevel: RiskLevel? = null
    private var remembered: Remembered? = null

    fun reset() {
        depthPoseHistory.clear()
        history.clear(); lastSampleMs = Long.MIN_VALUE; latest = AvoidanceSnapshot()
        warnedObstacle = false; warnedMs = 0L; clearSinceMs = 0L; directionSent = false
        warnedLevel = null
        remembered = null
    }

    fun evaluate(nowMs: Long, depth: ByteArray, width: Int, height: Int, scaleMm: Float,
        boxes: List<TargetBox>, motion: MotionSnapshot, visual: VisualMotionEvidence,
        aligned: Boolean, active: Boolean, riskEvent: LocalRiskEvent?,
        unknownPresent: Boolean = false, orientationQuaternion: DoubleArray? = null,
        projection: HorizontalProjection? = null, obstacleMeters: Float? = null): AvoidanceSnapshot {
        if (riskEvent != null && riskEvent.kind == RiskEventKind.OBSTACLE) {
            if (!warnedObstacle) directionSent = false
            warnedObstacle = true; warnedMs = nowMs; clearSinceMs = 0L
            if (riskEvent.level == RiskLevel.URGENT || warnedLevel == null) warnedLevel = riskEvent.level
        }
        if (!active || !aligned || !motion.calibrated || motion.stale ||
            width < 80 || height < 80 || depth.size.toLong() < width.toLong() * height * 2 ||
            !scaleMm.isFinite() || scaleMm <= 0f) {
            reset()
            return AvoidanceSnapshot(reason = "motion_or_depth_unavailable")
        }
        if (motion.state !in setOf(MotionState.MOVING,MotionState.STILL) || motion.rotationRate > 0.55 ||
            motion.tiltRotationRate > 0.50) {
            depthPoseHistory.clear()
            history.clear(); clearSinceMs = 0L; remembered = null
            return AvoidanceSnapshot(reason = "motion_or_depth_unavailable")
        }
        val orientation = orientationQuaternion?.takeIf { it.size == 4 &&
            it.all(Double::isFinite) }?.let { q ->
            val norm = kotlin.math.sqrt(q.sumOf { it*it })
            if (norm in 0.9..1.1) DoubleArray(4) { q[it]/norm } else null
        }
        val visualFresh = nowMs - visual.timeMs in 0..600
        val visualReliable = visualFresh && visual.kind != VisualMotionKind.UNKNOWN &&
            visual.matchedTiles >= 8 && visual.consensusTiles >= 6
        // Forward translation need not give identical pixel shifts at different depths.
        // Fresh but uncertain RGB can use current depth and stable IMU pose instead;
        // departure remains separate; occluded history still needs its own pose,
        // age and current-near-obstacle checks.
        val depthPoseFallback = !visualReliable && visualFresh &&
            visual.kind == VisualMotionKind.UNKNOWN && orientation != null
        if (!visualReliable && !depthPoseFallback) {
            depthPoseHistory.clear()
            clearSinceMs = 0L
            return AvoidanceSnapshot(reason = "visual_unavailable")
        }
        if (visual.kind == VisualMotionKind.GLOBAL_SHIFT &&
            (abs(visual.shiftX) >= 2 || visual.shiftPixels >= 3.0)) {
            depthPoseHistory.clear()
            history.clear(); clearSinceMs = 0L; remembered = null
            return AvoidanceSnapshot(reason = "scene_shift")
        }
        if (lastSampleMs != Long.MIN_VALUE && nowMs - lastSampleMs in 0..249)
            return latest.copy(directionReady=false,obstacleCleared=false)
        if (lastSampleMs != Long.MIN_VALUE && nowMs - lastSampleMs !in 0..1_500) {
            history.clear(); depthPoseHistory.clear(); clearSinceMs = 0L
        }
        lastSampleMs = nowMs
        val camera = projection?.takeIf { it.matches(width,height) }
        val z = (obstacleMeters ?: riskEvent?.distanceMeters)?.takeIf { it.isFinite() && it in 0.25f..4f }
        fun bounds(a: Float,b: Float,fallbackLeft: Float,fallbackRight: Float): Pair<Float,Float> =
            if (camera != null && z != null) camera.pixel(a,z)/width to camera.pixel(b,z)/width
            else fallbackLeft to fallbackRight
        val leftBounds = bounds(-0.95f,-0.32f,0.06f,0.34f)
        val centerBounds = bounds(-0.32f,0.32f,0.34f,0.66f)
        val rightBounds = bounds(0.32f,0.95f,0.66f,0.94f)
        val left = lane(depth,width,height,scaleMm,leftBounds.first,leftBounds.second)
        val center = lane(depth,width,height,scaleMm,centerBounds.first,centerBounds.second)
        val right = lane(depth,width,height,scaleMm,rightBounds.first,rightBounds.second)
        val l = left.clearance; val r = right.clearance
        val c = center.clearance?.let { if (camera != null && z != null) minOf(it,z) else it }
        if (c == null || center.validRatio < 0.35f) {
            history.clear(); depthPoseHistory.clear(); clearSinceMs = 0L
            return AvoidanceSnapshot(reason="insufficient_depth",leftClearanceMeters=l,
                centerClearanceMeters=c,rightClearanceMeters=r,
                leftValidRatio=left.validRatio,centerValidRatio=center.validRatio,
                rightValidRatio=right.validRatio)
        }
        val previous = history.lastOrNull()
        if (previous != null && ((previous.left != null && l != null &&
            abs(previous.left-l) > 0.9f) || abs(previous.center-c) > 0.9f ||
            (previous.right != null && r != null && abs(previous.right-r) > 0.9f))) {
            history.clear(); depthPoseHistory.clear(); clearSinceMs = 0L; remembered = null
        }
        val leftNear = camera != null && metricNearSide(depth,width,height,scaleMm,camera,-0.95f,-0.32f)
        val rightNear = camera != null && metricNearSide(depth,width,height,scaleMm,camera,0.32f,0.95f)
        val leftUsable = !leftNear && left.usableSide && !sideBox(boxes,width,height,0.06f,0.34f,camera) &&
            l != null && l >= c+0.45f
        val rightUsable = !rightNear && right.usableSide && !sideBox(boxes,width,height,0.66f,0.94f,camera) &&
            r != null && r >= c+0.45f
        val candidate = when {
            leftUsable && !rightUsable -> AvoidanceDirection.LEFT
            rightUsable && !leftUsable -> AvoidanceDirection.RIGHT
            leftUsable && rightUsable && l!!-r!! >= 0.60f -> AvoidanceDirection.LEFT
            leftUsable && rightUsable && r!!-l!! >= 0.60f -> AvoidanceDirection.RIGHT
            leftUsable && rightUsable -> history.lastOrNull()?.candidate ?:
                if (l!! > r!!) AvoidanceDirection.LEFT else AvoidanceDirection.RIGHT
            else -> null
        }
        if (depthPoseFallback) {
            while (depthPoseHistory.isNotEmpty() && nowMs-depthPoseHistory.first().timeMs > 1_200)
                depthPoseHistory.removeFirst()
            val oldPose = depthPoseHistory.firstOrNull()
            val oldDepth = depthPoseHistory.lastOrNull()
            val poseOrDepthChanged = oldPose != null && (rotationBetween(oldPose.orientation,orientation!!) > 0.21 ||
                (oldDepth?.left != null && l != null && abs(oldDepth.left-l) > 0.30f) ||
                (oldDepth?.right != null && r != null && abs(oldDepth.right-r) > 0.30f))
            val changed = poseOrDepthChanged || (oldPose != null && oldPose.candidate != candidate)
            if (changed) {
                depthPoseHistory.clear(); history.clear()
                if (poseOrDepthChanged || candidate != null) remembered = null
            }
            depthPoseHistory.addLast(DepthPoseSample(nowMs,orientation!!,l,r,candidate))
            while (depthPoseHistory.isNotEmpty() && nowMs-depthPoseHistory.first().timeMs > 1_200)
                depthPoseHistory.removeFirst()
        } else depthPoseHistory.clear()
        val forward = boxes.filter { box ->
            val distance = box.distance.meters
            val corridorLeft = width*0.32f; val corridorRight = width*0.68f
            val intersection = (minOf(box.right,corridorRight)-maxOf(box.left,corridorLeft)).coerceAtLeast(0f)
            box.confidence >= 0.45f && box.distance.validRatio >= 0.5f &&
                distance != null && distance in 0.8f..1.8f &&
                box.bottom >= height*0.35f && intersection/(box.right-box.left).coerceAtLeast(1f) >= 0.35f
        }.minByOrNull { it.distance.meters ?: Float.POSITIVE_INFINITY }
        if (history.lastOrNull()?.candidate != candidate) history.clear()
        history.addLast(Triple(nowMs,l,c,r,candidate,forward?.classId))
        while (history.isNotEmpty() && nowMs - history.first().timeMs > 1_200) history.removeFirst()
        val poseStable = depthPoseHistory.size >= 3 &&
            nowMs-depthPoseHistory.first().timeMs >= 500
        val stable = candidate != null && history.size >= 3 && nowMs-history.first().timeMs >= 500 &&
            (!depthPoseFallback || poseStable)
        if (stable && orientation != null)
            remembered = Remembered(candidate,nowMs,orientation)
        remembered?.let { prior ->
            val side = if (prior.direction == AvoidanceDirection.LEFT) left else right
            if ((if (prior.direction == AvoidanceDirection.LEFT) leftNear else rightNear) || side.nearRatio > 0.06f || (side.clearance != null && side.clearance < 1.20f) ||
                sideBox(boxes,width,height,
                    if (prior.direction == AvoidanceDirection.LEFT) 0.06f else 0.66f,
                    if (prior.direction == AvoidanceDirection.LEFT) 0.34f else 0.94f,camera)) remembered = null
        }
        val old = remembered
        val rememberedDirection = if (!stable && candidate == null && old != null &&
            warnedObstacle && c <= 1.00f && nowMs-old.timeMs in 0..1_500 &&
            orientation != null && rotationBetween(old.orientation,orientation) <= 0.10 &&
            !sideBox(boxes,width,height,
                if (old.direction == AvoidanceDirection.LEFT) 0.06f else 0.66f,
                if (old.direction == AvoidanceDirection.LEFT) 0.34f else 0.94f,camera)) {
            val side = if (old.direction == AvoidanceDirection.LEFT) left else right
            if (!(if (old.direction == AvoidanceDirection.LEFT) leftNear else rightNear) &&
                side.validRatio < 0.65f && side.nearRatio <= 0.06f &&
                (side.clearance == null || side.clearance >= 1.20f)) old.direction else null
        } else null
        val selected = if (stable) candidate else rememberedDirection
        val fromMemory = selected != null && !stable
        val earlyCaution = stable && forward != null && c in 0.8f..1.8f &&
            history.takeLast(3).all { it.forwardClassId == forward.classId }
        val riskDirection = selected != null && riskEvent != null && !directionSent
        val directionReady = selected != null && !directionSent && riskEvent == null &&
            ((warnedObstacle && nowMs-warnedMs in 0..3_000 && c <= 1.80f) || earlyCaution)
        val guidanceLevel = if (warnedObstacle) warnedLevel else if (earlyCaution) RiskLevel.CAUTION else null
        if (directionReady || riskDirection) {
            directionSent = true
            if (earlyCaution && !warnedObstacle) {
                warnedObstacle = true; warnedMs = nowMs; warnedLevel = RiskLevel.CAUTION
            }
        }
        val centerClear = center.clearCenter && !unknownPresent &&
            boxes.none { box -> box.confidence >= 0.45f &&
                (box.distance.meters == null || box.distance.meters <= 1.8f) &&
                box.left < width*0.66f && box.right > width*0.34f }
        if (visualReliable && warnedObstacle && riskEvent == null && nowMs-warnedMs >= 750 && centerClear) {
            if (clearSinceMs == 0L) clearSinceMs = nowMs
        } else clearSinceMs = 0L
        val cleared = warnedObstacle && clearSinceMs > 0 && nowMs-clearSinceMs >= 1_000
        if (cleared) {
            warnedObstacle = false; clearSinceMs = 0L; directionSent = false; warnedLevel = null
        }
        val reason = when {
            cleared -> "previous_obstacle_left_view"
            fromMemory -> "remembered_${selected!!.name.lowercase()}"
            stable && depthPoseFallback -> "stable_depth_pose_${candidate!!.name.lowercase()}"
            stable -> "stable_${candidate!!.name.lowercase()}"
            candidate != null -> "collecting_history"
            c >= 1.80f -> "center_not_near"
            left.usableSide && right.usableSide && l != null && r != null &&
                abs(l-r) < 0.60f -> "sides_similar"
            !left.usableSide && !right.usableSide -> "both_sides_near"
            else -> "no_clear_direction"
        }
        latest = AvoidanceSnapshot(selected, reason,
            l,c,r,history.size,cleared,directionReady,
            left.validRatio,center.validRatio,right.validRatio,guidanceLevel,fromMemory,
            riskDirection,forward?.classId,if (fromMemory) old!!.timeMs+1_500 else null,
            if (camera != null && z != null) "distance_projected" else "image_fraction_fallback",z)
        return latest
    }

    private fun rotationBetween(a: DoubleArray, b: DoubleArray): Double =
        2.0 * acos(abs(a.indices.sumOf { a[it]*b[it] }).coerceIn(0.0,1.0))

    private fun lane(depth: ByteArray, width: Int, height: Int, scaleMm: Float,
        left: Float, right: Float): Lane {
        val clippedLeft = left.coerceIn(0f,1f)
        val clippedRight = right.coerceIn(0f,1f)
        val visibleRatio = (clippedRight-clippedLeft)/(right-left).coerceAtLeast(0.0001f)
        val x0 = kotlin.math.ceil((width * clippedLeft).toDouble()).toInt()
        val x1 = (width * clippedRight).toInt()
        val y0 = (height * 0.35f).toInt(); val y1 = (height * 0.70f).toInt()
        val stride = maxOf(4,width / 80)
        var total = 0; var valid = 0; var near = 0
        val meters = ArrayList<Float>()
        for (y in y0 until y1 step stride) for (x in x0 until x1 step stride) {
            total++
            val offset = (y * width + x) * 2
            val raw = (depth[offset].toInt() and 255) or ((depth[offset+1].toInt() and 255) shl 8)
            val distance = raw * scaleMm / 1_000f
            if (raw == 0 || distance !in 0.25f..4.0f) continue
            valid++; meters.add(distance)
            if (distance < 1.20f) near++
        }
        meters.sort()
        val clearance = if (meters.isEmpty()) null else meters[((meters.size-1)*0.10f).toInt()]
        return Lane(visibleRatio*valid.toFloat()/total.coerceAtLeast(1),near.toFloat()/valid.coerceAtLeast(1),clearance)
    }

    // A closer side obstacle may lie outside the angular band projected at the
    // forward obstacle's depth. Inspect its own depth before accepting history.
    private fun metricNearSide(depth: ByteArray,width: Int,height: Int,scaleMm: Float,
        camera: HorizontalProjection,left: Float,right: Float): Boolean {
        var hits = 0
        val stride = maxOf(4,width/80)
        for (y in (height*0.35f).toInt() until (height*0.70f).toInt() step stride)
            for (x in 0 until width step stride) {
                val i=(y*width+x)*2
                val raw=(depth[i].toInt() and 255) or ((depth[i+1].toInt() and 255) shl 8)
                val z=raw*scaleMm/1000f
                if (raw != 0 && z in 0.25f..1.20f && camera.lateralMeters(x.toFloat(),z) in left..right)
                    if (++hits >= 8) return true
            }
        return false
    }

    private fun sideBox(boxes: List<TargetBox>, width: Int, height: Int, left: Float, right: Float,
        projection: HorizontalProjection? = null): Boolean {
        val x0 = width*left; val x1 = width*right
        return boxes.any { box ->
            val distance = box.distance.meters
            box.confidence >= 0.45f && distance != null && distance.isFinite() &&
                distance >= 0.25f && distance <= (if (projection != null) 2.15f else 1.5f) &&
                box.bottom >= height*0.55f &&
                if (projection != null) {
                    val a = if (left < 0.5f) -0.95f else 0.32f
                    val b = if (left < 0.5f) -0.32f else 0.95f
                    (projection.overlapMeters(box,a,b) ?: 0f) >= (b-a)*0.20f
                } else (minOf(box.right,x1)-maxOf(box.left,x0)).coerceAtLeast(0f) >= (x1-x0)*0.20f
        }
    }
}
