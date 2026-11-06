package com.helios.app

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

data class NavigationConfig(
    val cautionMeters: Float = 2.0f,
    val warningMeters: Float = 1.0f,
    val urgentMeters: Float = 0.5f,
    val cautionRepeatMs: Long = 8_000,
    val warningRepeatMs: Long = 4_000,
    val urgentRepeatMs: Long = 2_000,
    val clearRepeatMs: Long = 3_000,
    val clearMeters: Float = 2.3f,
    val clearHoldMs: Long = 1_000,
    val turnClearHoldMs: Long = 1_000,
    // Field-test preference: keep missing depth visible, without an uncertainty voice loop.
    val announceDepthUncertainty: Boolean = false,
    // User-requested field-test assumption; missing depth is not proof of physical clearance.
    val assumeMissingForwardClear: Boolean = false,
    val closerMeters: Float = 0.20f,
    val forwardWidthMeters: Float = HorizontalProjection.DEFAULT_FORWARD_WIDTH_METERS,
) {
    init {
        require(forwardWidthMeters.isFinite() && forwardWidthMeters in 0.2f..1.5f)
        require(urgentMeters >= 0.25f && urgentMeters < warningMeters && warningMeters < cautionMeters)
        require(cautionMeters < clearMeters && clearMeters <= 4f)
        require(listOf(cautionRepeatMs,warningRepeatMs,urgentRepeatMs,clearRepeatMs,clearHoldMs,turnClearHoldMs).all { it > 0 })
    }
    fun distancePriority(meters: Float) = when {
        meters<=urgentMeters -> 3
        meters<=warningMeters -> 2
        meters<=cautionMeters -> 1
        else -> 0
    }
    fun repeatMs(level: RiskLevel) = when (level) {
        RiskLevel.CAUTION -> cautionRepeatMs
        RiskLevel.WARNING -> warningRepeatMs
        RiskLevel.URGENT -> urgentRepeatMs
    }
}

data class NavigationValidity(val timeMs: Long = 0, val available: Boolean = false,
    val episodeId: Long? = null, val level: RiskLevel? = null,
    val direction: AvoidanceDirection? = null, val clearedId: Long? = null,
    val reobserveDirection: AvoidanceDirection? = null, val evidenceRevision: Long? = null,
    val heightBand:ImageHeightBand?=null, val spaceHint:SpatialHint?=null,
    val forwardClear:Boolean?=null)

/** Live evidence, checked again when a queued sentence is actually about to start. */
object NavigationStateStore {
    @Volatile var current = NavigationValidity()
        private set
    fun update(value: NavigationValidity) { current = value }
    fun valid(key: String, nowMs: Long, direction: AvoidanceDirection? = null, checkDirection: Boolean = false,band:ImageHeightBand?=null,space:SpatialHint?=null): Boolean {
        val parts = key.split(':')
        if (parts.size != 7 || parts.take(2) != listOf("risk","nav")) return false
        val state = current
        if (!state.available || nowMs-state.timeMs !in 0..700) return false
        val episode = parts[2].toLongOrNull() ?: return false
        if(state.evidenceRevision!=null && parts[6]!="h${state.evidenceRevision}") return false
        if (parts[3] in setOf(RiskEventKind.AREA_CLEARED.name,RiskEventKind.HEADING_CLEAR.name))
            return state.episodeId == null && state.clearedId == episode && state.forwardClear!=false
        if(band!=null && state.heightBand!=null && band!=state.heightBand) return false
        if(space!=null && space!=state.spaceHint) return false
        val expected=if(parts[3] in setOf(RiskEventKind.REOBSERVE.name,RiskEventKind.TURN_GUIDANCE.name)) state.reobserveDirection else state.direction
        return state.episodeId == episode && state.level?.name == parts[4] &&
            (parts[3]!=RiskEventKind.REOBSERVE.name || expected!=null) &&
            ((direction == null && !checkDirection) || direction == expected)
    }
}

data class NavigationEvaluation(val reason: String, val event: LocalRiskEvent? = null,
    val nearestMeters: Float? = null, val episodeId: Long? = null,
    val level: RiskLevel? = null, val guidance: AvoidanceSnapshot = AvoidanceSnapshot(),
    val unknownMeters: Float? = null,
    val heightBand: ImageHeightBand = ImageHeightBand.FRONT, val heightLayersStatus: String = "legacy")

/** One obstacle episode owns proximity, direction, repeats, escalation and departure. */
class ObstacleNavigation(private val config: NavigationConfig = NavigationConfig(),
    private val useCorridorPlanner: Boolean = false,
    private val useImageHeightLayers: Boolean = false) {
    private data class Observation(val box: TargetBox, val meters: Float,
        val band: ImageHeightBand = ImageHeightBand.FRONT)
    private data class Sample(val timeMs: Long, val observation: Observation)
    private class Episode(val id: Long, var observation: Observation, var seenMs: Long) {
        var level = RiskLevel.CAUTION
        var emittedLevel: RiskLevel? = null
        var emittedMs = Long.MIN_VALUE
        var emittedDirection: AvoidanceDirection? = null
        var emittedReobserve: AvoidanceDirection? = null
        var reobserveMs = Long.MIN_VALUE
    }
    private val turning = TurnNavigation(config)
    private val samples = ArrayDeque<Sample>()
    private val directions = AvoidanceDirectionEvaluator()
    private val corridors = CorridorDirectionEvaluator(LocalCorridorPlanner(highObstacleMeters=config.cautionMeters))
    private var episode: Episode? = null
    private var clearedId: Long? = null
    private var lastSampleMs = Long.MIN_VALUE
    private var nextEventId = 1L
    private var clearSinceMs = 0L
    private var clearPose: DoubleArray? = null
    private var lastEpisodeId = 0L
    private var lastGuidance = AvoidanceSnapshot()
    private var lastHeightBand=ImageHeightBand.FRONT
    private var lastHeightLayersStatus="三段待新鲜深度"

    fun requestSpeechRefresh() { turning.requestSpeechRefresh() }

    fun reset() {
        turning.reset()
        samples.clear(); directions.reset(); corridors.reset(); episode = null; clearedId = null
        lastSampleMs = Long.MIN_VALUE; clearSinceMs = 0; clearPose = null
        lastGuidance = AvoidanceSnapshot()
        lastHeightBand=ImageHeightBand.FRONT;lastHeightLayersStatus="三段待新鲜深度"
        NavigationStateStore.update(NavigationValidity())
    }

    fun evaluate(nowMs: Long, depth: ByteArray, width: Int, height: Int, scaleMm: Float,
        boxes: List<TargetBox>, motion: MotionSnapshot, visual: VisualMotionEvidence,
        active: Boolean, sensorsFresh: Boolean, pose: DoubleArray?,
        projection: HorizontalProjection? = null): NavigationEvaluation {
        if(useCorridorPlanner && useImageHeightLayers) return turning.evaluate(nowMs,depth,width,height,scaleMm,boxes,
            motion,visual,active,sensorsFresh,pose,projection)
        if (!active) { reset(); return NavigationEvaluation("session_inactive") }
        if (!sensorsFresh || !motion.calibrated || motion.stale ||
            motion.state !in setOf(MotionState.MOVING,MotionState.STILL) ||
            width < 80 || height < 80 || depth.size.toLong() < width.toLong()*height*2 ||
            !scaleMm.isFinite() || scaleMm <= 0f) {
            samples.clear(); directions.reset(); corridors.reset(); clearSinceMs = 0; clearPose = null
            lastGuidance = AvoidanceSnapshot(reason="motion_or_depth_unavailable")
            NavigationStateStore.update(NavigationValidity(nowMs))
            return NavigationEvaluation("motion_or_depth_unavailable",episodeId=episode?.id)
        }
        if (lastSampleMs != Long.MIN_VALUE && nowMs-lastSampleMs in 0..149)
            return NavigationEvaluation("sample_interval",nearestMeters=episode?.observation?.meters,
                episodeId=episode?.id,level=episode?.level,guidance=lastGuidance.copy(
                    directionReady=false,obstacleCleared=false,attachDirectionToRisk=false),
                heightBand=lastHeightBand,heightLayersStatus=lastHeightLayersStatus)
        if (lastSampleMs != Long.MIN_VALUE && nowMs-lastSampleMs !in 0..800) {
            samples.clear(); directions.reset(); corridors.reset(); clearSinceMs = 0; clearPose = null
        }
        lastSampleMs = nowMs
        val camera = projection?.takeIf { it.matches(width,height) }?.copy(forwardWidthMeters=config.forwardWidthMeters)
        val scene=if (useImageHeightLayers && camera != null) ImageHeightScene(depth,width,height,scaleMm,camera) else null
        val layerKnown=scene?.let { view -> ImageHeightBand.entries.flatMap { band -> boxes.mapNotNull { view.known(it,band) } } }.orEmpty()
        val layerUnknown=scene?.let { view -> ImageHeightBand.entries.mapNotNull { view.unknown(it,config.cautionMeters+0.2f) } }.orEmpty()
        // Metre-wide speech requires valid intrinsics; never fall back to a pixel fraction.
        val layeredCandidates=(layerKnown+layerUnknown).filter { it.meters<=config.cautionMeters }
        val highestPriority=layeredCandidates.maxOfOrNull { config.distancePriority(it.meters) }
        val priorityCandidates=layeredCandidates.filter { config.distancePriority(it.meters)==highestPriority }
        val speechBand=if (priorityCandidates.any { it.band==ImageHeightBand.FRONT }) ImageHeightBand.FRONT
            else priorityCandidates.minByOrNull { it.meters }?.band
        val speechKnown=layerKnown.filter { it.band==speechBand && config.distancePriority(it.meters)==highestPriority }
        val speechUnknown=layerUnknown.filter { it.band==speechBand && config.distancePriority(it.meters)==highestPriority }
        val unknown = if (!useImageHeightLayers) UnknownObstacleDetector.detect(depth,width,height,scaleMm,config.cautionMeters+0.2f,camera)
            else speechUnknown.minByOrNull { it.meters }?.let { UnknownObstacleObservation(it.meters,1) }
        val forward = boxes.filter { forward(it,width,height,camera) && reliable(it) }
        val known = if (useImageHeightLayers) speechKnown.minByOrNull { it.meters }?.let { Observation(it.box,it.meters,it.band) }
            else forward.minByOrNull { it.distance.meters!! }?.let { Observation(it,it.distance.meters!!) }
        // Distance severity wins across layers and classes; middle wins only within that severity.
        val observation = if (useImageHeightLayers) {
            val independent=speechUnknown.minByOrNull { it.meters }
            if (independent != null && (known == null || independent.meters+0.25f<known.meters))
                Observation(independent.box,independent.meters,independent.band) else known
        } else if (unknown != null && (known == null || unknown.meters+0.25f < known.meters)) {
            Observation(TargetBox(-1,1f,width*0.38f,height*0.35f,width*0.62f,height*0.70f,
                DepthEstimate(unknown.meters,1f,"连续深度面")),unknown.meters)
        } else known
        val layerStatus=if (scene == null) "三段：中央检测范围内参不可用，暂停障碍播报" else
            "上/中/下各1/3 · 上方%s · 前方%s · 地面%s".format(
                (layerKnown+layerUnknown).filter { it.band==ImageHeightBand.HIGH }.minOfOrNull { it.meters }?.let { "%.2fm".format(it) } ?: "无近表面",
                (layerKnown+layerUnknown).filter { it.band==ImageHeightBand.FRONT }.minOfOrNull { it.meters }?.let { "%.2fm".format(it) } ?: "无近表面",
                if (scene.ground == null) "基准未知" else (layerKnown+layerUnknown).filter { it.band==ImageHeightBand.GROUND }.minOfOrNull { it.meters }?.let { "异常%.2fm".format(it) } ?: "已观测平面")
        lastHeightBand=observation?.band ?: episode?.observation?.band ?: ImageHeightBand.FRONT
        lastHeightLayersStatus=layerStatus
        if (observation != null && observation.meters <= config.cautionMeters) clearedId = null
        if (observation != null) {
            val previous = samples.lastOrNull()
            if (previous != null && (nowMs-previous.timeMs > 800 ||
                !sameObstacle(previous.observation,observation,width))) samples.clear()
            samples.addLast(Sample(nowMs,observation))
            while (samples.isNotEmpty() && nowMs-samples.first().timeMs > 1_200) samples.removeFirst()
            fun zoneConfirmed(limit: Float) = samples.size >= 2 &&
                samples.takeLast(2).all { it.observation.meters <= limit } &&
                nowMs-samples.takeLast(2).first().timeMs >= 150
            val needs = if (zoneConfirmed(config.warningMeters)) 2 else 3
            val hold = if (needs == 2) 150L else 400L
            val confirmed = samples.size >= needs && nowMs-samples.first().timeMs >= hold
            val old = episode
            if (confirmed && observation.meters <= config.cautionMeters &&
                (old == null || !sameObstacle(old.observation,observation,width) || nowMs-old.seenMs > 2_000)) {
                lastEpisodeId = maxOf(lastEpisodeId+1,nowMs)
                episode = Episode(lastEpisodeId,observation,nowMs)
                clearedId = null
                // The first warning can use space observed while the obstacle was
                // being confirmed. A different obstacle starts a new history.
                if (old != null) { directions.reset(); corridors.reset() }
                clearSinceMs = 0; clearPose = null
            }
            val current = episode
            if (current != null && sameObstacle(current.observation,observation,width)) {
                current.observation = observation; current.seenMs = nowMs
                // Entry uses two independent observations, including an upgrade.
                if (confirmed) current.level = when {
                    observation.meters <= config.urgentMeters && zoneConfirmed(config.urgentMeters) -> RiskLevel.URGENT
                    current.level == RiskLevel.URGENT && observation.meters <= config.urgentMeters+0.15f -> RiskLevel.URGENT
                    observation.meters <= config.warningMeters && zoneConfirmed(config.warningMeters) -> RiskLevel.WARNING
                    current.level == RiskLevel.WARNING && observation.meters <= config.warningMeters+0.15f -> RiskLevel.WARNING
                    else -> RiskLevel.CAUTION
                }
            }
        } else samples.clear()
        val current = episode
        val context = current?.let { event(it,nowMs,RiskEventKind.OBSTACLE,null,issue=false) }
        val guidance = (if (useCorridorPlanner)
            corridors.evaluate(nowMs,depth,width,height,scaleMm,camera,observation?.meters,motion,pose,scene)
            else directions.evaluate(nowMs,depth,width,height,scaleMm,boxes,motion,visual,
                true,true,context,unknown != null,pose,camera,observation?.meters)).also { lastGuidance = it }
        val turning = motion.rotationRate > 0.55 || motion.tiltRotationRate > 0.50
        val direction = guidance.direction.takeUnless { turning || observation == null }
        val reobserve=guidance.reobserveDirection.takeUnless { turning || observation==null || direction!=null }
        if (useCorridorPlanner && direction == null) current?.emittedDirection = null
        if(reobserve==null) current?.emittedReobserve=null
        val clear = current != null && !turning && (!useImageHeightLayers || scene != null) &&
            (if (scene != null) layerKnown.none { it.meters<config.clearMeters } &&
                (current.observation.band!=ImageHeightBand.GROUND || scene.ground != null)
                else boxes.none { it.confidence >= 0.45f && forward(it,width,height,camera) &&
                    (it.distance.meters == null || it.distance.meters!! < config.clearMeters) }) &&
            (if (scene != null) layerUnknown.isEmpty() else unknown == null) && centralClear(depth,width,height,scaleMm,camera,scene) &&
            nowMs-visual.timeMs in 0..600 &&
            !(visual.kind == VisualMotionKind.GLOBAL_SHIFT && (abs(visual.shiftX) >= 2 || visual.shiftPixels >= 3))
        val normalized = normalized(pose)
        if (clear && normalized != null) {
            if (clearSinceMs == 0L || clearPose == null || angle(clearPose!!,normalized) > 0.10) {
                clearSinceMs = nowMs; clearPose = normalized
            }
        } else { clearSinceMs = 0; clearPose = null }
        if (current != null && clearSinceMs != 0L && nowMs-clearSinceMs >= config.clearHoldMs) {
            val departure = event(current,nowMs,RiskEventKind.AREA_CLEARED,null)
            episode = null; clearedId = current.id; samples.clear(); directions.reset(); corridors.reset()
            clearSinceMs = 0; clearPose = null
            NavigationStateStore.update(NavigationValidity(nowMs,true,clearedId=clearedId))
            return NavigationEvaluation("area_cleared",departure,episodeId=current.id,guidance=guidance,heightBand=current.observation.band,heightLayersStatus=layerStatus)
        }
        val zoneCompatible = current == null || observation == null || when (current.level) {
            RiskLevel.URGENT -> observation.meters <= config.urgentMeters+0.15f
            RiskLevel.WARNING -> observation.meters <= config.warningMeters+0.15f
            RiskLevel.CAUTION -> observation.meters <= config.cautionMeters+0.15f
        }
        NavigationStateStore.update(NavigationValidity(nowMs,
            current == null || (observation != null && nowMs-current.seenMs <= 300 &&
                sameObstacle(current.observation,observation,width) && zoneCompatible),
            current?.id,current?.level,direction,clearedId,reobserve))
        if (current == null) return NavigationEvaluation(if (observation == null) "no_confirmed_obstacle" else "confirming_obstacle",
            nearestMeters=observation?.meters,guidance=guidance,unknownMeters=unknown?.meters,
            heightBand=observation?.band ?: ImageHeightBand.FRONT,heightLayersStatus=layerStatus)
        if (!zoneCompatible) return NavigationEvaluation("confirming_zone_transition",
            nearestMeters=observation?.meters,episodeId=current.id,level=current.level,
            guidance=guidance,unknownMeters=unknown?.meters,
                heightBand=observation?.band ?: current.observation.band,heightLayersStatus=layerStatus)
        // Never repeat a cached obstacle while its live measurements are missing.
        if (observation == null || nowMs-current.seenMs > 300 ||
            !sameObstacle(current.observation,observation,width) || observation.meters > config.cautionMeters+0.15f)
            return NavigationEvaluation("awaiting_current_evidence",nearestMeters=observation?.meters,
                episodeId=current.id,level=current.level,guidance=guidance,unknownMeters=unknown?.meters,
                heightBand=observation?.band ?: current.observation.band,heightLayersStatus=layerStatus)
        val changedLevel = current.emittedLevel != current.level
        val newDirection = direction != null && direction != current.emittedDirection &&
            (current.emittedDirection == null || nowMs-current.emittedMs >= 2_000)
        val newReobserve=reobserve!=null && reobserve!=current.emittedReobserve &&
            (current.emittedMs==Long.MIN_VALUE || nowMs-current.emittedMs>=2_000) &&
            (current.reobserveMs==Long.MIN_VALUE || nowMs-current.reobserveMs>=config.repeatMs(current.level))
        val repeat = current.emittedMs != Long.MIN_VALUE && nowMs-current.emittedMs >= config.repeatMs(current.level)
        val speech = if (changedLevel || newDirection || newReobserve || repeat) {
            val kind = if(reobserve!=null) RiskEventKind.REOBSERVE else
                if (!changedLevel && newDirection && !repeat) RiskEventKind.DIRECTION else RiskEventKind.OBSTACLE
            event(current,nowMs,kind,direction ?: reobserve,remembered=guidance.remembered,
                evidenceExpiresMs=guidance.directionEvidenceExpiresMs).also {
                current.emittedLevel = current.level
                if (direction != null) current.emittedDirection = direction
                if(reobserve!=null) { current.emittedReobserve=reobserve;current.reobserveMs=nowMs }
                // REOBSERVE includes the proximity warning itself; its first
                // emission must also restart that warning's repeat clock.
                if (kind == RiskEventKind.OBSTACLE || kind == RiskEventKind.REOBSERVE || changedLevel || repeat)
                    current.emittedMs = nowMs
            }
        } else null
        return NavigationEvaluation(if (speech != null) "emit_${current.level.name.lowercase()}" else "episode_active",
            speech,observation.meters,current.id,current.level,guidance,unknown?.meters,
            observation.band,layerStatus)
    }

    private fun event(episode: Episode, nowMs: Long, kind: RiskEventKind, direction: AvoidanceDirection?,
        issue: Boolean = true, remembered: Boolean = false, evidenceExpiresMs: Long? = null): LocalRiskEvent {
        val id = if (issue) nextEventId++ else 0
        return LocalRiskEvent(id,"risk:nav:${episode.id}:${kind.name}:${episode.level.name}:$id:live",
            episode.level,episode.observation.box.classId,
            if (kind == RiskEventKind.AREA_CLEARED) config.clearMeters else episode.observation.meters,
            0f,null,nowMs,if (remembered) minOf(nowMs+700,evidenceExpiresMs ?: nowMs+700) else nowMs+1_500,
            kind,direction,remembered,observationOnly=useCorridorPlanner && direction != null,heightBand=episode.observation.band)
    }

    private fun reliable(box: TargetBox) = box.confidence >= 0.45f && box.distance.validRatio >= 0.5f &&
        box.distance.meters?.let { it.isFinite() && it in 0.25f..4f } == true
    private fun forward(box: TargetBox, width: Int, height: Int, camera: HorizontalProjection? = null): Boolean {
        val metricOverlap = camera?.overlapMeters(box,-camera.halfForwardMeters,
            camera.halfForwardMeters)
        if (metricOverlap != null) return box.bottom >= height*0.35f &&
            box.bottom-box.top >= height*0.04f && metricOverlap >= 0.04f
        val overlap = (minOf(box.right,width*0.68f)-maxOf(box.left,width*0.32f)).coerceAtLeast(0f)
        return box.bottom >= height*0.35f && box.right-box.left >= width*0.025f &&
            box.bottom-box.top >= height*0.04f && overlap/(box.right-box.left).coerceAtLeast(1f) >= 0.35f
    }
    private fun sameObstacle(a: Observation, b: Observation, width: Int): Boolean {
        if (a.band!=b.band || abs(a.meters-b.meters) > 0.8f) return false
        val ca = a.box.classId; val cb = b.box.classId
        if (ca != cb && ca >= 0 && cb >= 0) return false
        val intersection = (minOf(a.box.right,b.box.right)-maxOf(a.box.left,b.box.left)).coerceAtLeast(0f)*
            (minOf(a.box.bottom,b.box.bottom)-maxOf(a.box.top,b.box.top)).coerceAtLeast(0f)
        val areaA = (a.box.right-a.box.left)*(a.box.bottom-a.box.top)
        val areaB = (b.box.right-b.box.left)*(b.box.bottom-b.box.top)
        return intersection/(areaA+areaB-intersection).coerceAtLeast(1f) >= 0.25f ||
            ((ca < 0 || cb < 0) && abs((a.box.left+a.box.right-b.box.left-b.box.right)/2) <= width*0.12f)
    }
    private fun centralClear(bytes: ByteArray, width: Int, height: Int, scaleMm: Float,
        camera: HorizontalProjection? = null,scene: ImageHeightScene? = null): Boolean {
        val halfWidth=camera?.halfForwardMeters ?: config.forwardWidthMeters/2f
        val x0=camera?.pixel(-halfWidth,config.clearMeters)?.toInt()?.coerceIn(0,width-1) ?: (width*0.32f).toInt()
        val x1=camera?.pixel(halfWidth,config.clearMeters)?.toInt()?.coerceIn(1,width) ?: (width*0.68f).toInt()
        var total=0; var valid=0; var near=0
        val distances=ArrayList<Float>()
        for (y in (height*(if (scene!=null) 0f else 0.35f)).toInt() until (height*(if (scene!=null) 2f/3f else 0.70f)).toInt() step maxOf(4,width/80))
            for (x in x0 until x1 step maxOf(4,width/80)) {
                total++
                val i=(y*width+x)*2
                val raw=(bytes[i].toInt() and 255) or ((bytes[i+1].toInt() and 255) shl 8)
                val meters=raw*scaleMm/1_000f
                if (raw == 0 || meters !in 0.25f..6f) continue
                valid++; distances.add(meters)
                if (meters <= config.cautionMeters) near++
            }
        if (camera != null) {
            for (y in (height*(if (scene!=null) 0f else 0.35f)).toInt() until (height*(if (scene!=null) 2f/3f else 0.70f)).toInt() step maxOf(4,width/80))
                for (x in 0 until width step maxOf(4,width/80)) {
                    val i=(y*width+x)*2
                    val raw=(bytes[i].toInt() and 255) or ((bytes[i+1].toInt() and 255) shl 8)
                    val z=raw*scaleMm/1000f
                    if (raw != 0 && z in 0.25f..config.cautionMeters &&
                        abs(camera.lateralMeters(x.toFloat(),z)) <= halfWidth) return false
                }
        }
        if (valid == 0 || valid.toFloat()/total < 0.65f || near.toFloat()/valid > 0.02f) return false
        distances.sort()
        return distances[(distances.size/10).coerceAtMost(distances.lastIndex)] >= config.clearMeters
    }
    private fun normalized(pose: DoubleArray?): DoubleArray? {
        if (pose == null || pose.size != 4 || !pose.all(Double::isFinite)) return null
        val norm=sqrt(pose.sumOf { it*it })
        return if (norm in 0.9..1.1) DoubleArray(4) { pose[it]/norm } else null
    }
    private fun angle(a: DoubleArray,b: DoubleArray) = 2*acos(abs(a.indices.sumOf { a[it]*b[it] }).coerceIn(0.0,1.0))
}
