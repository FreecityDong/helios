package com.helios.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class AssistanceService : Service() {
    private lateinit var eventLog: EventLog
    private lateinit var wakeWordEngine: WakeWordEngine
    private lateinit var commandRecognizer: VoskCommandRecognizer
    private lateinit var audioRoute: AudioRouteController
    private val handler = Handler(Looper.getMainLooper())
    private val utteranceCounter = AtomicInteger()
    private val speechArbiter = SpeechArbiter()
    private val speechCallbacks = mutableMapOf<String, () -> Unit>()
    private var tts: TextToSpeech? = null
    private var toneGenerator: ToneGenerator? = null
    private var toneStream = AudioManager.STREAM_MUSIC
    private var ttsReady = false
    private var wakeReady = false
    private var commandReady = false
    private var startupAnnounced = false
    private var sensorSession: SensorSessionController? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var startupGeneration=0L
    private val startupFlow=StartupFlow()
    private var lastStartupStatus=StartupStatus()
    private var lastStartupPromptMs=0L
    private var calibrationWaitSince=0L
    private var sessionWasActivated=false
    private var finalFault:String?=null
    private var checkingFeedbackCompleted=false
    private var startupStartedMs=0L
    private var released = false
    private var confirmingExit = false
    private val wakeStart = Runnable { beginWakeListening() }
    private val wakeCallbacks = AudioInputSession()
    private val deferredUrgentWake = DeferredUrgentWake()
    private var voiceHandoffPending=false
    private var riskRefreshAfterInput=false
    private val interruptedStartup=StartupVoiceContinuation()
    private val wakeCommandStart = Runnable {
        voiceHandoffPending=false
        if(fallFlow.active) startFallListening(fromWake=true)
        else startCommandListening(exitConfirmation=confirmingExit)
    }
    private val followUpStart = Runnable { beginFollowUp() }
    private val sceneQuestions = mutableMapOf<Long,String>()
    private val pendingSceneRequest=PendingSceneRequest()
    private var manualSceneTicket:Long?=null

    private val findFlow = FindFlow()
    private val findGuidance = FindGuidance()
    private var successInfo: FindSuccessInfo? = null
    private var completionInfoView: FindSuccessInfo? = null
    private var successSpeechId: String? = null
    private var successAttemptMs = -10_000L
    private var successSpeechFailures = 0
    private var successSpeechStartedMs = 0L
    private val findClient = SceneClient()
    private val findSelection = FindSelection()
    private var findSafe = false
    private var clarifyingFind = false
    private var clarificationUntil = 0L
    private var lastFindStatus = ""
    private val sceneFlow = SceneFlow()
    private val sceneMotionGate = SceneMotionGate()
    private var riskSpeechPolicy = RiskSpeechPolicy()
    private val riskDetails=RiskDetailBuffer()
    private val pendingClearance=PendingClearance()
    private val movingClearance=MovingClearanceReminder()
    private var movingClearanceId:String?=null
    private var pendingDebugParameters:DebugParameters?=null
    private var parametersAppliedMs=0L
    private val sceneClient = SceneClient()
    private var sceneConfig = SceneConfig("", "", false, false)
    private var sceneBlockedUntil = 0L
    private var lastSceneTestRiskLog = 0L

    private fun tickScene(allowAutomatic: Boolean = true,manualAdmission:Boolean=false) {
        val now = SystemClock.elapsedRealtime()
        val state = OperationalStateStore.status.value
        if (state.motion.state != MotionState.MOVING) {
            riskSpeechPolicy.stoppedMoving()
            val removed = speechArbiter.cancelWhere { it.key.startsWith("risk:nav:") && it.riskNavigating == true && it.priority==SpeechPriority.ORDINARY }
            speechCallbacks.keys.retainAll(speechArbiter.retainedIds())
            if (removed != null) {
                tts?.stop()
                eventLog.append("navigation_speech_stopped", mapOf("id" to removed.id, "motion" to state.motion.state.name))
                val next = speechArbiter.resume(now)
                if (next != null) startSpeech(next) else startWakeListening()
            }
        }
        if (AssistanceStateStore.status.value.session != SessionState.ACTIVE || state.device != DeviceState.STREAMING)
            riskSpeechPolicy.reset()
        val strictQuestion=sceneFlow.ticket?.id in sceneQuestions
        if(manualAdmission) sceneMotionGate.reset() // A new image starts from the current still view.
        val motionReady = if (sceneConfig.testMode && !strictQuestion) { sceneMotionGate.reset(); true }
            else sceneMotionGate.update(now, state.motion, SceneFrames.pose)
        val positionViewValid=motionReady && SceneGate.ready(state,now,SceneFrames.lastFrameMs,false,0)
        completionInfoView?.observe(positionViewValid)
        checkSuccessInfo(now,positionViewValid)
        val safe = motionReady && !released && sceneConfig.enabled && AssistanceStateStore.status.value.session == SessionState.ACTIVE &&
            SceneGate.ready(state, now, SceneFrames.lastFrameMs, sceneConfig.testMode && !strictQuestion,
                if(manualAdmission || manualSceneTicket!=null && sceneFlow.ticket?.id==manualSceneTicket) 0 else sceneBlockedUntil)
        val previous = sceneFlow.ticket
        val wasPending = sceneFlow.pending
        val wasSpeaking = sceneFlow.speaking
        val automatic = sceneFlow.observe(now, safe,
            !sceneConfig.testMode && state.motion.state in setOf(MotionState.MOVING, MotionState.SHAKING),
            viewChanged = !sceneConfig.testMode && sceneMotionGate.viewChanged)
        if (sceneMotionGate.viewChanged && !sceneConfig.testMode) eventLog.append("scene_view_changed", mapOf(
            "reason" to (sceneMotionGate.viewChangeReason ?: "unknown"),
            "angleRad" to sceneMotionGate.viewDeltaRadians, "rotationRate" to state.motion.rotationRate,
            "motion" to state.motion.state.name, "gyroBias" to state.motion.gyroBias))
        sceneFlow.rearmReason?.let { reason -> eventLog.append("scene_auto_rearmed", mapOf(
            "reason" to reason, "motion" to state.motion.state.name, "rotationRate" to state.motion.rotationRate,
            "angleRad" to sceneMotionGate.viewDeltaRadians)) }
        if (previous != null && sceneFlow.ticket == null) cancelScene(when {
            !safe -> "环境任务已结束：画面失效、状态变化或安全打断"
            wasPending -> "环境分析等待超时，任务已结束"
            wasSpeaking -> "环境语音播放超时，任务已结束"
            else -> "环境结果等待播报已过期"
        })
        val ticket = sceneFlow.ticket
        if (ticket != null && sceneFlow.pending && sceneClient.capturedTicket != ticket.id && now - ticket.createdMs >= 2_500) {
            cancelScene("没有获取到新画面，环境分析已结束")
        }
        if(pendingSceneRequest.waiting) {
            val deferred=pendingSceneRequest.take(state.motion,!released && !fallFlow.active &&
                AssistanceStateStore.status.value.session==SessionState.ACTIVE && speechArbiter.active==null &&
                !commandRecognizer.isListening && !voiceHandoffPending &&
                AssistanceStateStore.status.value.voice==VoiceState.WAKE_LISTENING)
            if(deferred!=null) {
                eventLog.append("scene_resumed_after_still")
                requestScene(deferred.detailed,question=deferred.question,resumeAfterStill=true)
            }
            return
        }
        // Finding uses the still image independently of risk speech/cooldown.
        tickFind(now, positionViewValid && !released && sceneConfig.enabled &&
            AssistanceStateStore.status.value.session==SessionState.ACTIVE)
        if (successInfo==null && !findFlow.active && !clarifyingFind && findSelection.target==null && allowAutomatic && automatic && sceneConfig.automatic && !sceneConfig.testMode && speechArbiter.active == null &&
            AssistanceStateStore.status.value.voice == VoiceState.WAKE_LISTENING) requestScene(false, automatic = true)
    }

    private fun findStatus(message: String) {
        if (message != lastFindStatus) {
            lastFindStatus=message
            FindStatusStore.update(message)
            eventLog.append("find_status", mapOf("taskId" to findFlow.taskId, "message" to message, "attempts" to findFlow.attempts))
        }
    }

    private fun stopFind(reason: String, announce: Boolean = false) {
        val existed=findFlow.active || clarifyingFind || findSelection.target!=null
        val id=findFlow.taskId
        successInfo=null;completionInfoView=null
        successSpeechId=null;successAttemptMs=-10_000L;successSpeechFailures=0;successSpeechStartedMs=0L
        findGuidance.reset(); findClient.cancel(); findFlow.cancel(); clarifyingFind=false; findSelection.clear()
        val removed=speechArbiter.cancelPrefix("find:")
        speechCallbacks.keys.retainAll(speechArbiter.retainedIds())
        if (removed != null) tts?.stop()
        if (existed) {
            OperationalStateStore.update(task=TaskState.IDLE)
            findStatus(reason)
            eventLog.append("find_ended", mapOf("taskId" to id, "reason" to reason))
        }
        if (announce) speak(reason) { startWakeListening() }
    }

    private fun pauseFind(reason: String, confirm: Boolean = false) {
        if (!findFlow.active || findFlow.completion!=null) return
        findGuidance.reset(); findClient.cancel(); findFlow.invalidate(reason, confirm)
        val removed=speechArbiter.cancelPrefix("find:")
        speechCallbacks.keys.retainAll(speechArbiter.retainedIds())
        if (removed != null) { tts?.stop(); startWakeListening() }
        OperationalStateStore.update(task=TaskState.PAUSED)
        findStatus("找${findFlow.target?.name}已暂停：$reason")
    }

    private fun beginFind(raw: String, bare: Boolean = false, confirmed: Boolean = false) {
        clearPendingScene("已切换到找物")
        val name=FindTarget.extract(raw, bare)
        val target=FindTarget.resolve(name)
        if (target == null) {
            if (name.isNullOrBlank() || name in setOf("东西", "物品")) {
                stopFind("等待说明找物目标")
                cancelScene("正在确认找物目标", resume=false)
                clarifyingFind=true; clarificationUntil=SystemClock.elapsedRealtime()+15_000
                findStatus("请说要找的物品，例如手机、钥匙或杯子")
                speak("想找什么？提示音后请说手机、钥匙或杯子等物品名称。",inputAfterSpeech=true) { startCommandListening(false) }
            } else {
                stopFind("目标不支持")
                speak("目前不支持找$name。可以找手机、钥匙、杯子、水瓶、书、遥控器、背包、眼镜、钱包或椅子。") { startWakeListening() }
            }
            return
        }
        if (!confirmed) {
            stopFind("等待确认找物目标")
            cancelScene("正在确认找物目标",resume=false)
            findSelection.propose(target,SystemClock.elapsedRealtime())
            findStatus("要找的是${target.name}，请确认或取消")
            speak("你要找的是${target.name}，对吗？提示音后请说确认或取消。",inputAfterSpeech=true) { startCommandListening(false) }
            return
        }
        stopFind("已更换找物任务")
        cancelScene("正在找${target.name}，自动环境简述暂停", resume=false)
        sceneConfig=SceneConfig.read(this)
        if (!sceneConfig.enabled || !sceneConfig.direct) {
            speak("请先在环境设置中启用手机直连DeepSeek，再开始找物。") { startWakeListening() };return
        }
        findFlow.start(target,SystemClock.elapsedRealtime())
        OperationalStateStore.update(task=TaskState.RUNNING)
        findStatus("正在找${target.name}，请保持静止")
        eventLog.append("find_started",mapOf("taskId" to findFlow.taskId,"target" to target.name,"requestsPerStill" to 1,"requestTimeoutMs" to 25_000,"completeWithinMeters" to 0.5))
        val stillHint=if(PendingSceneRequest.stationary(OperationalStateStore.status.value.motion)) "" else "请先停稳，停稳后自动识别。"
        speak("开始找${target.name}。${stillHint}每次停稳只识别一次，目标在画面中间、50厘米内时结束。可随时唤醒说取消。") { startWakeListening() }
    }

    private fun waitFind(message: String) {
        if(!findFlow.active) return
        pauseFind(message)
        findFlow.waitForAdjustment(message)
        eventLog.append("find_round_paused",mapOf("taskId" to findFlow.taskId,"round" to findFlow.round,"reason" to message))
        findStatus(message)
        // This feedback has no old target position and may finish while the wearer adjusts the camera.
        speak(message) { startWakeListening() }
    }

    private fun cancelSuccessInfo(reason: String, resume: Boolean = true) {
        if(successInfo==null) return
        successInfo=null
        val removed=speechArbiter.cancelPrefix("find:success-info:")
        speechCallbacks.keys.retainAll(speechArbiter.retainedIds())
        eventLog.append("find_success_info_discarded",mapOf("reason" to reason))
        if(removed!=null) {
            tts?.stop()
            if(resume) {
                val next=speechArbiter.resume(SystemClock.elapsedRealtime())
                if(next!=null) startSpeech(next) else startWakeListening()
            }
        }
    }

    private fun checkSuccessInfo(now: Long, sameView: Boolean) {
        val info=successInfo ?: return
        info.observe(sameView)
        val speaking=speechArbiter.active?.key?.startsWith("find:success-info:")==true
        if(!info.sameView || !speaking && !info.valid(now)) cancelSuccessInfo("位置已变化或过期")
    }

    private fun queueSuccessInfo(info: FindSuccessInfo) {
        val completion=info.completion
        val now=SystemClock.elapsedRealtime()
        if(!info.valid(now) || !SceneGate.ready(OperationalStateStore.status.value,now,SceneFrames.lastFrameMs,false,0)) return
        successInfo=info
        val request=SpeechRequest("helios-find-info-${completion.taskId}-${utteranceCounter.incrementAndGet()}",
            "find:success-info:${completion.taskId}",info.text(),SpeechPriority.ORDINARY,now,completion.capturedMs+8_000)
        // Queue only: the success completion handler will select any higher-priority warning first.
        dispatchSpeech(request,queueOnly=true) {
            if(successInfo===info) successInfo=null
            eventLog.append("find_success_info_completed",mapOf("taskId" to completion.taskId))
            startWakeListening()
        }
    }

    private fun announceFindSuccess() {
        val completion=findFlow.completion ?: return
        if(released || fallFlow.active || speechArbiter.active?.priority==SpeechPriority.FALL || commandRecognizer.isListening || voiceHandoffPending) return
        val now=SystemClock.elapsedRealtime()
        if(!SceneGate.ready(OperationalStateStore.status.value,now,SceneFrames.lastFrameMs,false,0)) return
        if(successSpeechId?.let { it in speechArbiter.retainedIds() }==true) {
            if(speechArbiter.active?.id==successSpeechId && successSpeechStartedMs>0 && now-successSpeechStartedMs>=5_000)
                finishSpeech(successSpeechId,TextToSpeech.ERROR)
            return
        }
        if(now-successAttemptMs<1_000) return
        successAttemptMs=now
        val id="helios-find-success-${completion.taskId}-${utteranceCounter.incrementAndGet()}"
        successSpeechId=id
        // This is a completed fact, not a claim that an old position is still current.
        val text="已经找到${completion.target.name}。"
        dispatchSpeech(SpeechRequest(id,"find:success:${completion.taskId}",text,SpeechPriority.FIND_SUCCESS,now,now+30_000)) {
            eventLog.append("find_success_announced",mapOf("taskId" to completion.taskId,"target" to completion.target.name))
            val info=completionInfoView
            stopFind("已经找到${completion.target.name}，找物已完成")
            if(info!=null) queueSuccessInfo(info)
            startWakeListening()
        }
    }

    private fun tickFind(now: Long, safe: Boolean) {
        findSafe=safe && sceneConfig.direct
        if (findSelection.expire(now)) findStatus("找物目标确认超时，本次未开始")
        if (clarifyingFind && now>=clarificationUntil) {
            clarifyingFind=false; findStatus("未收到找物目标，本次未开始")
        }
        if (!findFlow.active) return
        if(findFlow.completion!=null) { announceFindSuccess();return }
        findFlow.observeMotion(OperationalStateStore.status.value.motion.state in setOf(MotionState.MOVING,MotionState.SHAKING))
        if(sceneFlow.ticket!=null) return
        val voice=AssistanceStateStore.status.value.voice
        val blocked = !findSafe || fallFlow.active
        if (blocked) {
            if (findFlow.pending != null || findFlow.result != null || findFlow.pauseReason == null)
                pauseFind(if (fallFlow.active) "跌倒流程结束后请说继续找" else "等待停稳和安全状态恢复",fallFlow.active)
            return
        }
        if (findFlow.needsConfirmation) return
        val result=findFlow.result
        if (result != null) {
            val started=findFlow.speechStartedMs
            if(started!=null && now-started>=30_000) stopFind("找物播报超时，任务已结束。",announce=true)
            else if(started==null && !findFlow.resultValid(now)) pauseFind("位置已过期，重新观察")
            return
        }
        if (!FindCapturePolicy.audioAllowsCapture(speechArbiter.active,voice,commandRecognizer.isListening,voiceHandoffPending)) return
        val pending=findFlow.pending
        if (pending != null && findClient.capturedTicket!=pending.requestId && now-pending.createdMs>=2_500) {
            waitFind("没有获取到新画面，找物已暂停。相机恢复后可说继续找，或运动后停稳再识别。");return
        }
        val sample=findFlow.sample(now,true) ?: return
        OperationalStateStore.update(task=TaskState.RUNNING)
        findStatus("正在找${sample.target.name}，第${findFlow.attempts}次观察")
        eventLog.append("find_requested",mapOf("taskId" to sample.taskId,"requestId" to sample.requestId,"target" to sample.target.name,"attempt" to findFlow.attempts,"round" to findFlow.round,"roundAttempt" to findFlow.roundAttempts,"source" to "finder"))
        val ticket=SceneTicket(sample.requestId,false,now,now+25_000)
        findClient.request(sceneConfig,ticket,canUpload={
            AssistanceStateStore.status.value.session==SessionState.ACTIVE && PendingSceneRequest.stationary(OperationalStateStore.status.value.motion)
        },onMetrics={ metrics -> handler.post {
            if(!released) eventLog.append("find_request_timing",metrics+mapOf("taskId" to sample.taskId))
        } },findTarget=sample.target,onFind={ observation,captured -> handler.post {
            if(released) return@post
            val outcome=findFlow.accept(sample,observation,captured,SystemClock.elapsedRealtime())
            eventLog.append("find_observation",mapOf("taskId" to sample.taskId,"requestId" to sample.requestId,"outcome" to outcome,
                "frameCapturedMs" to captured,"region" to (observation.region?.let { listOf(it.left,it.top,it.right,it.bottom) } ?: emptyList<Double>()),
                "context" to observation.context,"confidence" to observation.confidence,"depthVerified" to observation.depthVerified,"meters" to (observation.meters ?: "unknown")))
            when(outcome) {
                "reached" -> {
                    completionInfoView=findFlow.completion?.let { FindSuccessInfo(it) }
                    OperationalStateStore.update(task=TaskState.IDLE)
                    findStatus("已经找到${sample.target.name}，正在确认播报")
                    eventLog.append("find_success_committed",mapOf("taskId" to sample.taskId,"requestId" to sample.requestId,
                        "target" to sample.target.name,"meters" to (observation.meters ?: "unknown")))
                    announceFindSuccess()
                }
                "found", "guiding" -> {
                    val time=SystemClock.elapsedRealtime()
                    if(!findGuidance.shouldSpeak(observation,time)) {
                        findFlow.completeGuidance()
                        findStatus("${sample.target.name}位置变化不大，继续观察")
                        return@post
                    }
                    val id="helios-find-${sample.taskId}-${sample.requestId}"
                    val text=FindGuidance.describe(sample.target,observation,findFlow.guidanceCount==0)
                    findStatus(text)
                    dispatchSpeech(SpeechRequest(id,"find:result:${sample.taskId}:${sample.requestId}",text,SpeechPriority.ORDINARY,time,captured+8_000)) {
                        findFlow.completeGuidance();OperationalStateStore.update(task=TaskState.PAUSED)
                        findStatus("本次观察完成，等待运动后重新停稳再识别${sample.target.name}，可唤醒取消")
                        startWakeListening()
                    }
                }
                "not_found" -> {
                    val time=SystemClock.elapsedRealtime()
                    val text=FindGuidance.notFound(sample.target,observation,findFlow.roundAttempts,findFlow.everFound)
                    findStatus(text)
                    dispatchSpeech(SpeechRequest("helios-find-hint-${sample.taskId}-${sample.requestId}",
                        "find:feedback:${sample.taskId}:${sample.requestId}",text,SpeechPriority.ORDINARY,time,captured+8_000)) {
                        findFlow.completeGuidance()
                        OperationalStateStore.update(task=TaskState.PAUSED)
                        findStatus("暂未看到${sample.target.name}，等待运动后重新停稳再识别")
                        startWakeListening()
                    }
                }
                "stale", "expired" -> waitFind("本次画面已过期，任务仍保留，运动后停稳再识别。")
            }
        } }) { _,error,_ -> handler.post {
            if(!released && error!=null && findFlow.pending==sample) waitFind("${error.replace("环境分析", "找物识别").replace("环境服务", "识别服务")}，找物已暂停。恢复后停稳或说继续找，也可取消任务。")
        } }
    }

    private fun cancelScene(message: String, resume: Boolean = true) {
        val hadTask = sceneFlow.ticket != null || SceneStatusStore.status.value.busy
        if (sceneFlow.ticket != null) sceneFlow.cancel()
        sceneClient.cancel()
        sceneQuestions.clear();manualSceneTicket=null
        val removed = speechArbiter.cancelPrefix("scene:")
        speechCallbacks.keys.removeAll { it.startsWith("helios-scene-") }
        if (removed != null) tts?.stop()
        if (hadTask || removed != null) {
            OperationalStateStore.update(task = if (fallFlow.active) TaskState.PAUSED else TaskState.IDLE)
            SceneStatusStore.update(SceneStatus(message))
            eventLog.append("scene_cancelled", mapOf("reason" to message))
        }
        if (removed != null && resume) {
            val next = speechArbiter.resume(SystemClock.elapsedRealtime())
            if (next != null) startSpeech(next) else startWakeListening()
        }
    }

    private fun clearPendingScene(reason:String) {
        if(!pendingSceneRequest.clear()) return
        eventLog.append("scene_wait_cancelled",mapOf("reason" to reason))
        SceneStatusStore.update(SceneStatus(reason))
        if(!findFlow.active) OperationalStateStore.update(task=TaskState.IDLE)
    }

    private fun requestScene(detailed:Boolean,automatic:Boolean=false,question:String?=null,resumeAfterStill:Boolean=false) {
        if(!automatic) {
            pendingSceneRequest.clear()
            val session=AssistanceStateStore.status.value.session
            if(session!=SessionState.ACTIVE) {
                speak(if(session==SessionState.PAUSED) "请先恢复辅助，再进行图像识别。" else "请先完成辅助初始化和佩戴校准。") {startWakeListening()}
                return
            }
            if(!PendingSceneRequest.stationary(OperationalStateStore.status.value.motion)) {
                cancelScene("等待停稳后识别",resume=false)
                if(findFlow.active) pauseFind("等待视觉提问停稳")
                pendingSceneRequest.offer(DeferredSceneRequest(detailed,question))
                OperationalStateStore.update(task=TaskState.PAUSED)
                SceneStatusStore.update(SceneStatus("等待停稳，随后自动上传识别"))
                eventLog.append("scene_waiting_for_still",mapOf("mode" to if(question!=null) "question" else if(detailed) "detail" else "brief"))
                if(!resumeAfterStill) speak("请先停稳") {startWakeListening()} else startWakeListening()
                return
            }
        }
        if(!automatic && question==null) stopFind("已切换到环境分析")
        if(question!=null && findFlow.active) pauseFind("正在回答视觉问题")
        if(!automatic && !findFlow.active) OperationalStateStore.update(task=TaskState.IDLE)
        sceneConfig=SceneConfig.read(this)
        if(!automatic) tickScene(allowAutomatic=false,manualAdmission=true)
        val ticket=if(sceneConfig.enabled && (question==null || visualQuestionsReady()))
            sceneFlow.begin(SystemClock.elapsedRealtime(),detailed,requireStable=automatic && !sceneConfig.testMode) else null
        if(ticket==null) {
            if(!automatic) {
                val state=OperationalStateStore.status.value
                val reason=when {
                    !sceneConfig.enabled -> "请先配置并启用云端环境理解。"
                    state.device!=DeviceState.STREAMING || SystemClock.elapsedRealtime()-SceneFrames.lastFrameMs !in 0..1_000 -> "请先开启相机采集，等待新画面。"
                    state.fall!=FallState.IDLE -> "请先完成跌倒确认与恢复。"
                    !state.motion.calibrated -> "请先完成胸前佩戴校准。"
                    !PendingSceneRequest.stationary(state.motion) -> "请先停稳"
                    else -> "请保持静止，等待画面稳定后再试。"
                }
                SceneStatusStore.update(SceneStatus(reason))
                eventLog.append("scene_suppressed",mapOf("reason" to reason,"testMode" to sceneConfig.testMode))
                speak(reason) {startWakeListening()}
            }
            return
        }
        manualSceneTicket=if(automatic) null else ticket.id
        // Invalidate old queued/speaking scene results without cancelling the new ticket.
        sceneQuestions.clear()
        if(question!=null) sceneQuestions[ticket.id]=question
        sceneClient.cancel()
        val removed = speechArbiter.cancelPrefix("scene:")
        speechCallbacks.keys.removeAll { it.startsWith("helios-scene-") }
        if (removed != null) tts?.stop()
        OperationalStateStore.update(task = TaskState.RUNNING)
        SceneStatusStore.update(SceneStatus(if(question!=null) "正在观察新画面并回答问题…" else if (detailed) "正在观察新画面并补充细节…" else "正在分析当前画面…", busy = true))
        eventLog.append("scene_requested", mapOf("requestId" to ticket.id, "mode" to if(question!=null) "question" else if (detailed) "detail" else "brief", "automatic" to automatic, "testMode" to sceneConfig.testMode, "trigger" to if (automatic) sceneFlow.autoTriggerReason else "user_request"))
        sceneClient.request(sceneConfig, ticket, question=question, canUpload={
            AssistanceStateStore.status.value.session==SessionState.ACTIVE && PendingSceneRequest.stationary(OperationalStateStore.status.value.motion)
        }, onMetrics = { metrics ->
            handler.post { if (!released) eventLog.append("scene_request_timing", metrics) }
        }) { text, error, captured -> handler.post {
            if (released || !sceneFlow.finish(ticket.id, SystemClock.elapsedRealtime())) return@post
            OperationalStateStore.update(task = TaskState.IDLE)
            if (error != null || text == null) {
                sceneFlow.complete(ticket.id)
                sceneQuestions.remove(ticket.id);if(manualSceneTicket==ticket.id) manualSceneTicket=null
                SceneStatusStore.update(SceneStatus(error ?: "环境分析失败"))
                eventLog.append("scene_failed", mapOf("requestId" to ticket.id, "reason" to (error ?: "invalid_response")))
                if (!automatic) speak(error ?: "环境分析失败") { startWakeListening() }
            } else {
                SceneStatusStore.update(SceneStatus("环境分析完成 · %.1f秒 · 单帧结果".format((SystemClock.elapsedRealtime() - ticket.createdMs) / 1000.0), text))
                eventLog.append("scene_completed", mapOf("requestId" to ticket.id, "elapsedMs" to SystemClock.elapsedRealtime() - ticket.createdMs, "frameAgeMs" to SystemClock.elapsedRealtime() - captured))
                val now = SystemClock.elapsedRealtime()
                dispatchSpeech(SpeechRequest("helios-scene-${ticket.id}", "scene:${ticket.id}", text,
                    SpeechPriority.ENVIRONMENT, now, sceneFlow.playByMs,inputAfterSpeech=!automatic)) {
                    if (automatic) startWakeListening() else scheduleFollowUp()
                }
            }
        } }
        startWakeListening()
    }

    private val fallFlow=FallFlow()
    private var fallListening=false
    private var fallInputPending=false
    private var fallInputGeneration=0
    private var fallInputStarted=0L
    private var fallSpeechStarted=0L
    private var fallInputRetryAt=0L
    private var fallAudioProblem=""
    private var pausedTaskForFall=false
    private var fallSampleLabel=""
    private var fallSampleUntil=0L
    private var lastFallDiagnostic=0L
    private val fallTick=object : Runnable {
        override fun run() {
            if(released) return
            val now=SystemClock.elapsedRealtime()
            pendingDebugParameters?.takeIf {!fallFlow.active}?.let {applyDebugParameters(it)}
            tickStartup(now)
            val operational=OperationalStateStore.status.value
            val session=AssistanceStateStore.status.value.session
            if(now<fallSampleUntil) FallSignals.detector.rawSample(now)?.let { (a,g) ->
                eventLog.append("fall_imu_sample",mapOf("label" to fallSampleLabel,"timeMs" to now,"accelerationMs2" to a,
                    "rotationRadS" to g,"calibrated" to operational.motion.calibrated,"gravityMs2" to operational.motion.gravity))
            } else if(fallSampleLabel.isNotEmpty()) {
                eventLog.append("fall_imu_sample_end",mapOf("label" to fallSampleLabel));fallSampleLabel=""
            }
            val healthy=operational.device==DeviceState.STREAMING && !operational.motion.stale && operational.motion.calibrated
            val enabled=session==SessionState.ACTIVE && healthy && fallFlow.state==FallState.IDLE
            FallSignals.detector.poll(now,operational.motion.gravity,enabled)?.let { candidate ->
                eventLog.append("fall_candidate",mapOf("impact" to candidate.impact,"lowGravityBeforeImpact" to candidate.lowGravityBeforeImpact,
                    "changedDegrees" to candidate.changedDegrees,"peakTilt" to candidate.peakTilt,"source" to "Gemini 2 IMU"))
                triggerFall("imu_candidate")
            }
            val candidateStatus=FallSignals.detector.status
            if(enabled && now-lastFallDiagnostic>=1_000 && candidateStatus.rawPeakMs2>=18) {
                lastFallDiagnostic=now
                eventLog.append("fall_candidate_diagnostic",mapOf("reason" to candidateStatus.reason,"rawPeakMs2" to candidateStatus.rawPeakMs2,
                    "changedDegrees" to candidateStatus.changedDegrees,"tiltRadS" to candidateStatus.tiltRadS,"quietMs" to candidateStatus.quietMs))
            }
            if(fallSpeechStarted>0 && now-fallSpeechStarted>=8_000) {
                val active=speechArbiter.active
                if(active?.key?.startsWith("fall:")==true) {tts?.stop();finishSpeech(active.id,TextToSpeech.ERROR)}
                fallSpeechStarted=0
            }
            fallFlow.tick(now,if(fallFlow.state==FallState.HELPING) fallListening || (fallAudioProblem.startsWith("语音回应") && speechArbiter.active==null) else fallListening,healthy && operational.motion.state==MotionState.STILL && operational.motion.orientationDelta<3.0)?.let(::applyFallEffect)
            if(fallFlow.active && speechArbiter.active==null && !fallListening && now>=fallInputRetryAt) startFallListening()
            val monitoring=when {
                session!=SessionState.ACTIVE -> "监测暂停"
                !healthy -> "不可用：相机断开、IMU陈旧或未校准"
                fallFlow.state==FallState.RECOVERING -> "恢复期：等待稳定恢复佩戴姿态"
                else -> "Gemini 2 IMU原型监测中 · ${candidateStatus.displayReason} · 冲击峰值%.1f/%.1fm/s² · 倾角%.0f/%.0f°".format(candidateStatus.rawPeakMs2,FallSignals.detector.config.impactMetersPerSecond2,candidateStatus.changedDegrees,FallSignals.detector.config.changedDegrees)
            }
            val status=FallStatus(fallFlow.state,monitoring,(fallFlow.listenedMs/1_000).toInt(),fallAudioProblem,fallFlow.episode)
            if(status!=FallStatusStore.status.value) FallStatusStore.update(status)
            if(OperationalStateStore.status.value.fall!=fallFlow.state) OperationalStateStore.update(fall=fallFlow.state)
            tickScene()
            tryAnnounceClearance()
            handler.postDelayed(this,100)
        }
    }

    private fun triggerFall(source: String) {
        if(AssistanceStateStore.status.value.session!=SessionState.ACTIVE) {
            eventLog.append("fall_trigger_suppressed",mapOf("reason" to "session_not_active"));return
        }
        val effect=fallFlow.trigger(SystemClock.elapsedRealtime(),source) ?: return
        riskSpeechPolicy.reset();riskDetails.clear()
        pausedTaskForFall=(findFlow.active && findFlow.completion==null) || OperationalStateStore.status.value.task==TaskState.RUNNING
        pauseFind("跌倒流程中，恢复后需确认继续",confirm=true)
        findSelection.clear(); clarifyingFind=false
        clearPendingScene("跌倒确认已暂停环境任务")
        cancelScene("跌倒确认已暂停环境任务", resume = false)
        if(pausedTaskForFall) OperationalStateStore.update(task=TaskState.PAUSED)
        eventLog.append("fall_started",mapOf("episode" to effect.episode,"source" to source,"taskPaused" to pausedTaskForFall))
        applyFallEffect(effect)
    }

    private fun stopFallAudio() {
        handler.removeCallbacks(wakeStart)
        stopWakeEngine();stopCommandInput()
        fallListening=false;fallInputPending=false;fallInputGeneration++;fallSpeechStarted=0
        tts?.stop();speechArbiter.clear();speechCallbacks.clear()
    }

    private fun applyFallEffect(effect: FallEffect) {
        stopFallAudio()
        OperationalStateStore.update(fall=fallFlow.state)
        fallAudioProblem=""
        val releasedEvent=effect.kind==FallEffectKind.RELEASE
        if(effect.kind==FallEffectKind.HELP) {
            val route=runCatching {audioRoute.routeHelpSpeaker()}.getOrDefault("外放路由失败")
            fallAudioProblem=route
            tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            eventLog.append("fall_help_output",mapOf("episode" to effect.episode,"route" to route))
        } else tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val now=SystemClock.elapsedRealtime()
        eventLog.append("fall_effect",mapOf("episode" to effect.episode,"sequence" to effect.sequence,"kind" to effect.kind.name,
            "reason" to fallFlow.reason,"responseMs" to fallFlow.listenedMs))
        val id="helios-fall-${effect.episode}-${effect.sequence}"
        fallSpeechStarted=now
        val text=effect.text+if(releasedEvent && pausedTaskForFall) if(findFlow.active) "找物任务已暂停，要继续请唤醒后说继续找。" else "原任务已暂停，是否继续请重新发起指令。" else ""
        dispatchSpeech(SpeechRequest(id,"fall:${effect.episode}:${effect.sequence}",text,SpeechPriority.FALL,now,now+8_000)) {
            fallSpeechStarted=0
            if(releasedEvent) {audioRoute.releaseAllRoutes();startWakeListening()}
            else {fallInputRetryAt=SystemClock.elapsedRealtime()+900;handler.postDelayed({if(fallFlow.active) startFallListening()},900)}
        }
    }

    private fun startFallListening(fromWake:Boolean=false) {
        if(released || !fallFlow.active || fallListening || fallInputPending || speechArbiter.active!=null ||
            voiceHandoffPending && !fromWake) return
        handler.removeCallbacks(wakeStart);stopWakeEngine()
        if(!commandRecognizer.isAvailable) {
            fallAudioProblem="语音回应不可用，请使用我没事、需要帮助或取消求助按钮。"
            fallInputRetryAt=SystemClock.elapsedRealtime()+1_500
            return
        }
        runCatching {audioRoute.routeInput()}
        val episode=fallFlow.episode
        val generation=++fallInputGeneration
        fallInputPending=true
        AssistanceStateStore.update(voice=VoiceState.COMMAND_LISTENING,wakeAcknowledged=fromWake,liveTranscript="",
            message="跌倒回应：请说我没事、需要帮助或取消求助")
        commandRecognizer.startSafety(6_000,object : CommandRecognizer.Listener {
            override fun onReady() {
                if(fallFlow.active && episode==fallFlow.episode && generation==fallInputGeneration && speechArbiter.active==null) {
                    fallInputPending=false
                    fallListening=true;fallInputStarted=SystemClock.elapsedRealtime()
                    if(fromWake) playPromptTone()
                    eventLog.append("fall_response_listening",mapOf("episode" to episode,"windowMs" to 6_000))
                }
            }
            override fun onResult(candidates: List<String>) {
                if(!fallFlow.active || episode!=fallFlow.episode || generation!=fallInputGeneration) return
                val text=candidates.firstOrNull().orEmpty()
                finishFallInput("result",text)
                val reply=FallFlow.parse(text)
                eventLog.append("fall_reply",mapOf("episode" to episode,"reply" to reply.name,"text" to text))
                fallFlow.reply(SystemClock.elapsedRealtime(),reply)?.let(::applyFallEffect)
            }
            override fun onTimeout() {if(fallFlow.active && episode==fallFlow.episode && generation==fallInputGeneration) finishFallInput("timeout","")}
            override fun onError(code: Int,message: String) {
                if(!fallFlow.active || episode!=fallFlow.episode || generation!=fallInputGeneration) return
                finishFallInput("error",message)
                fallAudioProblem="语音回应失败，请使用按钮。"
                fallInputRetryAt=SystemClock.elapsedRealtime()+1_500
            }
        })
    }
    private fun finishFallInput(reason: String,text: String) {
        eventLog.append("fall_response_finished",mapOf("episode" to fallFlow.episode,"reason" to reason,"text" to text,
            "readyListeningMs" to if(fallListening) SystemClock.elapsedRealtime()-fallInputStarted else 0))
        fallListening=false;fallInputPending=false
        fallInputRetryAt=SystemClock.elapsedRealtime()+200
    }
    private fun handleFallReply(text: String,source: String) {
        val reply=FallFlow.parse(text)
        eventLog.append("fall_reply",mapOf("episode" to fallFlow.episode,"reply" to reply.name,"source" to source))
        fallFlow.reply(SystemClock.elapsedRealtime(),reply)?.let(::applyFallEffect)
    }

    override fun onCreate() {
        super.onCreate()
        eventLog = EventLog(applicationContext)
        wakeWordEngine = VoskWakeWordEngine(applicationContext)
        commandRecognizer = VoskCommandRecognizer(applicationContext)
        audioRoute = AudioRouteController(applicationContext,eventLog)
        applyDebugParameters(DebugParameterRepository.load(this),notify=false)
        sceneConfig = SceneConfig.read(this)
        SceneStatusStore.update(SceneStatus(if (sceneConfig.enabled) "环境理解已配置，等待相机采集和佩戴校准" else "环境理解未启用"))
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_DEBUG_PARAMETERS -> applyDebugParameters(DebugParameterRepository.load(this))
            ACTION_SCENE_CONFIG -> {
                clearPendingScene("环境配置已更新")
                pauseFind("环境配置已更新，等待重新观察")
                sceneConfig = SceneConfig.read(this)
                riskSpeechPolicy.reset(); sceneMotionGate.reset()
                cancelScene(if (sceneConfig.enabled) "环境配置已更新，请重新发起任务" else "环境理解已停用")
            }
            ACTION_SCENE_BRIEF -> requestScene(false)
            ACTION_SCENE_DETAIL -> requestScene(true)
            ACTION_SCENE_CANCEL -> { clearPendingScene("环境分析已取消");sceneFlow.cancel(); cancelScene("环境分析已取消"); startWakeListening() }
            ACTION_STOP -> stopWithSpeech("button")
            ACTION_USAGE_HELP -> playUsageHelp()
            ACTION_USAGE_HELP_STOP -> stopUsageHelp()
            ACTION_VOICE_INPUT -> {
                if(fallFlow.active) return START_NOT_STICKY
                if(commandRecognizer.isListening) { stopCommandInput();startWakeListening() }
                else if(wakeListeningAllowed()) handleWakeWord("touch")
            }
            ACTION_RECALIBRATE -> {
                if(!fallFlow.active && sensorSession!=null && AssistanceStateStore.status.value.session in setOf(SessionState.ACTIVE,SessionState.STARTING)) {
                    clearPendingScene("正在重新校准")
                    pauseFind("重新校准，等待停稳")
                    cancelScene("重新校准，旧位置已失效",resume=false)
                    sensorSession?.camera?.motion?.requestCalibration()
                    sessionWasActivated=false;startupStartedMs=SystemClock.elapsedRealtime()
                    startupFlow.retryCalibration();startupAnnounced=false;calibrationWaitSince=0L
                    AssistanceStateStore.update(session=SessionState.STARTING)
                }
            }
            ACTION_PAUSE -> processIntent(VoiceIntent.PAUSE_ASSISTANCE, "界面按钮")
            ACTION_RESUME -> processIntent(VoiceIntent.RESUME_ASSISTANCE, "界面按钮")
            ACTION_INJECT_SAFETY -> injectSpeech(SpeechPriority.SAFETY, "测试安全提醒，请注意前方。")
            ACTION_INJECT_ORDINARY -> injectSpeech(SpeechPriority.ORDINARY, "测试普通播报。")
            ACTION_INJECT_RESPONSE -> injectResponseWindow()
            ACTION_LOCAL_RISK -> intent?.let { handleLocalRisk(it) }
            ACTION_INJECT_FALL -> triggerFall("debug_injection")
            ACTION_RESET_FALL_TEST -> if((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0) {
                stopFallAudio();fallFlow.reset();FallSignals.detector.reset()
                eventLog.append("fall_test_reset");startWakeListening()
            }
            ACTION_FALL_SAMPLE -> {
                val label=intent?.getStringExtra("fallSampleLabel").orEmpty()
                if(label in setOf("静止","走路","转身","弯腰","坐下","设备模拟")) {
                    fallSampleLabel=label;fallSampleUntil=SystemClock.elapsedRealtime()+15_000
                    eventLog.append("fall_imu_sample_start",mapOf("label" to label,"durationMs" to 15_000))
                }
            }
            ACTION_FALL_REPLY -> handleFallReply(intent?.getStringExtra(EXTRA_FALL_REPLY).orEmpty(),"button")
            else -> startAssistance()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseResources()
        AssistanceStateStore.update(
            session = SessionState.OFF,
            tts = TtsState.IDLE,
            voice = VoiceState.UNAVAILABLE,
            message = "辅助模式已退出，语音资源已释放",
        )
        runCatching { eventLog.append("assistance_service_destroyed") }
        super.onDestroy()
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    private fun applyDebugParameters(value:DebugParameters,notify:Boolean=true) {
        if(fallFlow.active) {
            pendingDebugParameters=value
            eventLog.append("debug_parameters_deferred",mapOf("reason" to "fall_response_active"));return
        }
        pendingDebugParameters=null
        if(notify) {
            clearPendingScene("调试参数已更新")
            pauseFind("调试参数已更新，请停稳后继续")
            cancelScene("调试参数已更新，请重新提问",resume=false)
            val removed=speechArbiter.cancelWhere {it.key.startsWith("risk:")}
            if(removed!=null) tts?.stop()
            speechCallbacks.keys.retainAll(speechArbiter.retainedIds())
        }
        pendingClearance.clear();movingClearance.reset();movingClearanceId=null
        parametersAppliedMs=SystemClock.elapsedRealtime()
        DebugParameterRuntime.current=value
        SensorRuntime.camera?.motion?.configure(value.motion)
        FallSignals.detector.configure(value.fallCandidate)
        fallFlow.configure(value.fallFlow)
        riskSpeechPolicy=RiskSpeechPolicy(value.navigation)
        riskDetails.clear()
        NavigationStateStore.update(NavigationValidity())
        eventLog.append("debug_parameters_applied",mapOf("values" to value.asMap()))
        if(notify && AssistanceStateStore.status.value.session in setOf(SessionState.ACTIVE,SessionState.PAUSED))
            speak("调试参数已应用。") {startWakeListening()}
    }

    private fun startAssistance() {
        if (AssistanceStateStore.status.value.session in setOf(SessionState.STARTING, SessionState.ACTIVE, SessionState.PAUSED)) return
        riskRefreshAfterInput=false
        pendingClearance.clear();movingClearance.reset();movingClearanceId=null
        released = false
        startupGeneration++
        val generation=startupGeneration
        startupAnnounced=false;sessionWasActivated=false;finalFault=null
        checkingFeedbackCompleted=false;startupStartedMs=SystemClock.elapsedRealtime()
        startupFlow.start();calibrationWaitSince=0L;lastStartupPromptMs=SystemClock.elapsedRealtime()
        lastStartupStatus=StartupStatus()
        wakeReady=false;commandReady=false;ttsReady=false
        stopWakeEngine();stopCommandInput()
        tts?.stop();tts?.shutdown();tts=null
        sensorSession?.close();sensorSession=null
        StartupStatusStore.update(StartupStatus(StartupPhase.INITIALIZING,"正在准备语音和后台采集"))
        AssistanceStateStore.update(
            session = SessionState.STARTING,
            tts = TtsState.INITIALIZING,
            voice = VoiceState.MODEL_LOADING,
            message = "正在加载本地唤醒模型并检查中文语音",
            lastCommand = null,
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("正在加载本地唤醒模型"),
            foregroundTypes(),
        )
        cpuWakeLock=cpuWakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Helios:AssistanceSession").apply { setReferenceCounted(false) }
        if(cpuWakeLock?.isHeld!=true) cpuWakeLock?.acquire()
        eventLog.append("background_session_started",mapOf("wakeLockHeld" to (cpuWakeLock?.isHeld==true)))
        runCatching {audioRoute.routeInput()}.onFailure {
            eventLog.append("startup_audio_route_error",mapOf("type" to it.javaClass.simpleName))
        }
        try {
            sensorSession=SensorSessionController(applicationContext) { risk -> handler.post { if(!released && generation==startupGeneration) handleLocalRisk(risk) } }
            sensorSession?.start()
        } catch(error:Exception) {
            eventLog.append("sensor_session_start_failed",mapOf("error" to error.javaClass.simpleName))
            failStartup("采集初始化失败，请重新启动辅助")
            return
        }
        eventLog.append("assistance_start_requested")
        handler.removeCallbacks(fallTick)
        handler.post(fallTick)
        initializeTts()
        val startedAt = System.currentTimeMillis()
        wakeWordEngine.load(
            onReady = {
                handler.post {
                    if (released || generation!=startupGeneration) return@post
                    wakeReady = true
                    eventLog.append("wake_model_ready", mapOf("loadMs" to System.currentTimeMillis() - startedAt))
                    maybeFinishStartup();startWakeListening()
                }
            },
            onError = { error ->
                handler.post {
                    if (released || generation!=startupGeneration) return@post
                    AssistanceStateStore.update(
                        session = SessionState.ERROR,
                        voice = VoiceState.ERROR,
                        message = "本地唤醒模型加载失败，请导出日志后重试",
                    )
                    eventLog.append("wake_model_error", mapOf("error" to error.javaClass.name, "message" to (error.message ?: "")))
                    updateNotification("唤醒模型加载失败")
                    failStartup("本地唤醒模型加载失败")
                }
            },
        )
        val commandStartedAt = System.currentTimeMillis()
        commandRecognizer.load(
            onReady = {
                handler.post {
                    if (released || generation!=startupGeneration) return@post
                    commandReady = true
                    eventLog.append("command_model_ready", mapOf("loadMs" to System.currentTimeMillis() - commandStartedAt))
                    maybeFinishStartup();startWakeListening()
                }
            },
            onError = { error ->
                handler.post {
                    if (released || generation!=startupGeneration) return@post
                    AssistanceStateStore.update(session = SessionState.ERROR, voice = VoiceState.ERROR, message = "本地中文模型加载失败")
                    eventLog.append("command_model_error", mapOf("error" to error.javaClass.name, "message" to (error.message ?: "")))
                    failStartup("本地中文模型加载失败")
                }
            },
        )
    }

    private fun initializeTts() {
        val generation=startupGeneration
        tts = TextToSpeech(applicationContext) { initStatus ->
            val engine = tts
            if (released || generation!=startupGeneration || engine == null) return@TextToSpeech
            if (initStatus != TextToSpeech.SUCCESS) {
                onTtsUnavailable("中文语音引擎初始化失败")
                return@TextToSpeech
            }
            val locale = Locale.SIMPLIFIED_CHINESE
            val availability = engine.isLanguageAvailable(locale)
            if (availability == TextToSpeech.LANG_MISSING_DATA || availability == TextToSpeech.LANG_NOT_SUPPORTED ||
                engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE
            ) {
                onTtsUnavailable("缺少可用的中文语音资源")
                return@TextToSpeech
            }
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    handler.post { if (!released && speechArbiter.active?.id == utteranceId) {
                        AssistanceStateStore.update(tts = TtsState.SPEAKING)
                        val request=speechArbiter.active!!
                        val started=SystemClock.elapsedRealtime()
                        if(request.clearanceFeedback) movingClearance.spoken(started)
                        eventLog.append("tts_started",mapOf("utteranceId" to utteranceId.orEmpty(),
                            "queue_to_audio_ms" to (started-request.createdMs),
                            "risk_decision_to_audio_ms" to (request.riskObservedMs?.let {started-it} ?: -1L)))
                    } }
                }

                override fun onDone(utteranceId: String?) = finishSpeech(utteranceId, null)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finishSpeech(utteranceId, TextToSpeech.ERROR)

                override fun onError(utteranceId: String?, errorCode: Int) = finishSpeech(utteranceId, errorCode)
            })
            ttsReady = true
            AssistanceStateStore.update(tts = TtsState.READY)
            eventLog.append("tts_ready", mapOf("engine" to engine.defaultEngine, "locale" to locale.toLanguageTag()))
            startupSpeech("正在检查设备，请稍候。","checking") {
                if(!released && generation==startupGeneration) {checkingFeedbackCompleted=true;maybeFinishStartup()}
            }
            maybeFinishStartup();startWakeListening()
        }
    }

    private fun onTtsUnavailable(message: String) {
        eventLog.append("tts_unavailable",mapOf("message" to message))
        failStartup(message)
    }

    private fun failStartup(message:String) {
        val canAnnounce=ttsReady && tts!=null
        clearPendingScene("辅助已结束")
        startupGeneration++;startupFlow.stop();sessionWasActivated=false
        wakeReady=false;commandReady=false
        stopWakeEngine();stopCommandInput()
        tts?.stop();speechArbiter.clear();speechCallbacks.clear()
        handler.removeCallbacks(wakeStart);handler.removeCallbacks(followUpStart)
        sensorSession?.close();sensorSession=null
        if(cpuWakeLock?.isHeld==true) cpuWakeLock?.release()
        val reason=if(message.startsWith("检查未通过")) message else "检查未通过，辅助未启用。$message"
        StartupStatusStore.update(StartupStatus(StartupPhase.ERROR,reason))
        AssistanceStateStore.update(session=SessionState.ERROR,voice=VoiceState.ERROR,message=reason)
        updateNotification(reason)
        eventLog.append("startup_failed_resources_released",mapOf("reason" to reason,"audioReleasePending" to canAnnounce))
        val failureGeneration=startupGeneration
        val releaseFailureAudio:()->Unit={
            if(!released && failureGeneration==startupGeneration && AssistanceStateStore.status.value.session==SessionState.ERROR) {
                audioRoute.releaseAllRoutes()
                eventLog.append("startup_failure_audio_released")
            }
        }
        if(canAnnounce) {
            val now=SystemClock.elapsedRealtime()
            dispatchSpeech(SpeechRequest("helios-startup-error-${utteranceCounter.incrementAndGet()}","startup-error",reason,SpeechPriority.ORDINARY,now,now+10_000),onDone=releaseFailureAudio)
            // A failed or missing TTS completion must not keep the Bluetooth session alive.
            handler.postDelayed({releaseFailureAudio()},12_000)
        } else releaseFailureAudio()
    }

    private fun foregroundTypes():Int {
        val bluetooth=androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.BLUETOOTH_CONNECT)==android.content.pm.PackageManager.PERMISSION_GRANTED
        val usb=getSystemService(android.hardware.usb.UsbManager::class.java)
        val connected=bluetooth || usb.deviceList.values.any {usb.hasPermission(it)}
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if(connected) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
    }

    private fun maybeFinishStartup() { tickStartup(SystemClock.elapsedRealtime()) }

    private fun startupSpeech(text:String,kind:String,onDone:()->Unit) {
        val now=SystemClock.elapsedRealtime()
        dispatchSpeech(SpeechRequest("helios-startup-${utteranceCounter.incrementAndGet()}","startup:$kind",text,
            SpeechPriority.ORDINARY,now,now+20_000),onDone=onDone)
    }

    private fun sensorAges(camera:GeminiCamera?):List<Long> {
        // Read immutable sample snapshots before the clock; a new IMU arrival must not
        // appear to come from the future and accidentally terminate a healthy session.
        val samples=listOf(camera?.color,camera?.depth,camera?.accel,camera?.gyro)
        val sampledAt=SystemClock.elapsedRealtime()
        return samples.map {if(it==null || it.receivedMs<=0) Long.MAX_VALUE else sampledAt-it.receivedMs}
    }

    private fun tickStartup(now:Long) {
        val session=AssistanceStateStore.status.value.session
        val camera=sensorSession?.camera
        val ages=sensorAges(camera)
        if(!released && sessionWasActivated && session in setOf(SessionState.ACTIVE,SessionState.PAUSED,SessionState.STARTING)) {
            val state=OperationalStateStore.status.value
            val fault=RuntimeHealthPolicy.failure(if(sensorSession?.cameraPresent==true) state.device else DeviceState.DISCONNECTED,ages,camera?.detectorFailed==true,state.motion.calibrated,
                androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED,
                androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)
            if(fault!=null) {
                finalFault="异常退出：$fault"
                eventLog.append("runtime_health_fault",mapOf("reason" to fault))
                stopWithSpeech("runtime_fault","$fault，辅助已退出。请检查后重新启动。")
                return
            }
            // A short arrival jitter may suppress individual tasks, but must not silently
            // restart calibration. Persistent loss above is an explicit session exit.
            if(session in setOf(SessionState.ACTIVE,SessionState.PAUSED)) return
            if(ages.any {it>1_000}) return
        }
        if(released || !startupFlow.started || session in setOf(SessionState.OFF,SessionState.STOPPING,SessionState.PAUSED,SessionState.ERROR)) return
        val state=OperationalStateStore.status.value
        val sensors=state.device==DeviceState.STREAMING && ages.all {it in 0..1_000} && camera?.detectorReady==true
        val voicePrepared=wakeReady && commandReady && ttsReady
        val failure=if(!checkingFeedbackCompleted) null else when {
            androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED -> "麦克风权限不可用"
            androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED -> "相机权限不可用"
            else -> StartupCheckPolicy.failure(sensorSession?.cameraPresent==true,state.device,camera?.detectorFailed==true,sensors,voicePrepared,now-startupStartedMs)
        }
        val waiting=when {
            camera==null || state.device==DeviceState.DISCONNECTED -> "请连接胸前相机"
            state.device==DeviceState.CONNECTING -> "正在连接相机或等待USB授权"
            ages[0] !in 0..1_000 -> "正在等待有效图像数据"
            ages[1] !in 0..1_000 -> "深度数据尚未就绪"
            ages[2] !in 0..1_000 || ages[3] !in 0..1_000 -> "运动传感器数据尚未就绪"
            !camera.detectorReady -> "正在准备目标检测"
            else -> "等待设备检查完成"
        }
        val next=startupFlow.observe(voicePrepared && checkingFeedbackCompleted,sensors,state.motion.calibrated && !state.motion.stale,
            state.motion.state==MotionState.STILL,error=failure,deviceMessage=waiting)
        if(next.phase==StartupPhase.ERROR) {failStartup(next.message);return}
        StartupStatusStore.update(if(next.phase==StartupPhase.READY && session!=SessionState.ACTIVE)
            next.copy(message="校准完成，正在启用辅助") else next)
        if(next!=lastStartupStatus) {
            lastStartupStatus=next
            eventLog.append("startup_phase",mapOf("phase" to next.phase.name,"message" to next.message))
            updateNotification(next.message)
        }
        if(commandRecognizer.isListening || voiceHandoffPending) return
        if(next.phase==StartupPhase.READY) {
            if(!startupAnnounced) {
                startupAnnounced=true
                val generation=startupGeneration
                val activationToken=startupFlow.activationToken() ?: return
                startupSpeech("佩戴校准已完成，辅助已就绪，可以说Hi Helios。","ready") {
                    if(!released && generation==startupGeneration) {
                        val current=OperationalStateStore.status.value
                        val currentCamera=sensorSession?.camera
                        val checkNow=SystemClock.elapsedRealtime()
                        val currentFresh=currentCamera!=null && sensorAges(currentCamera).all {it in 0..1_000}
                        if(startupFlow.mayActivate(activationToken) && currentFresh && currentCamera?.detectorReady==true && current.device==DeviceState.STREAMING &&
                            wakeReady && commandReady && ttsReady && current.motion.calibrated && !current.motion.stale) {
                            ServiceCompat.startForeground(this,NOTIFICATION_ID,buildNotification("辅助运行中"),foregroundTypes())
                            sessionWasActivated=true
                            AssistanceStateStore.update(session=SessionState.ACTIVE,message="辅助已就绪")
                            StartupStatusStore.update(StartupStatus(StartupPhase.READY,"辅助运行中"))
                            eventLog.append("startup_ready",mapOf("calibrated" to true,"wakeLockHeld" to (cpuWakeLock?.isHeld==true)))
                            startWakeListening()
                        } else {
                            startupAnnounced=false
                            tickStartup(checkNow)
                        }
                    }
                }
            }
            return
        }
        startupAnnounced=false
        if(session==SessionState.ACTIVE) {
            cancelScene("设备或佩戴状态失效，等待恢复",resume=false)
            pauseFind("等待设备恢复与佩戴校准")
        }
        AssistanceStateStore.update(session=SessionState.STARTING,message=next.message)
        if(next.phase==StartupPhase.CALIBRATING) {
            val token=startupFlow.beginCalibrationPrompt()
            if(token!=null) {
                calibrationWaitSince=now
                val generation=startupGeneration
                startupSpeech(startupCheckSummary()+"接下来进行佩戴校准。请将相机固定在自己胸前，镜头朝前，身体保持自然站姿或坐姿，然后保持静止。","calibration") {
                    if(!released && generation==startupGeneration && startupFlow.acceptCalibrationPrompt(token,SystemClock.elapsedRealtime())) {
                        calibrationWaitSince=SystemClock.elapsedRealtime();lastStartupPromptMs=calibrationWaitSince
                        sensorSession?.camera?.calibrateWornOrientation()
                        eventLog.append("startup_calibration_started")
                    }
                }
            } else if(startupFlow.takeReminder(now,speechArbiter.active!=null)) {
                val reminder=if(state.motion.calibrated) "校准已经完成，请继续保持静止，正在等待停稳。" else "校准尚未完成，请将相机固定在胸前，保持自然姿势和静止。"
                startupSpeech(reminder,"waiting") { }
            }
        } else if(next.phase==StartupPhase.WAITING_DEVICE && ttsReady && speechArbiter.active==null && now-lastStartupPromptMs>30_000) {
            lastStartupPromptMs=now
            startupSpeech("相机数据尚未就绪，请检查连接或USB授权。","device") { }
        }
    }

    private fun startupCheckSummary():String {
        val outputs=getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bluetooth=outputs.any {it.type in setOf(android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,android.media.AudioDeviceInfo.TYPE_BLE_HEADSET)}
        val wired=outputs.any {it.type in setOf(android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES)}
        val headphones=when {bluetooth->"检测到蓝牙耳机连接。";wired->"检测到有线耳机连接。";else->"未检测到耳机，请留意手机声音。"}
        val connectivity=getSystemService(android.net.ConnectivityManager::class.java)
        val network=runCatching {connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true}.getOrDefault(false)
        val networkText=if(network) "网络已连接。" else "网络暂不可用，联网识别暂不可用，本地辅助可继续。"
        val cloud=if(sceneConfig.enabled) "" else "环境理解尚未启用。"
        eventLog.append("startup_devices_announced",mapOf("bluetoothDetected" to bluetooth,"wiredDetected" to wired,"networkValidated" to network,"cloudEnabled" to sceneConfig.enabled))
        return "相机已连接，图像、深度和运动数据正常。语音播报可用，麦克风已授权。"+headphones+networkText+cloud
    }

    private fun visualQuestionsReady(): Boolean = !released && !fallFlow.active &&
        AssistanceStateStore.status.value.session==SessionState.ACTIVE &&
        SceneGate.ready(OperationalStateStore.status.value,SystemClock.elapsedRealtime(),SceneFrames.lastFrameMs,false,0)

    private fun scheduleFollowUp() {
        handler.removeCallbacks(wakeStart);handler.removeCallbacks(followUpStart)
        if(released || fallFlow.active || speechArbiter.active!=null || findSelection.target!=null || clarifyingFind) { startWakeListening();return }
        AssistanceStateStore.update(voice=VoiceState.PROMPTING,message="准备追问，提示音后8秒内可直接提问")
        voiceHandoffPending=true
        handler.postDelayed(followUpStart,200)
    }

    private fun beginFollowUp() {
        voiceHandoffPending=false
        if(released || fallFlow.active || speechArbiter.active!=null || commandRecognizer.isListening) { startWakeListening();return }
        startCommandListening(exitConfirmation=false,followUp=true)
    }

    private fun stopCommandInput(reason:String="audio_handoff") {
        if(commandRecognizer.isListening) eventLog.append("command_input_closed",mapOf("reason" to reason))
        commandRecognizer.stop()
    }

    /** Invalidate callbacks already queued by the old recorder before changing audio owners. */
    private fun stopWakeEngine(reason:String="audio_handoff", preserveDeferred:Boolean=false) {
        if(!preserveDeferred) deferredUrgentWake.clear()
        if(wakeWordEngine.isListening) eventLog.append("wake_listener_stopped",mapOf("reason" to reason))
        wakeCallbacks.end()
        voiceHandoffPending=preserveDeferred && deferredUrgentWake.speechId!=null
        handler.removeCallbacks(wakeCommandStart)
        wakeWordEngine.stop()
    }

    private fun wakeListeningAllowed(): Boolean {
        val active = speechArbiter.active
        return WakeListeningPolicy.allowed(released, wakeReady && commandReady && ttsReady, fallFlow.active, active,
            commandRecognizer.isListening,AssistanceStateStore.status.value.session)
    }

    private fun publishWakeListening() {
        val sceneSpeaking = speechArbiter.active!=null
        val paused = AssistanceStateStore.status.value.session == SessionState.PAUSED
        AssistanceStateStore.update(voice = VoiceState.WAKE_LISTENING,
            tts = if (sceneSpeaking) TtsState.SPEAKING else if (ttsReady) TtsState.READY else TtsState.UNAVAILABLE,
            message = if (sceneSpeaking) "正在播报，可说 Hi Helios 打断并下达指令"
                else if (paused) "辅助已暂停，仍在等待 Hi Helios" else "正在本地等待 Hi Helios")
    }

    private fun startWakeListening() {
        if(deferredUrgentWake.speechId!=null && deferredUrgentWake.speechId!=speechArbiter.active?.id) {
            val hypothesis=deferredUrgentWake.take()!!
            voiceHandoffPending=false
            handleWakeWord(hypothesis)
            return
        }
        if(voiceHandoffPending) return
        if(tryAnnounceClearance()) return
        if(riskRefreshAfterInput && !commandRecognizer.isListening && speechArbiter.active==null &&
            sceneFlow.ticket==null && findFlow.pending==null && findFlow.result==null &&
            findSelection.target==null && !clarifyingFind) {
            riskRefreshAfterInput=false
            SensorRuntime.camera?.requestRiskRefresh()
            eventLog.append("risk_refresh_after_voice_input")
        }
        interruptedStartup.take(startupGeneration,!released && speechArbiter.active==null &&
            !commandRecognizer.isListening && AssistanceStateStore.status.value.session==SessionState.STARTING)?.invoke()
        handler.removeCallbacks(wakeCommandStart)
        handler.removeCallbacks(followUpStart)
        if (!wakeListeningAllowed()) return
        handler.removeCallbacks(wakeStart)
        if (wakeWordEngine.isListening) { publishWakeListening(); return }
        AssistanceStateStore.update(voice = VoiceState.PROMPTING, message = "正在准备本地唤醒监听", liveTranscript="", wakeAcknowledged=false)
        handler.postDelayed(wakeStart, if (speechArbiter.active!=null) 200L else WAKE_RESUME_DELAY_MS)
    }

    private fun beginWakeListening() {
        if(voiceHandoffPending) return
        if (!wakeListeningAllowed()) return
        if (wakeWordEngine.isListening) { publishWakeListening(); return }
        stopCommandInput()
        val route = runCatching { audioRoute.routeInput() }.getOrDefault("系统默认麦克风")
        val session = AssistanceStateStore.status.value.session
        AssistanceStateStore.update(
            session = session,
            tts = if (ttsReady) TtsState.READY else TtsState.UNAVAILABLE,
            voice = VoiceState.WAKE_LISTENING,
            message = if (session == SessionState.PAUSED) "辅助已暂停，仍在等待 Hi Helios" else "正在本地等待 Hi Helios",
        )
        updateNotification(if (session == SessionState.PAUSED) "辅助已暂停 · 唤醒仍可用" else "正在等待 Hi Helios")
        wakeCallbacks.end()
        val wakeToken = wakeCallbacks.begin()!!
        runCatching {
            wakeWordEngine.start(object : WakeWordEngine.Listener {
                override fun onWakeWord(hypothesis: String) { handler.post {
                    if (wakeCallbacks.current(wakeToken)) handleWakeWord(hypothesis)
                    else if (!released) eventLog.append("wake_callback_ignored",mapOf("reason" to "stale_recorder"))
                } }
                override fun onError(error: Throwable) { handler.post { if(wakeCallbacks.current(wakeToken)) handleWakeError(error) } }
                override fun onHypothesis(text: String, confidence: Double, accepted: Boolean, reason: String) {
                    handler.post { if (!released && wakeCallbacks.current(wakeToken)) eventLog.append("wake_hypothesis", mapOf(
                        "text" to text, "confidence" to confidence, "accepted" to accepted, "reason" to reason)) }
                }
            })
            publishWakeListening()
            eventLog.append("wake_listening_started", mapOf("audioRoute" to route,
                "duringSceneSpeech" to (speechArbiter.active?.key?.startsWith("scene:") == true),
                "duringFindSpeech" to (speechArbiter.active?.key?.startsWith("find:") == true),
                "speechKey" to (speechArbiter.active?.key ?: "none"),"session" to session.name,
                "motion" to OperationalStateStore.status.value.motion.state.name))
        }.onFailure(::handleWakeError)
    }

    private fun handleWakeWord(hypothesis: String) {
        if (!wakeListeningAllowed()) return
        val current=speechArbiter.active
        if(deferredUrgentWake.speechId!=null) return
        if(deferredUrgentWake.hold(current,hypothesis)) {
            // Stop only the microphone. Keep the urgent sentence and its completion intact.
            stopWakeEngine("wake_waiting_for_urgent",preserveDeferred=true)
            handler.removeCallbacks(wakeStart)
            voiceHandoffPending=true
            AssistanceStateStore.update(voice=VoiceState.PROMPTING,liveTranscript="",
                wakeAcknowledged=hypothesis!="touch",message="已识别唤醒，紧急提示结束后请听提示音说出指令")
            eventLog.append("wake_deferred_for_urgent",mapOf("utteranceId" to current!!.id,"hypothesis" to hypothesis))
            return
        }
        if(current?.key?.startsWith("startup:")==true) {
            speechCallbacks[current.id]?.let {interruptedStartup.hold(startupGeneration,it)}
        }
        // Detach callbacks first: a late TTS completion cannot open an old command window.
        speechArbiter.clear();speechCallbacks.clear();tts?.stop()
        handler.removeCallbacks(followUpStart)
        UsageHelpStatusStore.update(UsageHelpStatus())
        cancelSuccessInfo("新语音指令",resume=false)
        if(findFlow.completion!=null) stopFind("已经找到目标，成功提示已由用户打断")
        else if(findFlow.active) pauseFind("等待新语音指令")
        if(current!=null) eventLog.append("speech_voice_interrupted",mapOf("utteranceId" to current.id,
            "key" to current.key,"priority" to current.priority.name))
        cancelScene("已收到新指令，旧环境任务已取消",resume=false)
        if(fallFlow.active) {fallSpeechStarted=0;fallInputRetryAt=SystemClock.elapsedRealtime()+1_000}
        stopWakeEngine()
        AssistanceStateStore.update(tts=if(ttsReady) TtsState.READY else TtsState.UNAVAILABLE,
            voice=VoiceState.PROMPTING,message="已唤醒，准备接收指令",liveTranscript="",wakeAcknowledged=hypothesis!="touch")
        eventLog.append(if(hypothesis=="touch") "voice_input_touch" else "wake_detected",mapOf("hypothesis" to hypothesis,
            "motion" to OperationalStateStore.status.value.motion.state.name))
        voiceHandoffPending=true
        handler.postDelayed(wakeCommandStart,COMMAND_HANDOFF_DELAY_MS)
    }

    private fun playUsageHelp() {
        if(released || fallFlow.active || AssistanceStateStore.status.value.session !in setOf(SessionState.ACTIVE,SessionState.PAUSED) ||
            speechArbiter.active?.let { !it.key.startsWith("scene:") && !it.key.startsWith("find:") && !it.key.startsWith("usage:") }==true) {
            UsageHelpStatusStore.update(UsageHelpStatus(message="请等当前校准或安全提示结束后再播放帮助"));return
        }
        clearPendingScene("正在播放使用说明")
        cancelScene("正在播放使用说明",resume=false)
        if(findFlow.active) pauseFind("正在播放使用说明")
        val now=SystemClock.elapsedRealtime()
        UsageHelpStatusStore.update(UsageHelpStatus(true,"正在语音介绍，再点帮助可停止"))
        eventLog.append("usage_help_requested",mapOf("source" to "service"))
        val id="helios-usage-${utteranceCounter.incrementAndGet()}"
        dispatchSpeech(SpeechRequest(id,"usage:help",UsageHelp.TEXT,SpeechPriority.ORDINARY,now,now+60_000)) {
            UsageHelpStatusStore.update(UsageHelpStatus());startWakeListening()
        }
        handler.postDelayed({if(speechArbiter.active?.id==id) stopUsageHelp()},90_000)
    }

    private fun stopUsageHelp() {
        eventLog.append("usage_help_stopped",mapOf("source" to "service"))
        UsageHelpStatusStore.update(UsageHelpStatus())
        val active=speechArbiter.cancelPrefix("usage:")
        speechCallbacks.keys.retainAll(speechArbiter.retainedIds())
        if(active!=null) {
            tts?.stop()
            val next=speechArbiter.resume(SystemClock.elapsedRealtime())
            if(next!=null) startSpeech(next) else startWakeListening()
        }
    }

    private fun playPromptTone() {
        playTone(ToneGenerator.TONE_PROP_BEEP2,320)
        val vibrator=getSystemService(android.os.VibratorManager::class.java).defaultVibrator
        if(vibrator.hasVibrator()) vibrator.vibrate(android.os.VibrationEffect.createOneShot(70,android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        eventLog.append("command_ready_cue",mapOf("pattern" to "double_beep","durationMs" to 320,"haptic" to vibrator.hasVibrator()))
    }

    private fun playTone(tone:Int,durationMs:Int) {
        runCatching {
            val stream = audioRoute.promptStream()
            if (toneGenerator == null || toneStream != stream) {
                toneGenerator?.release();toneStream=stream;toneGenerator=ToneGenerator(stream,100)
            }
            toneGenerator?.startTone(tone,durationMs)
        }.onFailure { eventLog.append("prompt_tone_error", mapOf("error" to it.javaClass.name)) }
    }

    private fun startCommandListening(exitConfirmation: Boolean, followUp: Boolean = false) {
        if (released || fallFlow.active || speechArbiter.active!=null) {
            eventLog.append("command_handoff_suppressed", mapOf("reason" to "higher_priority_speech"))
            return
        }
        if (!commandRecognizer.isAvailable) {
            speak("本地中文识别模型不可用，请重新启动辅助模式。") { startWakeListening() }
            return
        }
        handler.removeCallbacks(followUpStart);handler.removeCallbacks(wakeStart)
        stopWakeEngine()
        riskRefreshAfterInput=true
        confirmingExit = exitConfirmation
        val constrained=exitConfirmation || findSelection.target!=null || clarifyingFind
        val openMode=!constrained
        if(followUp && !openMode) { startWakeListening();return }
        val windowMs=if(constrained || !openMode) COMMAND_WINDOW_MS else if(followUp) OpenQuestionPolicy.FOLLOW_UP_MS else OpenQuestionPolicy.WINDOW_MS
        val startRecognition: (Long,CommandRecognizer.Listener)->Unit = if(openMode) commandRecognizer::startOpen else commandRecognizer::start
        val route = runCatching { audioRoute.routeInput() }.getOrDefault("系统默认麦克风")
        AssistanceStateStore.update(
            voice = if (exitConfirmation) VoiceState.CONFIRMING_EXIT else VoiceState.COMMAND_LISTENING,
            liveTranscript="",wakeAcknowledged=if(followUp) false else AssistanceStateStore.status.value.wakeAcknowledged,
            message = if (exitConfirmation) "请说确认退出或继续辅助" else "请说出问题或指令",
        )
        eventLog.append("command_listening_started", mapOf("exitConfirmation" to exitConfirmation, "audioRoute" to route,"openQuestions" to openMode,"followUp" to followUp,"windowMs" to windowMs))
        startRecognition(windowMs, object : CommandRecognizer.Listener {
            override fun onReady() {
                eventLog.append("command_recognizer_ready")
                playPromptTone()
                AssistanceStateStore.update(message = if (exitConfirmation) "请说确认退出或继续辅助" else if(findSelection.target!=null) "请说确认或取消" else if(clarifyingFind) "请说要找的物品名称" else if(followUp) "提示音已响，8秒内可直接追问" else "提示音已响，可自由提问或说出本地指令")
            }
            override fun onPartial(text: String) {
                if(AssistanceStateStore.status.value.voice in setOf(VoiceState.COMMAND_LISTENING,VoiceState.CONFIRMING_EXIT))
                    AssistanceStateStore.update(liveTranscript=text.replace("[unk]", "").replace(Regex("\\s+"), "").take(120))
                eventLog.append("command_partial",mapOf("text" to text))
            }

            override fun onResult(candidates: List<String>) {
                val text = candidates.firstOrNull().orEmpty()
                AssistanceStateStore.update(voice = VoiceState.PROCESSING, message = "正在处理指令", lastCommand = text,liveTranscript=text.replace("[unk]", "").replace(Regex("\\s+"), "").take(120))
                val intent = OpenQuestionPolicy.resolve(text,confirmingExit,constrained)
                eventLog.append("command_result", mapOf("text" to text, "candidates" to candidates, "intent" to intent.name))
                if(followUp && intent==VoiceIntent.UNKNOWN && OpenQuestionPolicy.clean(text)==null) { startWakeListening();return }
                processIntent(intent, text)
            }

            override fun onTimeout() {
                eventLog.append(if(followUp) "question_follow_up_timeout" else "command_timeout",
                    mapOf("exitConfirmation" to confirmingExit,"feedback" to "silent"))
                confirmingExit=false
                findSelection.clear();clarifyingFind=false
                startWakeListening()
            }

            override fun onError(code: Int, message: String) {
                eventLog.append("command_error", mapOf("code" to code, "message" to message))
                confirmingExit = false
                speak("语音暂不可用，请重试。") {startWakeListening()}
            }
        })
    }

    private fun processIntent(intent: VoiceIntent, sourceText: String) {
        if (released) return
        if(fallFlow.active && intent !in setOf(VoiceIntent.PAUSE_ASSISTANCE,VoiceIntent.STOP_ASSISTANCE)) {
            handleFallReply(sourceText,"voice");return
        }
        if (clarifyingFind && intent == VoiceIntent.UNKNOWN) { beginFind(sourceText,bare=true);return }
        eventLog.append("command_intent", mapOf("intent" to intent.name, "source" to sourceText))
        when (intent) {
            VoiceIntent.QUERY_STATUS -> speak(statusMessage()) { startWakeListening() }
            VoiceIntent.PAUSE_ASSISTANCE -> {
                clearPendingScene("辅助已暂停")
                pauseFind("辅助已暂停")
                cancelScene("辅助暂停，环境任务已取消", resume = false)
                fallFlow.cancel(SystemClock.elapsedRealtime())
                stopFallAudio()
                AssistanceStateStore.update(session = SessionState.PAUSED)
                speak("辅助功能已暂停。唤醒词仍然可用。") { startWakeListening() }
            }
            VoiceIntent.RESUME_ASSISTANCE -> {
                AssistanceStateStore.update(session = SessionState.STARTING)
                startupAnnounced=false
                tickStartup(SystemClock.elapsedRealtime())
                if(AssistanceStateStore.status.value.session!=SessionState.ACTIVE) startWakeListening()
            }
            VoiceIntent.STOP_ASSISTANCE -> {
                confirmingExit = true
                AssistanceStateStore.update(voice = VoiceState.CONFIRMING_EXIT, message = "等待退出确认")
                speak("请确认，是否退出辅助模式？请说确认退出或继续辅助。",inputAfterSpeech=true) { startCommandListening(exitConfirmation = true) }
            }
            VoiceIntent.CONFIRM_STOP -> stopWithSpeech("voice_confirmed")
            VoiceIntent.CANCEL_CONFIRMATION -> {
                confirmingExit = false
                speak("已取消退出，辅助模式继续运行。") { startWakeListening() }
            }
            VoiceIntent.ASK_SCENE -> {
                val question=OpenQuestionPolicy.clean(sourceText)
                if(question!=null) requestScene(false,question=question) else startWakeListening()
            }
            VoiceIntent.DESCRIBE_SCENE -> requestScene(false)
            VoiceIntent.DESCRIBE_DETAIL -> requestScene(true)
            VoiceIntent.CONFIRM_FIND -> {
                val target=findSelection.confirm(SystemClock.elapsedRealtime())
                if(target!=null) beginFind(target.name,bare=true,confirmed=true)
                else speak("当前没有待确认的找物目标。") { startWakeListening() }
            }
            VoiceIntent.FIND_OBJECT -> beginFind(sourceText)
            VoiceIntent.FINISH_FIND -> {
                clearPendingScene("已切换到结束找物")
                val existed=findFlow.active || findSelection.target!=null || clarifyingFind
                stopFind("用户结束找物")
                speak(if(existed) "好的，找物已结束。" else "当前没有找物任务。") { startWakeListening() }
            }
            VoiceIntent.RESUME_FIND -> {
                clearPendingScene("已切换到继续找物")
                if (findFlow.active) {
                    findFlow.resume(SystemClock.elapsedRealtime())
                    speak("继续找${findFlow.target?.name}，停稳后重新观察。") { startWakeListening() }
                } else speak("当前没有找物任务，请说找手机或找钥匙。") { startWakeListening() }
            }
            VoiceIntent.CANCEL_TASK -> {
                clearPendingScene("待上传识别已取消")
                val wasFind=findFlow.active || clarifyingFind || findSelection.target!=null
                stopFind("找物已取消")
                sceneFlow.cancel()
                cancelScene("环境分析已取消", resume = false)
                speak(if(wasFind) "找物已取消。" else "环境分析已取消。") { startWakeListening() }
            }
            VoiceIntent.UNKNOWN -> speak("没有听清，请重新唤醒后说完整的问题或指令。") {
                startWakeListening()
            }
        }
    }

    private fun statusMessage():String {
        val session=AssistanceStateStore.status.value.session
        val state=OperationalStateStore.status.value
        val running=when(session) {
            SessionState.PAUSED -> "辅助已暂停"
            SessionState.STARTING -> "辅助正在准备"
            else -> "辅助运行中"
        }
        val task=when {
            pendingSceneRequest.waiting -> "正在等待停稳后识别"
            findFlow.active -> "正在找${findFlow.target?.name}"
            state.device!=DeviceState.STREAMING -> "相机尚未就绪"
            else -> "相机正常"
        }
        return "$running，$task。"
    }

    private fun speak(text: String,inputAfterSpeech:Boolean=false,onDone: () -> Unit) {
        if (released) return
        val now = SystemClock.elapsedRealtime()
        val id = "helios-${utteranceCounter.incrementAndGet()}"
        val request = SpeechRequest(id, id, text, SpeechPriority.ORDINARY, now, now + 15_000,inputAfterSpeech=inputAfterSpeech)
        dispatchSpeech(request, onDone=onDone)
    }

    private fun injectSpeech(priority: SpeechPriority, text: String) {
        if(fallFlow.active) return
        if (released || AssistanceStateStore.status.value.session !in setOf(SessionState.ACTIVE, SessionState.PAUSED)) {
            eventLog.append("speech_injection_ignored", mapOf("reason" to "session_inactive"))
            return
        }
        val now = SystemClock.elapsedRealtime()
        val id = "helios-${utteranceCounter.incrementAndGet()}"
        dispatchSpeech(SpeechRequest(id, "demo:${priority.name}", text, priority, now, now + 5_000)) { startWakeListening() }
    }

    private fun tryAnnounceClearance():Boolean {
        if(released || AssistanceStateStore.status.value.session!=SessionState.ACTIVE || fallFlow.active ||
            sceneConfig.enabled && sceneConfig.testMode) {
            pendingClearance.clear();return false
        }
        val idle=ttsReady && speechArbiter.active==null && !commandRecognizer.isListening && !voiceHandoffPending &&
            sceneFlow.ticket==null && findFlow.pending==null && findFlow.result==null &&
            findSelection.target==null && !clarifyingFind
        val now=SystemClock.elapsedRealtime()
        val current=NavigationStateStore.current
        val transition=pendingClearance.take(now,current,idle)
        if(transition!=null) {
            eventLog.append("clearance_feedback_ready",mapOf("id" to transition.id,"motion" to OperationalStateStore.status.value.motion.state.name))
            dispatchSpeech(transition) {startWakeListening()}
            return true
        }
        if(movingClearanceId?.let {it in speechArbiter.retainedIds()}==true) return false
        val moving=OperationalStateStore.status.value.motion.state==MotionState.MOVING
        val interval=DebugParameterRuntime.current.navigation.clearRepeatMs
        if(!movingClearance.take(now,current,moving,idle,interval)) return false
        val id="helios-current-clear-${utteranceCounter.incrementAndGet()}"
        movingClearanceId=id
        val request=SpeechRequest(id,"risk:current-clear:$id","当前前方未见障碍。",SpeechPriority.ORDINARY,
            now,now+1_500,riskMode=RiskSpeechMode.CLEAR,clearanceFeedback=true,clearanceMovingOnly=true)
        eventLog.append("moving_clearance_feedback",mapOf("id" to id,"intervalMs" to interval,"proofAgeMs" to now-current.timeMs))
        dispatchSpeech(request) {movingClearanceId=null;startWakeListening()}
        return true
    }

    private fun handleLocalRisk(intent: Intent) {
        if(fallFlow.active) return
        if (sceneConfig.enabled && sceneConfig.testMode) {
            if (SystemClock.elapsedRealtime() - lastSceneTestRiskLog >= 5_000) {
                lastSceneTestRiskLog = SystemClock.elapsedRealtime()
                eventLog.append("local_risk_speech_suppressed", mapOf("reason" to "scene_recognition_test_mode"))
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if(intent.getLongExtra(EXTRA_RISK_OBSERVED,now)<parametersAppliedMs) {
            eventLog.append("local_risk_speech_suppressed",mapOf("reason" to "old_parameters"));return
        }
        val expires = intent.getLongExtra(EXTRA_RISK_EXPIRES,0)
        val level = runCatching { RiskLevel.valueOf(intent.getStringExtra(EXTRA_RISK_LEVEL).orEmpty()) }.getOrNull()
        val motion = OperationalStateStore.status.value.motion
        val key = intent.getStringExtra(EXTRA_RISK_KEY).orEmpty()
        val meters = intent.getFloatExtra(EXTRA_RISK_METERS,Float.NaN)
        val reason = if (released) "service_released" else LocalRiskDispatchPolicy.reason(now,expires,level,key,meters,
            AssistanceStateStore.status.value.session,OperationalStateStore.status.value.device,motion)
        if (reason != "ready") {
            eventLog.append("local_risk_speech_suppressed",mapOf("reason" to reason,
                "session" to AssistanceStateStore.status.value.session.name,
                "device" to OperationalStateStore.status.value.device.name,
                "motion" to motion.state.name,"expiresMs" to expires))
            return
        }
        val id = "helios-risk-${intent.getLongExtra(EXTRA_RISK_ID,0)}-${utteranceCounter.incrementAndGet()}"
        val kind = runCatching { RiskEventKind.valueOf(intent.getStringExtra(EXTRA_RISK_KIND).orEmpty()) }
            .getOrDefault(RiskEventKind.OBSTACLE)
        val direction = runCatching { AvoidanceDirection.valueOf(intent.getStringExtra(EXTRA_RISK_DIRECTION).orEmpty()) }
            .getOrNull()?.takeIf { if(kind==RiskEventKind.TURN_GUIDANCE) TurnMotion.guidance(motion)
                else motion.rotationRate <= 0.55 && motion.tiltRotationRate <= 0.50 }
        val rememberedDirection = direction != null && intent.getBooleanExtra(EXTRA_RISK_REMEMBERED,false)
        if (kind in setOf(RiskEventKind.DIRECTION,RiskEventKind.REOBSERVE) && direction == null) {
            eventLog.append("local_risk_speech_suppressed",mapOf("reason" to "direction_stale"))
            return
        }
        val navigating = motion.state == MotionState.MOVING
        val heightBand = runCatching { ImageHeightBand.valueOf(intent.getStringExtra(EXTRA_RISK_HEIGHT_BAND).orEmpty()) }
            .getOrDefault(ImageHeightBand.FRONT)
        val spaceHint=runCatching { SpatialHint.valueOf(intent.getStringExtra(EXTRA_RISK_SPACE).orEmpty()) }.getOrNull()
        val clearance=kind in setOf(RiskEventKind.HEADING_CLEAR,RiskEventKind.AREA_CLEARED)
        val text = if(clearance) LocalRiskSpeech.text(level!!,-1,YoloDetector.labels,kind)
            else if (!navigating) LocalRiskSpeech.stationary(level!!,
            intent.getIntExtra(EXTRA_RISK_CLASS_ID,-1), YoloDetector.labels, heightBand,spaceHint)
            else LocalRiskSpeech.text(level!!, intent.getIntExtra(EXTRA_RISK_CLASS_ID,-1),
            YoloDetector.labels,kind,direction,rememberedDirection,
                intent.getBooleanExtra(EXTRA_RISK_OBSERVATION_ONLY,false),
                heightBand,spaceHint)
        eventLog.append("local_risk_speech_requested",mapOf("id" to id,"key" to key,
            "level" to (level?.name ?: "unknown"),"kind" to kind.name,
            "direction" to (direction?.name ?: "none"),
            "rememberedDirection" to rememberedDirection,
            "classId" to intent.getIntExtra(EXTRA_RISK_CLASS_ID,-1),
            "meters" to meters,"closingMetersPerSecond" to intent.getFloatExtra(EXTRA_RISK_SPEED,0f)))
        val priority = if (kind in setOf(RiskEventKind.LEFT_FORWARD_VIEW,RiskEventKind.AREA_CLEARED,RiskEventKind.HEADING_CLEAR)) SpeechPriority.ORDINARY
            else when (level) {
                RiskLevel.URGENT -> SpeechPriority.URGENT_SAFETY
                RiskLevel.WARNING -> SpeechPriority.WARNING_SAFETY
                else -> SpeechPriority.SAFETY
            }
        val request = SpeechRequest(id,key,text,priority,now,expires,riskMeters=meters,
            riskDirection=if (navigating) direction else null, riskBand=heightBand, riskNavigating=if(clearance) null else navigating,
            riskSpace=spaceHint,riskObservedMs=intent.getLongExtra(EXTRA_RISK_OBSERVED,now))
        riskDetails.offer(request)
        val voice = AssistanceStateStore.status.value.voice
        val occupied = findFlow.active || clarifyingFind || findSelection.target!=null || sceneFlow.ticket != null || voice in setOf(VoiceState.COMMAND_LISTENING,
            VoiceState.CONFIRMING_EXIT, VoiceState.PROMPTING) ||
            speechArbiter.active?.priority?.let { it.ordinal <= SpeechPriority.ORDINARY.ordinal } == true
        val assessment = riskSpeechPolicy.assess(request, now, motion.state == MotionState.MOVING, occupied)
        eventLog.append("risk_speech_policy", mapOf("id" to id, "episode" to key.split(':').getOrNull(2).orEmpty(),
            "mode" to (assessment.mode?.name ?: "SUPPRESSED"), "reason" to assessment.reason,
            "motion" to motion.state.name, "meters" to meters, "height" to request.riskBand!!.name))
        if (clearance && assessment.mode == RiskSpeechMode.CLEAR) {
            val prefix = key.split(':').take(3).joinToString(":") + ":"
            val activeCore=speechArbiter.active?.takeIf {it.key.startsWith("risk:") && !it.riskDetail && it.priority!=SpeechPriority.ORDINARY}?.id
            val removed=speechArbiter.cancelWhere {it.key.startsWith(prefix) && it.id!=activeCore}
            speechCallbacks.keys.retainAll(speechArbiter.retainedIds())
            if (removed != null) tts?.stop()
            pendingClearance.offer(request.copy(riskMode=RiskSpeechMode.CLEAR))
            eventLog.append("clearance_feedback_pending",mapOf("id" to id,"motion" to motion.state.name))
            tryAnnounceClearance()
            return
        }
        if (!assessment.allowed) return
        val prepared = request.copy(riskMode = assessment.mode, text =
            if (assessment.mode == RiskSpeechMode.DIRECTION_UPDATE) LocalRiskSpeech.directionUpdate(direction,level!!,spaceHint)
            else if(assessment.mode==RiskSpeechMode.REPEAT) RiskDetailBuffer.parts(text).first else text)
        if(prepared.text.isBlank()) {
            eventLog.append("local_risk_speech_suppressed",mapOf("reason" to "empty_space_update"))
            return
        }
        riskDetails.offer(prepared)
        if(prepared.riskMode in setOf(RiskSpeechMode.DIRECTION_UPDATE,RiskSpeechMode.CLEAR)) {
            dispatchSpeech(prepared.copy(riskDetail=prepared.riskMode==RiskSpeechMode.DIRECTION_UPDATE,
                riskNavigating=if(prepared.riskMode==RiskSpeechMode.DIRECTION_UPDATE && prepared.riskSpace!=null) null else prepared.riskNavigating)) {startWakeListening()}
        } else {
            val core=prepared.copy(text=RiskDetailBuffer.parts(prepared.text).first)
            dispatchSpeech(core) {speakLatestRiskDetail(core)}
        }
    }

    private fun speakLatestRiskDetail(core:SpeechRequest) {
        val now=SystemClock.elapsedRealtime()
        val detail=riskDetails.take(core,now) {value ->
            val state=OperationalStateStore.status.value
            state.motion.state in setOf(MotionState.STILL,MotionState.MOVING) && !state.motion.stale &&
                NavigationStateStore.valid(value.key,now,value.riskDirection,checkDirection=value.riskSpace==null,
                    band=value.riskBand,space=value.riskSpace)
        }
        if(detail!=null) dispatchSpeech(detail,queueOnly=true) {startWakeListening()} else startWakeListening()
    }

    private fun injectResponseWindow() {
        if(fallFlow.active) return
        if (released || AssistanceStateStore.status.value.session !in setOf(SessionState.ACTIVE, SessionState.PAUSED)) return
        val now = SystemClock.elapsedRealtime()
        val id = "helios-${utteranceCounter.incrementAndGet()}"
        dispatchSpeech(SpeechRequest(id, "demo:response", "测试回应窗口，请在提示音后说话。", SpeechPriority.SAFETY, now, now + 5_000)) {
            handler.postDelayed({ beginSimulatedResponseWindow() }, WAKE_RESUME_DELAY_MS)
        }
    }

    private fun beginSimulatedResponseWindow() {
        if (released) return
        if (!commandRecognizer.isAvailable) { startWakeListening(); return }
        stopWakeEngine()
        val route = runCatching { audioRoute.routeInput() }.getOrDefault("系统默认麦克风")
        AssistanceStateStore.update(voice = VoiceState.COMMAND_LISTENING, message = "模拟安全回应窗口，6 秒后恢复唤醒")
        eventLog.append("simulated_response_window_started", mapOf("audioRoute" to route))
        commandRecognizer.start(COMMAND_WINDOW_MS, object : CommandRecognizer.Listener {
            override fun onReady() { eventLog.append("simulated_response_ready") }
            override fun onResult(candidates: List<String>) = finishSimulatedResponse("result", candidates.firstOrNull().orEmpty())
            override fun onTimeout() = finishSimulatedResponse("timeout", "")
            override fun onError(code: Int, message: String) = finishSimulatedResponse("error:$code", message)
        })
    }

    private fun finishSimulatedResponse(reason: String, text: String) {
        eventLog.append("simulated_response_window_finished", mapOf("reason" to reason, "text" to text))
        val next = speechArbiter.resume(SystemClock.elapsedRealtime())
        if (next != null) startSpeech(next) else startWakeListening()
    }

    private fun dispatchSpeech(request: SpeechRequest, queueOnly: Boolean = false, onDone: () -> Unit) {
        val previous = speechArbiter.active
        val inputOwnsAudio=VoiceInputPriority.defer(request,voiceHandoffPending,commandRecognizer.isListening)
        if(inputOwnsAudio) eventLog.append("speech_deferred_for_voice_input",mapOf("id" to request.id,
            "priority" to request.priority.name,"handoffPending" to voiceHandoffPending))
        val visualOwnsTask=SpeechInterruptionPolicy.deferToVisualTask(request,
            sceneFlow.ticket!=null || findFlow.pending!=null || findFlow.result!=null)
        if(visualOwnsTask) eventLog.append("speech_deferred_for_visual_task",mapOf("id" to request.id))
        val decision = speechArbiter.submit(request, SystemClock.elapsedRealtime(),queueOnly || inputOwnsAudio || visualOwnsTask)
        eventLog.append("speech_decision", mapOf("id" to request.id, "priority" to request.priority.name,
            "decision" to decision.name, "active" to (previous?.id ?: "none")))
        speechCallbacks.keys.retainAll(speechArbiter.retainedIds())
        if (decision == SpeechDecision.DUPLICATE || decision == SpeechDecision.EXPIRED || decision == SpeechDecision.OVERFLOW) return
        speechCallbacks[request.id] = onDone
        if (decision == SpeechDecision.START) {
            if (previous != null) {
                if(previous.id !in speechArbiter.retainedIds()) speechCallbacks.remove(previous.id)
                tts?.stop()
                eventLog.append("speech_interrupted", mapOf("previous" to previous.id, "by" to request.id))
            }
            startSpeech(request)
        }
    }

    private fun startSpeech(request: SpeechRequest) {
        if(VoiceInputPriority.defer(request,voiceHandoffPending,commandRecognizer.isListening) ||
            SpeechInterruptionPolicy.deferToVisualTask(request,sceneFlow.ticket!=null || findFlow.pending!=null || findFlow.result!=null)) {
            speechArbiter.deferActiveForVoice(request.id,SystemClock.elapsedRealtime())
            speechCallbacks.keys.retainAll(speechArbiter.retainedIds())
            eventLog.append("speech_deferred_for_voice_input",mapOf("id" to request.id,"source" to "resumed_speech"))
            return
        }
        if(UsageHelpStatusStore.status.value.playing && !request.key.startsWith("usage:")) UsageHelpStatusStore.update(UsageHelpStatus())
        handler.removeCallbacks(wakeStart)
        if(request.key.startsWith("find:success-info:")) {
            val info=successInfo
            val now=SystemClock.elapsedRealtime()
            if(info==null || info.completion.taskId!=request.key.substringAfterLast(':').toLongOrNull() || !info.valid(now) ||
                !SceneGate.ready(OperationalStateStore.status.value,now,SceneFrames.lastFrameMs,false,0)) {
                speechCallbacks.remove(request.id)
                val next=speechArbiter.complete(request.id,now)
                if(next!=null) startSpeech(next) else startWakeListening()
                return
            }
            eventLog.append("find_success_info_started",mapOf("taskId" to info.completion.taskId))
        }
        if(request.key.startsWith("find:success:")) {
            successSpeechStartedMs=SystemClock.elapsedRealtime()
            if(!SceneGate.ready(OperationalStateStore.status.value,successSpeechStartedMs,SceneFrames.lastFrameMs,false,0)) {
                speechCallbacks.remove(request.id)
                successSpeechId=null
                val next=speechArbiter.complete(request.id,SystemClock.elapsedRealtime())
                if(next!=null) startSpeech(next) else startWakeListening()
                return
            }
            if(findFlow.completion?.taskId!=request.key.substringAfterLast(':').toLongOrNull()) {
                speechCallbacks.remove(request.id)
                val next=speechArbiter.complete(request.id,SystemClock.elapsedRealtime())
                if(next!=null) startSpeech(next) else startWakeListening()
                return
            }
        }
        if (request.key.startsWith("find:feedback:")) {
            if (!findFlow.active || request.key.split(':').getOrNull(2)?.toLongOrNull()!=findFlow.taskId || !findSafe || request.expiresMs<=SystemClock.elapsedRealtime()) {
                speechCallbacks.remove(request.id)
                val next=speechArbiter.complete(request.id,SystemClock.elapsedRealtime())
                if(next!=null) startSpeech(next) else startWakeListening()
                return
            }
        }
        if (request.key.startsWith("find:result:")) {
            val state=OperationalStateStore.status.value
            if (!findFlow.resultValid(SystemClock.elapsedRealtime()) || request.key.split(':').getOrNull(2)?.toLongOrNull()!=findFlow.taskId || request.key.substringAfterLast(':').toLongOrNull()!=findFlow.resultRequestId ||
                !SceneGate.ready(state,SystemClock.elapsedRealtime(),SceneFrames.lastFrameMs,false,sceneBlockedUntil) || !findSafe) {
                speechCallbacks.remove(request.id)
                pauseFind("位置已变化，重新观察")
                val next=speechArbiter.complete(request.id,SystemClock.elapsedRealtime())
                if(next!=null) startSpeech(next) else startWakeListening()
                return
            }
            findFlow.admitSpeech(SystemClock.elapsedRealtime())
            findFlow.result?.let { findGuidance.started(it,SystemClock.elapsedRealtime()) }
            eventLog.append("find_guidance_started",mapOf("taskId" to findFlow.taskId,"requestId" to findFlow.resultRequestId,"guidanceIndex" to findFlow.guidanceCount+1))
        }
        if (request.key.startsWith("scene:")) {
            val id = request.key.substringAfter(':').toLongOrNull()
            tickScene(allowAutomatic = false)
            if (id == null || !sceneFlow.admitSpeech(id, SystemClock.elapsedRealtime())) {
                speechCallbacks.remove(request.id)
                val next = speechArbiter.complete(request.id, SystemClock.elapsedRealtime())
                if (next != null) startSpeech(next) else startWakeListening()
                return
            }
        }
        if(request.clearanceFeedback) {
            val operational=OperationalStateStore.status.value
            if(AssistanceStateStore.status.value.session!=SessionState.ACTIVE || fallFlow.active ||
                operational.device!=DeviceState.STREAMING || operational.motion.stale || !operational.motion.calibrated ||
                !ClearanceProof.ready(SystemClock.elapsedRealtime(),NavigationStateStore.current) ||
                request.clearanceMovingOnly && operational.motion.state!=MotionState.MOVING) {
                eventLog.append("clearance_speech_discarded",mapOf("id" to request.id,"reason" to "current_scene_or_motion_changed"))
                speechCallbacks.remove(request.id)
                val next=speechArbiter.complete(request.id,SystemClock.elapsedRealtime())
                if(next!=null) startSpeech(next) else startWakeListening()
                return
            }
        }
        if (request.key.startsWith("risk:nav:")) {
            val now = SystemClock.elapsedRealtime()
            val operational = OperationalStateStore.status.value
            val level = runCatching { RiskLevel.valueOf(request.key.split(':')[4]) }.getOrNull()
            val ready = LocalRiskDispatchPolicy.reason(now,request.expiresMs,level,request.key,
                request.riskMeters ?: Float.NaN,AssistanceStateStore.status.value.session,
                operational.device,operational.motion) == "ready" &&
                NavigationStateStore.valid(request.key,now,request.riskDirection,checkDirection=request.riskSpace==null && request.riskNavigating != false,band=request.riskBand,space=request.riskSpace) &&
                (request.riskNavigating == null || request.riskNavigating == (operational.motion.state == MotionState.MOVING)) &&
                (request.riskDirection == null || (request.key.split(':')[3]==RiskEventKind.TURN_GUIDANCE.name && TurnMotion.guidance(operational.motion)) || (operational.motion.rotationRate <= 0.55 &&
                    operational.motion.tiltRotationRate <= 0.50))
            if (!ready) {
                eventLog.append("local_risk_speech_suppressed",mapOf("reason" to "navigation_superseded_before_tts",
                    "key" to request.key))
                speechCallbacks.remove(request.id)
                val next = speechArbiter.complete(request.id,now)
                if (next != null) startSpeech(next) else startWakeListening()
                return
            }
        }
        if (request.key.startsWith("scene:")) {
            val now = SystemClock.elapsedRealtime()
            eventLog.append("scene_speech_started", mapOf("requestId" to request.key.substringAfter(':'),
                "request_to_tts_ms" to (sceneFlow.ticket?.let { now - it.createdMs } ?: 0L),
                "result_queue_ms" to now - request.createdMs))
        }
        handler.removeCallbacks(followUpStart)
        if(VoiceInputPriority.urgentTakesInput(request) &&
            deferredUrgentWake.speechId!=request.id &&
            (voiceHandoffPending || commandRecognizer.isListening)) {
            eventLog.append("urgent_speech_took_voice_input",mapOf("utteranceId" to request.id,
                "commandListening" to commandRecognizer.isListening,"handoffPending" to voiceHandoffPending))
            stopWakeEngine("urgent_speech")
            stopCommandInput("urgent_speech")
            confirmingExit=false
            findSelection.clear();clarifyingFind=false
            clearPendingScene("紧急告警已退出本次语音交互")
            AssistanceStateStore.update(wakeAcknowledged=false,liveTranscript="")
        }
        val reuseWake=VoiceInputPriority.reuseWake(wakeWordEngine.isListening,wakeListeningAllowed(),commandRecognizer.isListening)
        if(!reuseWake) stopWakeEngine("speech_start",preserveDeferred=deferredUrgentWake.speechId==request.id)
        else eventLog.append("wake_listening_reused",mapOf("utteranceId" to request.id,"speechKey" to request.key))
        stopCommandInput("speech:"+request.key)
        if(request.key.startsWith("fall:") && fallFlow.state==FallState.HELPING) {
            fallAudioProblem=runCatching {audioRoute.routeHelpSpeaker()}.getOrDefault("外放路由失败")
        }
        AssistanceStateStore.update(voice = VoiceState.PROCESSING, message = request.text)
        val engine = tts
        if (!ttsReady || engine == null) {
            handler.post { finishSpeech(request.id, TextToSpeech.ERROR) }
            return
        }
        if (request.priority in setOf(SpeechPriority.SAFETY,SpeechPriority.WARNING_SAFETY,SpeechPriority.URGENT_SAFETY,SpeechPriority.FALL) &&
            request.riskMode in setOf(null, RiskSpeechMode.ALERT)) {
            if(request.priority==SpeechPriority.FALL || !request.key.startsWith("risk:")) {
                pauseFind("安全提醒中",request.priority==SpeechPriority.FALL)
            } else if(findFlow.result!=null && findFlow.completion==null) {
                // Drop an old direction, but retain an in-flight stationary observation.
                pauseFind("安全告警已中断本次位置引导")
                findFlow.waitForAdjustment("等待运动后重新停稳")
            }
            sceneBlockedUntil = SystemClock.elapsedRealtime() + 5_000
            cancelScene("新的安全风险已打断环境描述", resume = false)
        } else if (sceneFlow.ticket != null && !request.key.startsWith("scene:") && request.riskMode != RiskSpeechMode.CLEAR) {
            // Only a sentence actually starting may invalidate an environment result.
            cancelScene("新的播报已结束原环境任务", resume = false)
        }
        val usage = audioRoute.speechUsage()
        val settleMs = audioRoute.playbackSettleMs()
        if (settleMs > 0) {
            eventLog.append("tts_route_settling",mapOf("utteranceId" to request.id,"delayMs" to settleMs))
            handler.postDelayed({
                if (!released && speechArbiter.active?.id == request.id) startSpeech(request)
            },settleMs)
            return
        }
        engine.setSpeechRate(DebugParameterRuntime.current["speechRate"].toFloat())
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val spokenText=WakeListeningPolicy.spokenText(request.text)
        val result = engine.speak(spokenText, TextToSpeech.QUEUE_FLUSH, Bundle(), request.id)
        if (result != TextToSpeech.ERROR) riskSpeechPolicy.started(request, SystemClock.elapsedRealtime(), OperationalStateStore.status.value.motion.state == MotionState.MOVING)
        eventLog.append("tts_requested", mapOf("utteranceId" to request.id, "text" to spokenText,
            "priority" to request.priority.name,"riskDetail" to request.riskDetail,"audioUsage" to usage,"audioRoute" to audioRoute.diagnostics()))
        if (result == TextToSpeech.ERROR) finishSpeech(request.id, result)
        else startWakeListening()
    }

    private fun finishSpeech(utteranceId: String?, errorCode: Int?) {
        handler.post {
            if (released) return@post
            val id = utteranceId ?: return@post
            if (speechArbiter.active?.id != id) return@post
            if(speechArbiter.active?.key?.startsWith("usage:")==true) UsageHelpStatusStore.update(UsageHelpStatus())
            if(errorCode!=null && speechArbiter.active?.key?.startsWith("startup:")==true) {
                tts?.stop();speechArbiter.clear();speechCallbacks.clear()
                failStartup("启动语音播放失败，请检查声音后重试")
                return@post
            }
            AssistanceStateStore.update(tts = if (errorCode == null) TtsState.COMPLETED else TtsState.ERROR)
            if(errorCode!=null && speechArbiter.active?.key?.startsWith("find:success-info:")==true) {
                successInfo=null;speechCallbacks.remove(id)
                eventLog.append("find_success_info_tts_error")
                val next=speechArbiter.complete(id,SystemClock.elapsedRealtime())
                if(next!=null) startSpeech(next) else startWakeListening()
                return@post
            }
            if(errorCode!=null && speechArbiter.active?.priority==SpeechPriority.FIND_SUCCESS) {
                speechCallbacks.remove(id);speechArbiter.complete(id,SystemClock.elapsedRealtime(),advance=false)
                successSpeechId=null;successSpeechFailures++
                eventLog.append("find_success_tts_error",mapOf("taskId" to findFlow.taskId,"attempt" to successSpeechFailures))
                if(successSpeechFailures>=2) stopFind("已经找到${findFlow.target?.name}，但成功提示未能播出")
                val next=speechArbiter.resume(SystemClock.elapsedRealtime())
                if(next!=null) startSpeech(next) else startWakeListening()
                return@post
            }
            if(errorCode!=null && speechArbiter.active?.key?.startsWith("find:result:")==true) {
                speechArbiter.complete(id,SystemClock.elapsedRealtime(),advance=false)
                speechCallbacks.remove(id)
                stopFind("找物结果播报失败，任务已结束。")
                startWakeListening();return@post
            }
            val fields = if (errorCode == null) {
                mapOf("utteranceId" to (utteranceId ?: "unknown"))
            } else {
                mapOf("utteranceId" to (utteranceId ?: "unknown"), "errorCode" to errorCode)
            }
            eventLog.append(if (errorCode == null) "tts_completed" else "tts_error", fields)
            if (errorCode != null) speechArbiter.active?.let { riskSpeechPolicy.failed(it) }
            if(deferredUrgentWake.speechId==id) {
                val hypothesis=deferredUrgentWake.take()!!
                speechCallbacks.remove(id)
                speechArbiter.complete(id,SystemClock.elapsedRealtime(),advance=false)
                voiceHandoffPending=false
                eventLog.append("wake_resumed_after_urgent",mapOf("utteranceId" to id,"ttsError" to (errorCode!=null)))
                handleWakeWord(hypothesis)
                return@post
            }
            val responseWindow = speechArbiter.active?.inputAfterSpeech==true || speechArbiter.active?.key == "demo:response" || speechArbiter.active?.key?.startsWith("fall:")==true
            if(errorCode!=null && speechArbiter.active?.key?.startsWith("fall:")==true) fallAudioProblem="语音播放失败，请使用回应按钮；本地求助声音未确认播放。"
            speechArbiter.active?.key?.takeIf { it.startsWith("scene:") }?.substringAfter(':')?.toLongOrNull()?.let { sceneFlow.complete(it);sceneQuestions.remove(it);if(manualSceneTicket==it) manualSceneTicket=null }
            if(speechArbiter.active?.priority==SpeechPriority.FIND_SUCCESS || speechArbiter.active?.key?.startsWith("find:success-info:")==true) {
                val action=speechCallbacks.remove(id)
                speechArbiter.complete(id,SystemClock.elapsedRealtime(),advance=false)
                action?.invoke()
                val next=speechArbiter.resume(SystemClock.elapsedRealtime())
                if(next!=null) startSpeech(next) else startWakeListening()
                return@post
            }
            val action = speechCallbacks.remove(id).takeUnless { errorCode!=null }
            speechArbiter.complete(id,SystemClock.elapsedRealtime(),advance=false)
            // Completion bookkeeping belongs to the finished task even if warnings wait.
            // Input-producing callbacks keep the channel before queued background audio.
            action?.invoke()
            if(!responseWindow && !commandRecognizer.isListening && !voiceHandoffPending) {
                val next=speechArbiter.resume(SystemClock.elapsedRealtime())
                if(next!=null) startSpeech(next) else if(action==null) startWakeListening()
            }
        }
    }

    private fun handleWakeError(error: Throwable) {
        if (released) return
        stopWakeEngine()
        AssistanceStateStore.update(voice = VoiceState.ERROR, message = "唤醒监听出错，正在尝试恢复")
        eventLog.append("wake_listening_error", mapOf("error" to error.javaClass.name, "message" to (error.message ?: "")))
        handler.postDelayed({ if (!released) startWakeListening() }, RECOVERY_DELAY_MS)
    }

    private fun stopWithSpeech(reason:String,announcement:String="辅助模式已退出。") {
        if(released || AssistanceStateStore.status.value.session==SessionState.STOPPING) return
        clearPendingScene("辅助已结束")
        startupGeneration++;startupFlow.stop();sessionWasActivated=false
        handler.removeCallbacks(followUpStart);handler.removeCallbacks(wakeStart)
        stopFind("辅助退出，找物已取消")
        cancelScene("辅助退出，环境任务已取消",resume=false)
        if(fallFlow.active) fallFlow.cancel(SystemClock.elapsedRealtime())
        stopFallAudio() // discard active/queued speech before the exit feedback
        sensorSession?.close();sensorSession=null
        if(cpuWakeLock?.isHeld==true) cpuWakeLock?.release()
        AssistanceStateStore.update(session=SessionState.STOPPING,voice=VoiceState.UNAVAILABLE,
            message=if(reason=="runtime_fault") "辅助已停止。${finalFault.orEmpty()}" else "辅助已结束")
        eventLog.append("assistance_stop_requested",mapOf("reason" to reason))
        // TTS completion/error is not trusted as the only release path.
        handler.postDelayed({ if(!released) stopAssistance() },if(reason=="runtime_fault") 10_000 else 3_500)
        speak(announcement) { stopAssistance() }
    }

    private fun stopAssistance() {
        releaseResources()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseResources() {
        if (released) return
        riskRefreshAfterInput=false
        pendingClearance.clear();movingClearance.reset();movingClearanceId=null
        deferredUrgentWake.clear()
        stopFind("辅助已退出，找物已取消")
        findClient.close()
        clearPendingScene("辅助已退出")
        cancelScene("辅助已退出", resume = false)
        sceneClient.close()
        sceneMotionGate.reset(); riskSpeechPolicy.reset();riskDetails.clear()
        released = true
        startupGeneration++;startupFlow.stop()
        UsageHelpStatusStore.update(UsageHelpStatus())
        StartupStatusStore.update(if(finalFault!=null) StartupStatus(message=finalFault!!) else StartupStatus())
        sensorSession?.close();sensorSession=null
        if(cpuWakeLock?.isHeld==true) cpuWakeLock?.release()
        eventLog.append("background_session_stopped",mapOf("wakeLockHeld" to (cpuWakeLock?.isHeld==true)))
        fallInputGeneration++;fallInputPending=false;fallListening=false
        fallFlow.reset();FallSignals.detector.reset();FallStatusStore.update(FallStatus())
        OperationalStateStore.update(fall=FallState.IDLE)
        handler.removeCallbacksAndMessages(null)
        stopCommandInput()
        wakeWordEngine.release()
        commandRecognizer.release()
        audioRoute.releaseAllRoutes()
        toneGenerator?.release()
        toneGenerator = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        speechArbiter.clear()
        speechCallbacks.clear()
        runCatching { eventLog.append("voice_resources_released") }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "辅助模式状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示 Helios 语音辅助模式是否正在运行"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopService = PendingIntent.getService(
            this, 1, Intent(this, AssistanceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_helios)
            .setContentTitle("Helios 语音辅助")
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "退出辅助", stopService)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
    }

    companion object {
        const val ACTION_DEBUG_PARAMETERS = "com.helios.app.DEBUG_PARAMETERS"
        const val ACTION_SCENE_CONFIG = "com.helios.app.SCENE_CONFIG"
        const val ACTION_SCENE_BRIEF = "com.helios.app.SCENE_BRIEF"
        const val ACTION_SCENE_DETAIL = "com.helios.app.SCENE_DETAIL"
        const val ACTION_SCENE_CANCEL = "com.helios.app.SCENE_CANCEL"
        const val ACTION_USAGE_HELP = "com.helios.app.USAGE_HELP"
        const val ACTION_USAGE_HELP_STOP = "com.helios.app.USAGE_HELP_STOP"
        const val ACTION_VOICE_INPUT = "com.helios.app.VOICE_INPUT"
        const val ACTION_RECALIBRATE = "com.helios.app.RECALIBRATE"
        const val ACTION_START = "com.helios.app.action.START_ASSISTANCE"
        const val ACTION_STOP = "com.helios.app.action.STOP_ASSISTANCE"
        const val ACTION_PAUSE = "com.helios.app.action.PAUSE_ASSISTANCE"
        const val ACTION_RESUME = "com.helios.app.action.RESUME_ASSISTANCE"
        const val ACTION_INJECT_SAFETY = "com.helios.app.action.INJECT_SAFETY"
        const val ACTION_INJECT_ORDINARY = "com.helios.app.action.INJECT_ORDINARY"
        const val ACTION_INJECT_RESPONSE = "com.helios.app.action.INJECT_RESPONSE"
        const val ACTION_RESET_FALL_TEST = "com.helios.app.action.RESET_FALL_TEST"
        const val ACTION_FALL_SAMPLE = "com.helios.app.action.FALL_SAMPLE"
        const val ACTION_INJECT_FALL = "com.helios.app.action.INJECT_FALL"
        const val ACTION_FALL_REPLY = "com.helios.app.action.FALL_REPLY"
        const val EXTRA_FALL_REPLY = "fallReply"
        const val ACTION_LOCAL_RISK = "com.helios.app.action.LOCAL_RISK"
        const val EXTRA_RISK_ID = "riskId"
        const val EXTRA_RISK_KEY = "riskKey"
        const val EXTRA_RISK_LEVEL = "riskLevel"
        const val EXTRA_RISK_CLASS_ID = "riskClassId"
        const val EXTRA_RISK_METERS = "riskMeters"
        const val EXTRA_RISK_SPEED = "riskClosingSpeed"
        const val EXTRA_RISK_KIND = "riskKind"
        const val EXTRA_RISK_DIRECTION = "riskDirection"
        const val EXTRA_RISK_SPACE = "risk_space"
        const val EXTRA_RISK_HEIGHT_BAND = "riskHeightBand"
        const val EXTRA_RISK_OBSERVATION_ONLY = "riskObservationOnly"
        const val EXTRA_RISK_REMEMBERED = "riskRememberedDirection"
        const val EXTRA_RISK_OBSERVED = "riskObservedMs"
        const val EXTRA_RISK_EXPIRES = "riskExpiresMs"
        private const val CHANNEL_ID = "assistance_status"
        private const val NOTIFICATION_ID = 1001
        private const val COMMAND_WINDOW_MS = 6_000L
        private const val COMMAND_HANDOFF_DELAY_MS = 300L
        private const val WAKE_RESUME_DELAY_MS = 900L
        private const val RECOVERY_DELAY_MS = 1_500L
    }
}
