package com.helios.app

import kotlin.math.max
import kotlin.math.min

enum class RiskLevel { CAUTION, WARNING, URGENT }
enum class RiskEventKind { OBSTACLE, DIRECTION, REOBSERVE, TURN_GUIDANCE, HEADING_CLEAR, LEFT_FORWARD_VIEW, AREA_CLEARED }

data class LocalRiskEvent(
    val id: Long,
    val key: String,
    val level: RiskLevel,
    val classId: Int,
    val distanceMeters: Float,
    val closingMetersPerSecond: Float,
    val timeToContactSeconds: Float?,
    val observedMs: Long,
    val expiresMs: Long,
    val kind: RiskEventKind = RiskEventKind.OBSTACLE,
    val avoidanceDirection: AvoidanceDirection? = null,
    val rememberedDirection: Boolean = false,
    val observationOnly: Boolean = false,
    val heightBand: ImageHeightBand = ImageHeightBand.FRONT,
    val spaceHint: SpatialHint? = null,
    val confirmationMs: Long? = null,
)

data class RiskEvaluation(val reason: String, val event: LocalRiskEvent? = null,
    val nearestMeters: Float? = null, val closingMetersPerSecond: Float = 0f)

object LocalRiskDispatchPolicy {
    fun reason(nowMs: Long, expiresMs: Long, level: RiskLevel?, key: String, meters: Float,
        session: SessionState, device: DeviceState, motion: MotionSnapshot): String = when {
        session != SessionState.ACTIVE -> "session_inactive"
        device != DeviceState.STREAMING -> "device_unavailable"
        !motion.calibrated || motion.stale || (motion.state != MotionState.MOVING &&
            !(key.startsWith("risk:nav:") && motion.state == MotionState.STILL)) -> "motion_unavailable"
        level == null || !key.startsWith("risk:") || !meters.isFinite() || meters !in 0.25f..4f -> "invalid_event"
        expiresMs <= nowMs || expiresMs-nowMs > 1_500 -> "expired"
        key.startsWith("risk:nav:") && !NavigationStateStore.valid(key,nowMs) -> "navigation_superseded"
        else -> "ready"
    }
}

/** Initial local obstacle rule. A YOLO box is never treated as free-space evidence. */
class LocalRiskEvaluator {
    private data class Sample(val timeMs: Long, val meters: Float)
    private class Track(var box: TargetBox, var seenMs: Long,
        val samples: ArrayDeque<Sample> = ArrayDeque(), var warnedLevel: RiskLevel? = null)
    private data class Candidate(val box: TargetBox, val meters: Float,
        val speed: Float, val ttc: Float?, val level: RiskLevel, val key: String, val track: Track)

    private val tracks = ArrayList<Track>()
    private val lastAlertMs = HashMap<String, Pair<Long,RiskLevel>>()
    private val lastAlertMeters = HashMap<String,Float>()
    private val lastHazardMs = HashMap<String,Long>()
    private val worsenedKeys = HashSet<String>()
    private var lastSampleMs = Long.MIN_VALUE
    private var nextId = 1L
    private var unknownPrevious: Pair<Long, UnknownObstacleObservation>? = null

    fun reset() { tracks.clear(); lastAlertMs.clear(); lastAlertMeters.clear(); lastHazardMs.clear()
        worsenedKeys.clear(); lastSampleMs = Long.MIN_VALUE; unknownPrevious = null }

    fun evaluate(nowMs: Long, boxes: List<TargetBox>, width: Int, height: Int,
        motion: MotionSnapshot, session: SessionState, sensorsFresh: Boolean,
        unknown: UnknownObstacleObservation? = null): RiskEvaluation {
        if (session != SessionState.ACTIVE) { tracks.clear(); unknownPrevious = null; return RiskEvaluation("session_inactive") }
        if (!sensorsFresh || width <= 0 || height <= 0) { tracks.clear(); unknownPrevious = null; return RiskEvaluation("sensor_unavailable") }
        if (!motion.calibrated || motion.stale || motion.state == MotionState.UNKNOWN) {
            tracks.clear(); unknownPrevious = null; return RiskEvaluation("motion_unknown")
        }
        if (motion.state != MotionState.MOVING) { tracks.clear(); unknownPrevious = null; return RiskEvaluation("not_moving") }
        if (lastSampleMs != Long.MIN_VALUE && nowMs - lastSampleMs in 0..149) return RiskEvaluation("sample_interval")
        lastSampleMs = nowMs
        tracks.removeAll { nowMs - it.seenMs > 800 || nowMs < it.seenMs }
        // Keys are bounded by the 80 model classes and three screen sectors. Retain an
        // alert across long continuous hazards; only a quiet gap rearms that key.
        lastAlertMs.entries.removeAll { nowMs < it.value.first }
        lastHazardMs.entries.removeAll { nowMs < it.value }
        if (boxes.isEmpty()) return evaluateUnknown(nowMs, unknown)
        unknownPrevious = null
        val front = boxes.filter { inForwardArea(it,width,height) }
        if (front.isEmpty()) return RiskEvaluation("outside_forward_area")
        val reliable = front.filter { b ->
            val d = b.distance.meters
            b.confidence >= 0.45f && d != null && d.isFinite() && d in 0.25f..4f &&
                b.distance.validRatio >= 0.5f
        }
        if (reliable.isEmpty()) return RiskEvaluation("depth_unreliable")
        val used = HashSet<Track>()
        val candidates = ArrayList<Candidate>()
        var nearest = Float.POSITIVE_INFINITY
        var anyReceding = false
        for (box in reliable.sortedBy { it.distance.meters }) {
            val meters = box.distance.meters!!
            nearest = min(nearest,meters)
            val track = tracks.filter { it !in used && it.box.classId == box.classId &&
                overlap(it.box,box) >= 0.25f &&
                (it.samples.lastOrNull()?.meters?.let { d -> kotlin.math.abs(d-meters) <= 0.8f } ?: true) }
                .maxByOrNull { overlap(it.box,box) } ?: Track(box,nowMs).also { tracks.add(it) }
            used.add(track)
            track.box = box; track.seenMs = nowMs
            track.samples.addLast(Sample(nowMs,meters))
            while (track.samples.isNotEmpty() && nowMs - track.samples.first().timeMs > 1_600) track.samples.removeFirst()
            val samples = track.samples.toList()
            val duration = if (samples.size >= 2) (samples.last().timeMs-samples.first().timeMs)/1000f else 0f
            val decreases = samples.zipWithNext().count { (a,b) -> a.meters-b.meters >= 0.04f }
            val speed = if (samples.size >= 3 && duration >= 0.4f && decreases >= 2) {
                (samples.first().meters-samples.last().meters)/duration
            } else 0f
            if (samples.size >= 3 && samples.last().meters-samples.first().meters >= 0.12f) anyReceding = true
            val ttc = if (speed >= 0.2f) meters/speed else null
            val level = when {
                meters <= 0.75f && samples.size >= 2 && nowMs-samples.first().timeMs >= 150 -> RiskLevel.URGENT
                meters <= 1.8f && samples.size >= 3 && speed >= 0.2f -> RiskLevel.CAUTION
                else -> null
            }
            if (level != null) {
                val sector = ((box.left+box.right)/2f/width*3f).toInt().coerceIn(0,2)
                candidates.add(Candidate(box,meters,speed,ttc,level,"risk:${box.classId}:$sector",track))
            }
        }
        val best = candidates.minWithOrNull(compareBy<Candidate> { if (it.level == RiskLevel.URGENT) 0 else 1 }
            .thenBy { it.meters }) ?: return RiskEvaluation(if (anyReceding) "receding_or_crossing" else "no_approach",
                nearestMeters=nearest)
        val continuous = lastHazardMs[best.key]?.let { nowMs - it in 0..5_000 } ?: false
        if (!continuous) worsenedKeys.remove(best.key)
        // Keep every qualifying hazard alive, including those not selected as this frame's
        // closest alert. Otherwise alternating targets can rearm each other after a gap.
        candidates.forEach { lastHazardMs[it.key] = nowMs }
        val last = lastAlertMs[best.key]
        val recentClose = best.track.samples.toList().takeLast(2).let { values ->
            values.size == 2 && values.all { it.meters <= 0.45f } &&
                kotlin.math.abs(values[0].meters-values[1].meters) <= 0.15f
        }
        if (continuous && best.level == RiskLevel.URGENT && last?.second == RiskLevel.URGENT &&
            best.meters <= 0.45f && (lastAlertMeters[best.key]?.minus(best.meters) ?: 0f) >= 0.25f &&
            recentClose && worsenedKeys.add(best.key)) {
            lastAlertMs[best.key] = nowMs to RiskLevel.URGENT
            lastAlertMeters[best.key] = best.meters
            best.track.warnedLevel = RiskLevel.URGENT
            val event = LocalRiskEvent(nextId++,"${best.key}:URGENT:WORSENED",RiskLevel.URGENT,
                best.box.classId,best.meters,best.speed,best.ttc,nowMs,nowMs+1_500)
            return RiskEvaluation("alert_worsened",event,nearest,best.speed)
        }
        if (best.track.warnedLevel == RiskLevel.URGENT || best.track.warnedLevel == best.level)
            return RiskEvaluation("already_alerted",nearestMeters=nearest,closingMetersPerSecond=best.speed)
        if (continuous && last != null && (last.second == RiskLevel.URGENT || last.second == best.level))
            return RiskEvaluation("continuous_hazard",nearestMeters=nearest,closingMetersPerSecond=best.speed)
        val cooldown = if (best.level == RiskLevel.URGENT) 4_000 else 6_000
        if (last != null && nowMs-last.first < cooldown &&
            !(last.second == RiskLevel.CAUTION && best.level == RiskLevel.URGENT))
            return RiskEvaluation("cooldown",nearestMeters=nearest,
            closingMetersPerSecond=best.speed)
        lastAlertMs[best.key] = nowMs to best.level
        lastAlertMeters[best.key] = best.meters
        best.track.warnedLevel = best.level
        val event = LocalRiskEvent(nextId++,"${best.key}:${best.level.name}",best.level,best.box.classId,best.meters,best.speed,
            best.ttc,nowMs,nowMs+1_500)
        return RiskEvaluation("alert_${best.level.name.lowercase()}",event,nearest,best.speed)
    }

    private fun evaluateUnknown(nowMs: Long, observation: UnknownObstacleObservation?): RiskEvaluation {
        if (observation == null || !observation.meters.isFinite() || observation.meters !in 0.25f..0.60f) {
            unknownPrevious = null
            return RiskEvaluation("no_target")
        }
        val previous = unknownPrevious
        unknownPrevious = nowMs to observation
        if (previous == null || nowMs - previous.first !in 150..800 ||
            previous.second.sector != observation.sector ||
            kotlin.math.abs(previous.second.meters - observation.meters) > 0.15f) {
            return RiskEvaluation("unknown_near_candidate", nearestMeters = observation.meters)
        }
        val key = "risk:unknown:${observation.sector}"
        if (lastAlertMs.any { (alertKey, stamped) ->
                !alertKey.startsWith("risk:unknown:") && alertKey.endsWith(":${observation.sector}") &&
                    nowMs - stamped.first in 0..2_000
            }) return RiskEvaluation("recent_named_target", nearestMeters = observation.meters)
        val last = lastAlertMs[key]
        val continuous = lastHazardMs[key]?.let { nowMs - it in 0..5_000 } ?: false
        lastHazardMs[key] = nowMs
        if (continuous && last != null) return RiskEvaluation("continuous_hazard", nearestMeters = observation.meters)
        if (last != null && nowMs - last.first < 4_000) return RiskEvaluation("cooldown", nearestMeters = observation.meters)
        lastAlertMs[key] = nowMs to RiskLevel.URGENT
        val event = LocalRiskEvent(nextId++,"$key:URGENT",RiskLevel.URGENT,-1,observation.meters,
            0f,null,nowMs,nowMs+1_500)
        return RiskEvaluation("alert_unknown_near", event, observation.meters)
    }

    private fun inForwardArea(box: TargetBox, width: Int, height: Int): Boolean {
        val corridorLeft = width*0.32f
        val corridorRight = width*0.68f
        val intersection = max(0f,min(box.right,corridorRight)-max(box.left,corridorLeft))
        val fraction = intersection/(box.right-box.left).coerceAtLeast(1f)
        return box.bottom >= height*0.35f && fraction >= 0.35f &&
            (box.right-box.left) >= width*0.025f && (box.bottom-box.top) >= height*0.04f
    }

    private fun overlap(a: TargetBox, b: TargetBox): Float {
        val common = max(0f,min(a.right,b.right)-max(a.left,b.left))*
            max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        val areaA = max(0f,a.right-a.left)*max(0f,a.bottom-a.top)
        val areaB = max(0f,b.right-b.left)*max(0f,b.bottom-b.top)
        return common/(areaA+areaB-common).coerceAtLeast(1f)
    }
}
