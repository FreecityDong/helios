package com.helios.app

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/** Continuous current-scene risk and side-space monitoring; no user turn task. */
class TurnNavigation(private val config: NavigationConfig) {
    private var state=TurnState.MONITOR
    private var episodeId: Long?=null
    private var clearedId: Long?=null
    private var serial=0L
    private var eventId=0L
    private val spaceStability=SpatialHintStability()
    private var emittedSpace:SpatialHint?=null
    private var refreshSpeech=false
    fun requestSpeechRefresh() { refreshSpeech=true }
    private var lastMs=Long.MIN_VALUE
    private var emittedMs=Long.MIN_VALUE
    private var emittedLevel: RiskLevel?=null
    private var emittedDirection: AvoidanceDirection?=null
    private var emittedMeters: Float?=null
    private val announcedBands = mutableSetOf<ImageHeightBand>()
    private var level=RiskLevel.CAUTION
    private var target: ForwardLayerSurface?=null
    private val samples=ArrayDeque<Pair<Long,ForwardLayerSurface>>()
    private var candidate: AvoidanceDirection?=null
    private var candidateSince=0L
    private var candidateCount=0
    private var selected: AvoidanceDirection?=null
    private var clearSince: Long?=null
    private var clearanceConfirmed=false
    private var missingSince: Long?=null
    private var missingNotified=false
    private var hadNear=false
    private var revision=0L
    private var heading: DoubleArray?=null
    private var accumulatedTurn=0.0
    private var latest=NavigationEvaluation("turn_monitor")
    fun reset() {
        state=TurnState.MONITOR;episodeId=null;clearedId=null;lastMs=Long.MIN_VALUE;emittedMs=Long.MIN_VALUE
        emittedLevel=null;emittedDirection=null;emittedMeters=null;announcedBands.clear();target=null;samples.clear();selected=null;candidate=null
        candidateSince=0;candidateCount=0;clearSince=null;clearanceConfirmed=false;missingSince=null;missingNotified=false;hadNear=false;heading=null;accumulatedTurn=0.0;revision++
        spaceStability.reset();emittedSpace=null;refreshSpeech=false
        latest=NavigationEvaluation("turn_monitor");NavigationStateStore.update(NavigationValidity())
    }
    fun evaluate(now: Long,bytes: ByteArray,w: Int,h: Int,scale: Float,boxes: List<TargetBox>,
        motion: MotionSnapshot,visual: VisualMotionEvidence,active: Boolean,fresh: Boolean,
        pose: DoubleArray?,camera: HorizontalProjection?): NavigationEvaluation {
        if(!active) {reset();return latest.copy(reason="session_inactive")}
        if(!fresh || !motion.calibrated || motion.stale || motion.state !in setOf(MotionState.STILL,MotionState.MOVING) ||
            camera?.matches(w,h)!=true || w<80 || h<80 || bytes.size.toLong()<w.toLong()*h*2 || !scale.isFinite() || scale<=0f) {
            clearSince=null;clearanceConfirmed=false;samples.clear();selected=null;candidate=null;candidateCount=0;spaceStability.reset();revision++
            NavigationStateStore.update(NavigationValidity(now))
            return latest.copy(reason="turn_data_unavailable",event=null,guidance=AvoidanceSnapshot(geometryMode="turn_priority",turnState=state))
        }
        // Invalidate old-heading speech even on frames skipped by the evaluation interval.
        val norm=pose?.takeIf { it.size==4 && it.all(Double::isFinite) }?.let {sqrt(it.sumOf {v->v*v})}
        val q=if(norm!=null && norm in 0.9..1.1) DoubleArray(4) {pose[it]/norm} else null
        if(q!=null) {
            val changed=heading?.let { a->2*acos(abs(a.indices.sumOf {a[it]*q[it]}).coerceIn(0.0,1.0))>0.12 }==true
            if(changed) {revision++;NavigationStateStore.update(NavigationValidity(now))}
            if(heading==null || changed) heading=q
        } else if(lastMs!=Long.MIN_VALUE) {
            accumulatedTurn+=motion.rotationRate*(now-lastMs).coerceIn(0,150)/1000.0
            if(accumulatedTurn>0.12) {revision++;accumulatedTurn=0.0;NavigationStateStore.update(NavigationValidity(now))}
        }
        if(lastMs!=Long.MIN_VALUE && now-lastMs in 0..149) return latest.copy(reason="sample_interval",event=null)
        if(lastMs!=Long.MIN_VALUE && now-lastMs !in 0..800) {clearSince=null;clearanceConfirmed=false;samples.clear();selected=null;candidate=null;candidateCount=0;spaceStability.reset();revision++}
        lastMs=now
        val scene=ImageHeightScene(bytes,w,h,scale,camera.copy(forwardWidthMeters=config.forwardWidthMeters))
        val surfaces=ImageHeightBand.entries.flatMap { band ->
            listOfNotNull(scene.unknown(band,config.clearMeters))+boxes.mapNotNull {scene.known(it,band)}
        }
        val near=surfaces.filter {it.meters<=config.cautionMeters}
        val priority=near.maxOfOrNull {config.distancePriority(it.meters)}
        val best=near.filter {config.distancePriority(it.meters)==priority}.let { list ->
            val middle=list.filter {it.band==ImageHeightBand.FRONT}
            (middle.ifEmpty {list}).minWithOrNull(compareBy<ForwardLayerSurface> {it.meters}.thenBy {if(it.box.classId>=0) 0 else 1})
        }
        val centralCoverage=TurnSpace.centralCoverage(scene,config.clearMeters)
        val layerText=(if(config.assumeMissingForwardClear) "测试：缺测暂按可通行 · " else "")+ImageHeightBand.entries.joinToString(" · ") { band ->
            band.label+(surfaces.filter {it.band==band}.minOfOrNull {it.meters}?.let {"%.2fm".format(it)} ?: "未见近物")+
                "（覆盖%.0f%%）".format(centralCoverage[band.ordinal]*100)
        }
        if(best!=null) {
            clearedId=null
            if(samples.lastOrNull()?.let {it.second.band!=best.band || abs(it.second.meters-best.meters)>0.8f}==true) samples.clear()
            samples.addLast(now to best);while(samples.isNotEmpty() && now-samples.first().first>1200) samples.removeFirst()
        } else samples.clear()
        val zone=if(best==null) false else if(best.meters<=config.warningMeters)
            samples.size>=2 && now-samples.takeLast(2).first().first>=150 && samples.takeLast(2).all {it.second.meters<=config.warningMeters}
            else samples.size>=3 && now-samples.first().first>=400
        if(best!=null && zone) {
            if(episodeId==null) {serial=maxOf(serial+1,now);episodeId=serial;state=TurnState.BLOCKED;emittedMs=Long.MIN_VALUE;emittedLevel=null;announcedBands.clear();emittedMeters=null;emittedSpace=null}
            target=best
            val urgent=samples.size>=2 && samples.takeLast(2).all {it.second.meters<=config.urgentMeters}
            level=when {
                urgent -> RiskLevel.URGENT
                level==RiskLevel.URGENT && best.meters<=config.urgentMeters+0.15f -> RiskLevel.URGENT
                best.meters<=config.warningMeters -> RiskLevel.WARNING
                level==RiskLevel.WARNING && best.meters<=config.warningMeters+0.15f -> RiskLevel.WARNING
                else -> RiskLevel.CAUTION
            }
        }
        val noNear=best==null
        if(noNear && hadNear) {revision++;selected=null;candidate=null;candidateCount=0}
        hadNear=!noNear
        val missing=noNear && episodeId!=null && !TurnSpace.centralObserved(scene,config.clearMeters)
        if(missing) {if(missingSince==null) missingSince=now} else missingSince=null
        if(best!=null && zone) missingNotified=false
        // Missing or far depth never inherits an old urgent distance/repeat rate.
        if(noNear) level=RiskLevel.CAUTION
        val distance=(best?.meters ?: config.cautionMeters).coerceIn(0.25f,config.cautionMeters)
        // Side geometry is needed only for an obstacle/turning episode. Central
        // detection remains live; never reuse stale side evidence during a turn.
        val (left,right)=if(best!=null || episodeId!=null) TurnSpace.sides(scene,distance)
            else SideTurnEvidence(SideTurnStatus.UNKNOWN) to SideTurnEvidence(SideTurnStatus.UNKNOWN)
        fun evidence(d: AvoidanceDirection)=if(d==AvoidanceDirection.LEFT) left else right
        if(selected!=null && evidence(selected!!).status!=SideTurnStatus.POSSIBLE) {selected=null;revision++}
        val possible=listOf(AvoidanceDirection.LEFT,AvoidanceDirection.RIGHT).filter {evidence(it).status==SideTurnStatus.POSSIBLE}
        val next=possible.maxByOrNull {evidence(it).score}?.let { d ->
            selected?.takeIf {it in possible && evidence(d).score-evidence(it).score<5f} ?: d
        }.takeIf {best!=null && TurnMotion.guidance(motion)}
        if(next!=candidate) {candidate=next;candidateSince=now;candidateCount=0}
        if(next!=null) candidateCount++
        if(!TurnMotion.guidance(motion)) {selected=null;candidate=null;candidateCount=0;clearSince=null}
        if(next!=null && candidateCount>=3 && now-candidateSince>=500) selected=next
        val oldSpace=spaceStability.current
        val space=spaceStability.observe(if(best!=null && TurnMotion.guidance(motion))
            SpatialHintStability.classify(left.status,right.status) else SpatialHint.UNKNOWN,now)
        if(space!=oldSpace) revision++
        state=if(episodeId==null) TurnState.MONITOR else TurnState.BLOCKED
        val clearBlocker=when {
            best!=null -> "near_${best.band.name.lowercase()}"
            surfaces.any {it.meters<config.clearMeters} -> "within_release_distance"
            !TurnMotion.clear(motion) -> "turning_too_fast"
            now-visual.timeMs !in 0..600 -> "visual_stale"
            !TurnSpace.centralClear(scene,config.clearMeters,config.assumeMissingForwardClear) -> "depth_support_or_near_cluster"
            else -> "none"
        }
        val canClear=clearBlocker=="none"
        if(canClear) {
            if(clearSince==null) clearSince=now
            if(now-clearSince!!>=config.turnClearHoldMs) clearanceConfirmed=true
        } else {clearSince=null;clearanceConfirmed=false}
        val guidance=AvoidanceSnapshot(reason="current_space_${space.name.lowercase()}",
            geometryMode="turn_priority",geometryDistanceMeters=distance,reobserveDirection=selected,
            spaceHint=space,turnState=state,turnLeft=left,turnRight=right,centralCoverage=centralCoverage)
        if(episodeId!=null && clearanceConfirmed) {
            val id=episodeId!!;val event=event(now,id,RiskEventKind.HEADING_CLEAR,null,config.clearMeters,
                if(config.assumeMissingForwardClear && !TurnSpace.centralObserved(scene,config.clearMeters)) -5 else -1)
            episodeId=null;clearedId=id;target=null;state=TurnState.MONITOR;selected=null;candidate=null;samples.clear()
            NavigationStateStore.update(NavigationValidity(now,true,clearedId=id,evidenceRevision=revision,forwardClear=true))
            latest=NavigationEvaluation("heading_clear",event,guidance=guidance.copy(turnState=state,reobserveDirection=null),heightLayersStatus=layerText)
            return latest
        }
        NavigationStateStore.update(NavigationValidity(now,true,episodeId,level,clearedId=clearedId,reobserveDirection=selected,evidenceRevision=revision,heightBand=best?.band ?: target?.band,spaceHint=space,forwardClear=canClear && clearanceConfirmed))
        var speech: LocalRiskEvent?=null
        if(episodeId!=null) {
            val changed=emittedLevel!=level
            val newSide=emittedSpace!=null && emittedSpace!=space
            val repeat=emittedMs==Long.MIN_VALUE || now-emittedMs>=config.repeatMs(level)
            val revoked=emittedDirection!=null && selected==null
            val missingReminder=config.announceDepthUncertainty && missing && !missingNotified &&
                (revoked || missingSince?.let {now-it>=1000}==true)
            val closer = best != null && emittedMeters?.let { best.meters <= it - config.closerMeters } == true
            val newHeight = best != null && best.band !in announcedBands
            if((best!=null && zone && (changed || newSide || repeat || revoked || closer || newHeight || refreshSpeech)) || missingReminder) {
                speech=event(now,episodeId!!,RiskEventKind.TURN_GUIDANCE,selected,best?.meters ?: config.clearMeters,
                    if(best==null) -4 else best.box.classId,best?.band ?: ImageHeightBand.FRONT)
                if(missingReminder) missingNotified=true
                refreshSpeech=false
                emittedSpace=space
                emittedMs=now;emittedLevel=level;emittedDirection=selected;emittedMeters=best?.meters;best?.band?.let { announcedBands.add(it) }
            }
        }
        val clearStatus=if(clearBlocker=="none") {
            if(clearanceConfirmed) "forward_clear_confirmed" else "clear_confirming_${now-(clearSince ?: now)}ms"
        } else "clear_blocked_$clearBlocker"
        latest=NavigationEvaluation(if(speech!=null) "emit_${level.name.lowercase()}" else if(episodeId==null) {if(clearanceConfirmed) "forward_clear" else "turn_monitor"} else clearStatus,
            speech,best?.meters,episodeId,if(episodeId!=null) level else null,guidance,
            best?.takeIf {it.box.classId<0}?.meters,best?.band ?: target?.band ?: ImageHeightBand.FRONT,layerText+" · "+clearStatus)
        return latest
    }
    private fun event(now: Long,id: Long,kind: RiskEventKind,direction: AvoidanceDirection?,meters: Float,
        classId: Int=-1,band: ImageHeightBand=ImageHeightBand.FRONT)=LocalRiskEvent(++eventId,
        "risk:nav:$id:${kind.name}:${level.name}:$eventId:h$revision",level,classId,meters,0f,null,now,now+1500,
        kind,direction,observationOnly=true,heightBand=band,
        spaceHint=spaceStability.current.takeUnless {kind==RiskEventKind.HEADING_CLEAR},
        confirmationMs=samples.firstOrNull()?.let {now-it.first})
}
