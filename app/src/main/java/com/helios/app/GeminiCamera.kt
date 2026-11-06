package com.helios.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.orbbec.obsensor.*
import com.orbbec.obsensor.types.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class CameraSample(val count: Long = 0, val timestampUs: String = "—", val receivedMs: Long = 0, val detail: String = "等待数据") {
    fun health(now: Long): String = when { receivedMs == 0L -> "无数据"; now - receivedMs > 2000 -> "陈旧 ${now - receivedMs} ms"; else -> "正常 ${now - receivedMs} ms" }
}

/** Control calls serialize on one worker; each capture thread exclusively owns its fetched frames. */
class GeminiCamera(private val context: Context, private val requestPermission: (UsbDevice) -> Unit,
    private val onRisk: (LocalRiskEvent) -> Unit) {
    private val usb = context.getSystemService(UsbManager::class.java)
    private val log = EventLog(context)
    private val control = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var enabled = false
    @Volatile private var disposed = false
    @Volatile var message = "未启动相机"
        private set
    @Volatile var deviceDescription = "Gemini 2 未连接"
        private set
    @Volatile var color = CameraSample()
        private set
    @Volatile var depth = CameraSample()
        private set
    @Volatile var accel = CameraSample()
        private set
    @Volatile var gyro = CameraSample()
        private set
    val acquired = AtomicLong()
    val released = AtomicLong()
    val previewCreated = AtomicLong()
    val previewReleased = AtomicLong()
    val closeErrors = AtomicLong()
    val processingRate = FrameRate()
    val detectionTiming = DetectionTiming()
    val arrivalTiming = DetectionTiming()
    @Volatile private var latestTargets: List<TargetBox> = emptyList()
    @Volatile private var latestDetectionSize = 0 to 0
    @Volatile var detectorReady = false
        private set
    @Volatile var detectorFailed = false
        private set
    @Volatile var detectionStatus = "YOLO 尚未启动"
        private set
    @Volatile var riskStatus = "移动风险等待有效数据"
        private set
    private val corridorDiagnosticRequested=AtomicBoolean(false)
    fun requestCorridorDiagnostic() { corridorDiagnosticRequested.set(true) }
    private val riskRefreshRequested=AtomicBoolean(false)
    fun requestRiskRefresh() { riskRefreshRequested.set(true) }
    private var navigationConfig=DebugParameterRuntime.current.navigation
    private var navigation = ObstacleNavigation(navigationConfig,useCorridorPlanner=true,useImageHeightLayers=true)
    @Volatile private var colorProjection: HorizontalProjection? = null
    @Volatile var avoidanceStatus = "避让方向：等待有效数据"
        private set
    private var lastRiskReason = ""
    private var lastGuidanceLogMs = 0L
    private var lastGuidanceReason = ""
    val orientation = GyroOrientation()
    val motion = MotionTracker().apply {configure(DebugParameterRuntime.current.motion)}
    @Volatile var motionSnapshot = MotionSnapshot()
        private set
    @Volatile var previewEnabled = false
    @Volatile var previews = LatestResource<PreviewPair>()
        private set
    private var sdk: OBContext? = null
    private var device: Device? = null
    private var videoPipeline: Pipeline? = null
    private var imuPipeline: Pipeline? = null
    private var videoThread: Thread? = null
    private var imuThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val detached = AtomicBoolean(false)
    private var usbKey: String? = null
    private var requestedKey: String? = null
    private var startedMs = 0L
    private var lastLogMs = 0L
    private var nextRetryMs = 0L
    @Volatile private var calibrationRequested = false

    init { control.scheduleWithFixedDelay({ if (!disposed) runCatching { tick() }.onFailure { fail("camera_control_error", it) } }, 0, 1, TimeUnit.SECONDS) }

    fun start() {
        if (disposed) return
        enabled = true
        if (!running.get()) OperationalStateStore.update(device = DeviceState.CONNECTING)
        control.execute { requestedKey = null; nextRetryMs = 0; tick() }
    }
    fun stop() {
        enabled = false
        OperationalStateStore.update(device = DeviceState.DISCONNECTED, motion = MotionSnapshot())
        if (!disposed) control.execute { shutdownSession("user_or_background"); message = "已停止，帧与缓存已释放" }
    }
    fun dispose() {
        enabled = false
        disposed = true
        OperationalStateStore.update(device = DeviceState.DISCONNECTED, motion = MotionSnapshot())
        control.execute { shutdownSession("activity_destroyed") }
        control.shutdown()
    }
    fun permissionResult(granted: Boolean) {
        if (disposed) return
        control.execute {
            log.append("camera_usb_permission", mapOf("granted" to granted))
            if (!granted) message = "USB 授权被拒绝，请点击开启重试"
            else tick()
        }
    }
    fun usbChanged() { if (!disposed) control.execute { tick() } }
    fun calibrateWornOrientation() {
        calibrationRequested = true
        if (running.get()) {
            motion.requestCalibration()
            calibrationRequested = false
        }
        motionSnapshot = MotionSnapshot()
        OperationalStateStore.update(motion = MotionSnapshot())
        FallSignals.detector.reset()
        log.append("motion_calibration_requested")
    }

    private fun tick() {
        if (!enabled || disposed) return
        val target = usb.deviceList.values.firstOrNull { it.vendorId == 0x2bc5 }
        val key = target?.let { "${it.deviceName}:${it.deviceId}" }
        if ((usbKey != null && usbKey != key) || detached.getAndSet(false)) shutdownSession("usb_detached")
        if (target == null) {
            requestedKey = null
            message = "相机已断开，等待重新插入后自动恢复"
            if (OperationalStateStore.status.value.device != DeviceState.DISCONNECTED) OperationalStateStore.update(device = DeviceState.DISCONNECTED, motion = MotionSnapshot())
            return
        }
        if (!usb.hasPermission(target)) {
            message = "等待 USB 相机授权"
            if (requestedKey != key) { requestedKey = key; requestPermission(target) }
            return
        }
        if (SystemClock.elapsedRealtime() < nextRetryMs) return
        if (sdk == null) {
            usbKey = key
            message = "正在初始化 Orbbec SDK"
            val configFile = File(context.filesDir, "helios-orbbec.xml")
            // Preserve vendor device settings; explicitly bound SDK-owned queues as well as our preview slot.
            val configText = context.assets.open("OrbbecSDKConfig.xml").bufferedReader().use { it.readText() }
                .replace(Regex("<PipelineFrameQueueSize>.*?</PipelineFrameQueueSize>"), "<PipelineFrameQueueSize>2</PipelineFrameQueueSize>")
                .replace(Regex("<FrameProcessingBlockQueueSize>.*?</FrameProcessingBlockQueueSize>"), "<FrameProcessingBlockQueueSize>2</FrameProcessingBlockQueueSize>")
            configFile.writeText(configText)
            sdk = OBContext(OrbbecAndroidContext(context), configFile.absolutePath, object : DeviceChangedCallback {
                override fun onDeviceAttach(list: DeviceList) { list.close() }
                override fun onDeviceDetach(list: DeviceList) { try { detached.set(true) } finally { list.close() } }
            })
            log.append("camera_sdk_ready", mapOf("version" to OBContext.getVersionName(), "coreVersion" to OBContext.getCoreVersionName()))
        }
        if (device == null) {
            sdk!!.queryDevices().use { list -> if (list.deviceCount > 0) device = list.getDevice(0) }
            if (device == null) { message = "USB 已授权，等待 SDK 发现相机"; return }
            startStreams()
        }
        if (running.get() && OperationalStateStore.status.value.device != DeviceState.STREAMING)
            OperationalStateStore.update(device = DeviceState.STREAMING)
        val now = SystemClock.elapsedRealtime()
        val nextMotion = motion.snapshot(now)
        if (nextMotion.calibrated && nextMotion.gyroBias.size == 3 && nextMotion.gyroBias != motionSnapshot.gyroBias &&
            orientation.calibrateStationaryBias(nextMotion.gyroBias)) {
            log.append("gyro_bias_calibrated", mapOf("biasRadS" to nextMotion.gyroBias))
        }
        if (nextMotion.state != motionSnapshot.state || nextMotion.calibrated != motionSnapshot.calibrated || nextMotion.stale != motionSnapshot.stale) {
            log.append("motion_state", mapOf("state" to nextMotion.state.name, "stale" to nextMotion.stale,
                "calibrated" to nextMotion.calibrated, "dynamicAcceleration" to nextMotion.dynamicAcceleration,
                "movementAcceleration" to nextMotion.movementAcceleration,
                "peakAcceleration" to nextMotion.peakAcceleration, "shakeReason" to nextMotion.shakeReason,
                "rotationRate" to nextMotion.rotationRate, "tiltRotationRate" to nextMotion.tiltRotationRate,
                "gravity" to nextMotion.gravity, "calibrationReason" to nextMotion.calibrationReason,
                "orientationDelta" to nextMotion.orientationDelta,
                "visualMotion" to nextMotion.visualMotion.name, "visualConsensusTiles" to nextMotion.visualConsensusTiles,
                "source" to nextMotion.source))
        }
        motionSnapshot = nextMotion
        if (nextMotion != OperationalStateStore.status.value.motion) OperationalStateStore.update(motion = nextMotion)
        if (now - lastLogMs >= 5000) {
            lastLogMs = now
            log.append("camera_health", mapOf(
                "colorCount" to color.count, "depthCount" to depth.count, "accelCount" to accel.count, "gyroCount" to gyro.count,
                "processingFps" to processingRate.fps, "alignment" to "depth_to_color",
                "detection" to detectionStatus, "processingLatency" to detectionTiming.summary(),
                "arrivalLatency" to arrivalTiming.summary(),
                "targetFrameSize" to "${latestDetectionSize.first}x${latestDetectionSize.second}",
                "targets" to latestTargets.map {
                    val (w,h) = latestDetectionSize
                    mapOf("classId" to it.classId, "confidence" to it.confidence,
                        "box" to listOf(it.left,it.top,it.right,it.bottom),
                        "edgeClipped" to (w > 0 && h > 0 && (it.left <= w*0.01f || it.top <= h*0.01f || it.right >= w*0.99f || it.bottom >= h*0.99f)),
                        "meters" to (it.distance.meters ?: "unknown"), "validRatio" to it.distance.validRatio,
                        "distanceReason" to it.distance.reason)
                },
                "colorHealth" to color.health(now), "depthHealth" to depth.health(now), "accelHealth" to accel.health(now), "gyroHealth" to gyro.health(now),
                "motionState" to nextMotion.state.name, "motionStale" to nextMotion.stale, "motionCalibrated" to nextMotion.calibrated,
                "motionPeakAcceleration" to nextMotion.peakAcceleration,
                "motionMovementAcceleration" to nextMotion.movementAcceleration,
                "motionTiltRotationRate" to nextMotion.tiltRotationRate, "shakeReason" to nextMotion.shakeReason,
                "visualMotion" to nextMotion.visualMotion.name, "visualConsensusTiles" to nextMotion.visualConsensusTiles,
                "motionSource" to nextMotion.source,
                "framesAcquired" to acquired.get(), "framesReleased" to released.get(), "closeErrors" to closeErrors.get(),
                "previewCreated" to previewCreated.get(), "previewReleased" to previewReleased.get(), "previewPending" to previews.size(),
            ))
        }
        if (running.get() && now - maxOf(color.receivedMs, depth.receivedMs, startedMs) > 8000) {
            message = "视频流陈旧，正在释放并重连"
            OperationalStateStore.update(device = DeviceState.STALE, motion = MotionSnapshot())
            shutdownSession("video_stale")
            nextRetryMs = now + 2000
        }
    }

    private fun startStreams() {
        val current = checkNotNull(device)
        val info = current.info
        deviceDescription = "${info.name} · 固件 ${info.firmwareVersion} · ${info.connectionType}"
        log.append("camera_connected", mapOf("name" to info.name, "firmware" to info.firmwareVersion, "connection" to info.connectionType))
        color = CameraSample(); depth = CameraSample(); accel = CameraSample(); gyro = CameraSample()
        processingRate.reset(); orientation.reset(clearBias = true); detectionTiming.reset(); arrivalTiming.reset(); latestTargets=emptyList()
        navigation.reset(); riskStatus = "移动风险等待有效数据"; avoidanceStatus = "避让方向：等待有效数据"; lastRiskReason = ""; lastGuidanceReason = ""; lastGuidanceLogMs = 0L
        motion.reset(); motionSnapshot = MotionSnapshot()
        if (calibrationRequested) {
            motion.requestCalibration()
            calibrationRequested = false
        }
        previews.close()
        previews = LatestResource()
        startedMs = SystemClock.elapsedRealtime()
        val video = Pipeline(current)
        videoPipeline = video
        Config().use { config ->
            selectVideo(video, config, SensorType.COLOR, Format.MJPG, StreamType.COLOR)
            selectVideo(video, config, SensorType.DEPTH, Format.Y16, StreamType.DEPTH)
            config.setFrameAggregateOutputMode(FrameAggregateOutputMode.OB_FRAME_AGGREGATE_OUTPUT_ALL_TYPE_FRAME_REQUIRE)
            video.enableFrameSync()
            video.start(config)
        }
        running.set(true)
        OperationalStateStore.update(device = DeviceState.STREAMING)
        videoThread = Thread({ videoLoop(video) }, "helios-rgb-depth").also { it.start() }
        try {
            val imu = Pipeline(current)
            imuPipeline = imu
            Config().use { config ->
                config.enableAccelStream(); config.enableGyroStream()
                config.setFrameAggregateOutputMode(FrameAggregateOutputMode.OB_FRAME_AGGREGATE_OUTPUT_ANY_SITUATION)
                imu.start(config)
            }
            imuThread = Thread({ imuLoop(imu) }, "helios-imu").also { it.start() }
            message = "采集中，上方 RGB、下方深度"
        } catch (error: Exception) {
            message = "视频已启动；IMU 启动失败：${error.message}"
            log.append("camera_imu_start_error", mapOf("error" to error.toString()))
            closeResource(imuPipeline); imuPipeline = null
        }
    }

    private fun selectVideo(pipeline: Pipeline, config: Config, sensor: SensorType, format: Format, stream: StreamType) {
        pipeline.getStreamProfileList(sensor).use { profiles ->
            val modes = mutableListOf<VideoMode>()
            for (i in 0 until profiles.count) {
                profiles.getProfile(i).use { owner ->
                    val p = owner.`as`<VideoStreamProfile>(stream) // alias; only owner closes
                    if (p.format == format) {
                        val intrinsic = runCatching { p.intrinsic.bytes }.getOrNull()
                        val mode = intrinsic?.let { VideoMode.fromIntrinsic(i, p.width, p.height, p.fps, it) }
                            ?: VideoMode(i, p.width, p.height, p.fps)
                        modes.add(mode)
                        log.append("camera_profile_candidate", mapOf("stream" to stream.name,
                            "width" to mode.width, "height" to mode.height, "fps" to mode.fps,
                            "horizontalFov" to (mode.horizontalFov ?: "unknown"), "verticalFov" to (mode.verticalFov ?: "unknown")))
                    }
                }
            }
            check(modes.isNotEmpty()) { "$sensor 不支持预览格式 $format" }
            val selected = VideoModeSelection.select(modes, sensor == SensorType.COLOR)
            if (sensor == SensorType.COLOR) colorProjection = selected.projection
            profiles.getProfile(selected.index).use { profile ->
                config.enableStream(profile)
                val p = profile.`as`<VideoStreamProfile>(stream)
                log.append("camera_stream_profile", mapOf("stream" to stream.name, "width" to p.width, "height" to p.height, "fps" to p.fps, "format" to p.format.name,
                    "horizontalFov" to (selected.horizontalFov ?: "unknown"), "verticalFov" to (selected.verticalFov ?: "unknown"),
                    "fx" to (selected.projection?.fx ?: "unknown"), "cx" to (selected.projection?.cx ?: "unknown"),
                    "selection" to if (selected.angularCoverage != null) "widest_intrinsic_lowest_pixels" else "aspect_fallback"))
            }
        }
    }

    private inline fun <T : Frame> owned(frame: T?, action: (T) -> Unit) {
        if (frame == null) return
        acquired.incrementAndGet()
        try { action(frame) } finally {
            try { frame.close(); released.incrementAndGet() } catch (e: Exception) { closeErrors.incrementAndGet(); throw e }
        }
    }

    private fun videoLoop(pipeline: Pipeline) {
        detectorReady=false;detectorFailed=false
        val visualEstimator = VisualMotionEstimator()
        var latestVisualEvidence = VisualMotionEvidence()
        var lastVisualSampleMs = 0L
        var visualAvailable = true
        var detector: YoloDetector? = null
        try {
            detector = YoloDetector(context)
            detectorReady=true
            detectionStatus = "${detector.modelName} · ${detector.inputSize} · ${detector.backend}"
            log.append("yolo_ready", mapOf("model" to "${detector.modelName}-${detector.inputSize}", "runtime" to "onnxruntime-android-1.20.0", "backend" to detector.backend))
        } catch (error: Exception) {
            detectorFailed=true
            detectionStatus = "检测不可用：${error.message}"
            riskStatus = "移动风险不可用：目标检测失败"
            log.append("yolo_load_error", mapOf("error" to error.toString()))
        }
        try { AlignFilter(StreamType.COLOR).use { align ->
        while (running.get() && enabled) {
            try {
                owned(pipeline.waitForFrameSet(100)) { input ->
                  val processingStart = SystemClock.elapsedRealtime()
                  owned(align.process(input)) { aligned ->
                    val frames = aligned.`as`<FrameSet>(FrameType.FRAME_SET) // alias; aligned owns handle
                    val now = SystemClock.elapsedRealtime()
                    val render = true // process every synchronized pair; capacity-one preview drops only stale display work
                    var rgb: Bitmap? = null
                    var dep: Bitmap? = null
                    var targets = emptyList<TargetBox>()
                    var regionMask: NavigationRegionMask? = null
                    var findEvidence: SceneEvidence? = null
                    var captureColorUs = 0L
                    var hostArrivalUs = 0L
                    try {
                        owned(frames.colorFrame) { f ->
                            hostArrivalUs = f.systemTimeStampUs
                            captureColorUs = f.timeStampUs.toLong()
                            color = CameraSample(color.count + 1, f.timeStampUs.toString(), now, "${f.width}×${f.height} ${f.format}")
                            if (render) {
                                val bytes = ByteArray(f.dataSize); f.getData(bytes)
                                rgb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                val image = rgb
                                if (visualAvailable && image != null && now - lastVisualSampleMs >= 250) {
                                    lastVisualSampleMs = now
                                    runCatching {
                                        val sample = Bitmap.createScaledBitmap(image, VisualMotionEstimator.WIDTH,
                                            VisualMotionEstimator.HEIGHT, true)
                                        try {
                                            val pixels = IntArray(VisualMotionEstimator.WIDTH * VisualMotionEstimator.HEIGHT)
                                            sample.getPixels(pixels, 0, VisualMotionEstimator.WIDTH, 0, 0,
                                                VisualMotionEstimator.WIDTH, VisualMotionEstimator.HEIGHT)
                                            for (i in pixels.indices) {
                                                val color = pixels[i]
                                                pixels[i] = (((color shr 16 and 255) * 77) +
                                                    ((color shr 8 and 255) * 150) + ((color and 255) * 29)) shr 8
                                            }
                                            latestVisualEvidence = visualEstimator.addFrame(now, pixels)
                                            motion.addVisualEvidence(latestVisualEvidence)
                                        } finally { sample.recycle() }
                                    }.onFailure {
                                        visualAvailable = false
                                        log.append("visual_motion_error", mapOf("error" to it.toString()))
                                    }
                                }
                            }
                        }
                        owned(frames.depthFrame) { f ->
                            depth = CameraSample(depth.count + 1, f.timeStampUs.toString(), now, "${f.width}×${f.height} ${f.format} · scale=${f.valueScale} mm")
                            if (previewEnabled) dep = depthBitmap(f)
                            val image = rgb
                            val bytes = ByteArray(f.dataSize); f.getData(bytes)
                            // The already-owned byte array is retained only if offer fulfills a snapshot.
                            // A request can arrive during inference, so do not check pending before inference.
                            if (image != null) {
                                findEvidence=SceneEvidence(image.width,image.height,depth=bytes.takeIf { kotlin.math.abs(f.timeStampUs.toLong()-captureColorUs)<=50_000L },depthWidth=f.width,depthHeight=f.height,depthScaleMm=f.valueScale,depthDeltaUs=kotlin.math.abs(f.timeStampUs.toLong()-captureColorUs))
                            }
                            // One immutable configuration/projection for this frame's mask and decisions.
                            val frameNavigation=DebugParameterRuntime.current.navigation
                            val frameProjection=colorProjection?.takeIf {image?.width==f.width && image.height==f.height}
                                ?.copy(forwardWidthMeters=frameNavigation.forwardWidthMeters)
                            regionMask=if(previewEnabled) NavigationRegionOverlay.build(bytes,f.width,f.height,f.valueScale,
                                frameProjection) else null
                            val model = detector
                            if (image != null && model != null) {
                                val boxes = model.detect(image)
                                val fw=f.width; val fh=f.height; val unit=f.valueScale
                                val aligned = fw==image.width && fh==image.height
                                                                val estimates = if (aligned)
                                    DetectionGeometry.distances(boxes,bytes,fw,fh,unit)
                                else boxes.map { DepthEstimate(null,0f,"对齐尺寸不匹配") }
                                targets = boxes.mapIndexed { i, box -> box.copy(distance = estimates[i]) }
                                latestDetectionSize = fw to fh
                                latestTargets = targets
                                detectionStatus = "${model.modelName} · ${targets.size} 个目标\n" + targets.take(4).joinToString("\n") {
                                    "${YoloDetector.labels[it.classId]} · 有效深度 ${(it.distance.validRatio*100).toInt()}% · ${it.distance.reason}"
                                }
                                // Read sensor arrivals before taking the decision time:
                                // the IMU thread may publish a newer millisecond meanwhile.
                                val accelAtDecision = accel.receivedMs
                                val gyroAtDecision = gyro.receivedMs
                                val motionAtDecision = motionSnapshot
                                val poseAtDecision = orientation.snapshot()
                                val riskNow = SystemClock.elapsedRealtime()
                                val newConfig=frameNavigation
                                if(newConfig!=navigationConfig) {
                                    navigation.reset();navigationConfig=newConfig
                                    navigation=ObstacleNavigation(newConfig,useCorridorPlanner=true,useImageHeightLayers=true)
                                    log.append("navigation_parameters_applied")
                                }
                                if(riskRefreshRequested.compareAndSet(true,false)) navigation.requestSpeechRefresh()
                                val result = navigation.evaluate(riskNow,bytes,fw,fh,unit,targets,
                                    motionAtDecision,latestVisualEvidence,
                                    AssistanceStateStore.status.value.session == SessionState.ACTIVE,
                                    aligned && riskNow-accelAtDecision in 0..1_000 &&
                                        riskNow-gyroAtDecision in 0..1_000,poseAtDecision,frameProjection)
                                val guidance = result.guidance
                                regionMask=regionMask?.let { NavigationRegionOverlay.withCorridorStatus(it,guidance) }
                                if(result.reason!="sample_interval" && corridorDiagnosticRequested.compareAndSet(true,false)) {
                                    val projection=colorProjection
                                    val jpeg=java.io.ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG,85,it) }.toByteArray()
                                    fun opening(e: CorridorEvidence?)=mapOf("reason" to e?.reason,
                                        "candidateOpening" to e?.obstacleOpeningWidthMeters,"measuredOpening" to e?.measuredSideOpeningWidthMeters,
                                        "measurement" to e?.openingMeasurement?.let { m -> mapOf("distance" to m.distanceMeters,
                                            "startPixel" to m.startPixel,"endPixel" to m.endPixel,"boundary" to m.boundary) })
                                    val metadata=org.json.JSONObject(mapOf("timeMs" to riskNow,"width" to fw,"height" to fh,
                                        "scaleMm" to unit,"fx" to projection?.fx,"cx" to projection?.cx,
                                        "fy" to projection?.fy,"cy" to projection?.cy,"obstacleMeters" to result.nearestMeters,
                                        "left" to opening(guidance.corridorLeft),"right" to opening(guidance.corridorRight),
                                        "turnState" to guidance.turnState?.name,"turnDirection" to guidance.reobserveDirection?.name,
                                        "turnLeft" to turnDiagnosticFields("left",guidance.turnLeft),"turnRight" to turnDiagnosticFields("right",guidance.turnRight))).toString(2)
                                    control.execute {
                                        runCatching {
                                            val directory=File(File(context.filesDir,"corridor-diagnostic"),riskNow.toString()).apply { mkdirs() }
                                            File(directory,"depth.bin").writeBytes(bytes)
                                            File(directory,"rgb.jpg").writeBytes(jpeg)
                                            File(directory,"meta.json").writeText(metadata)
                                            log.append("corridor_diagnostic_saved",mapOf("timeMs" to riskNow,"width" to fw,"height" to fh,"directory" to directory.name))
                                        }.onFailure { log.append("corridor_diagnostic_error",mapOf("error" to it.toString())) }
                                    }
                                }
                                riskStatus = "障碍状态：${result.reason} · ${result.heightBand.label}\n${result.heightLayersStatus}" +
                                    (result.nearestMeters?.let { " · %.2f m".format(it) } ?: "") +
                                    (result.level?.let { " · ${it.name}" } ?: "")
                                avoidanceStatus = "避让方向：" + when (guidance.direction) {
                                    AvoidanceDirection.LEFT -> "左侧相对开阔"
                                    AvoidanceDirection.RIGHT -> "右侧相对开阔"
                                    null -> "暂不推荐（${guidance.reason}）"
                                }
                                if(guidance.geometryMode=="turn_priority") {
                                    avoidanceStatus="实时空间："+(guidance.spaceHint?.let(LocalRiskSpeech::spaceText) ?: "等待有效深度")
                                }
                                if (guidance.corridorLeft != null && guidance.corridorRight != null) {
                                    val left = guidance.corridorLeft; val right = guidance.corridorRight
                                    fun diagnosticLine(name: String, evidence: CorridorEvidence): String {
                                        if (!evidence.diagnosticsAvailable) return "\n$name：${evidence.reason}"
                                        val cause=when(evidence.coverageFailure) {
                                            "occupied" -> "包络碰撞"
                                            "out_of_view" -> "视野不足"
                                            "invalid_depth" -> "深度不足"
                                            "occluded_or_near_return" -> "近返回/遮挡"
                                            "insufficient_opening" -> "开口不足"
                                            else -> "覆盖充分"
                                        }
                                        return "\n%s：%s · 视野%.0f%% / 有效%.0f%% / 空闲%.0f%%\n视野外%.0f%% / 无效%.0f%% / 遮挡%.0f%% · 侧区可见%.2fm\n通道可见最窄%.2fm / 开口%.2fm / 所需%.2fm".format(
                                            name,cause+(evidence.blockedHeightBand?.let { "(${it.label})" } ?: ""),evidence.fieldOfViewCoverage*100,evidence.reliableDepthWithinView*100,
                                            evidence.freeWithinReliableDepth*100,evidence.outOfViewFraction*100,
                                            evidence.invalidDepthFraction*100,evidence.occludedFraction*100,
                                            evidence.sideVisibleWidthAtObstacleMeters,evidence.minimumVisibleWidthMeters,
                                            evidence.obstacleOpeningWidthMeters,evidence.requiredWidthMeters)+
                                            " · 侧区已见开口"+(evidence.measuredSideOpeningWidthMeters?.let { "%.2fm".format(it) } ?: "未知")+
                                            " · 地面"+(if (evidence.groundAssessment=="ground_unknown") "未知" else "近身支撑待确认")
                                    }
                                    avoidanceStatus += diagnosticLine("左",left)+diagnosticLine("右",right)
                                }
                                if (riskNow-lastGuidanceLogMs >= 500 &&
                                    (guidance.reason != lastGuidanceReason || riskNow-lastGuidanceLogMs >= 5_000)) {
                                    lastGuidanceReason = guidance.reason; lastGuidanceLogMs = riskNow
                                    log.append("avoidance_evaluation",mapOf(
                                        "reason" to guidance.reason,
                                        "geometryMode" to guidance.geometryMode,
                                        "leftCorridorReason" to (guidance.corridorLeft?.reason ?: "none"),
                                        "rightCorridorReason" to (guidance.corridorRight?.reason ?: "none"),
                                        "leftObservationEligible" to (guidance.corridorLeft?.observationEligible ?: false),
                                        "rightObservationEligible" to (guidance.corridorRight?.observationEligible ?: false),
                                        "leftOpeningWidth" to (guidance.corridorLeft?.obstacleOpeningWidthMeters ?: 0f),
                                        "leftMeasuredSideOpeningWidth" to (guidance.corridorLeft?.measuredSideOpeningWidthMeters ?: "unknown"),
                                        "rightMeasuredSideOpeningWidth" to (guidance.corridorRight?.measuredSideOpeningWidthMeters ?: "unknown"),
                                        "openingMeasurementDistance" to (guidance.geometryDistanceMeters ?: "unknown"),
                                        "leftOpeningBoundary" to (guidance.corridorLeft?.openingMeasurement?.boundary ?: "unknown"),
                                        "rightOpeningBoundary" to (guidance.corridorRight?.openingMeasurement?.boundary ?: "unknown"),
                                        "rightOpeningWidth" to (guidance.corridorRight?.obstacleOpeningWidthMeters ?: 0f),
                                        "leftCorridorWidth" to (guidance.corridorLeft?.minimumObservedWidthMeters ?: 0f),
                                        "rightCorridorWidth" to (guidance.corridorRight?.minimumObservedWidthMeters ?: 0f),
                                        "leftCorridorCoverage" to (guidance.corridorLeft?.coverage ?: 0f),
                                        "rightCorridorCoverage" to (guidance.corridorRight?.coverage ?: 0f),
                                        "requiredCorridorWidth" to (guidance.corridorLeft?.requiredWidthMeters ?: 0f),
                                        "leftCorridorOffset" to (guidance.corridorLeft?.offsetMeters ?: 0f),
                                        "rightCorridorOffset" to (guidance.corridorRight?.offsetMeters ?: 0f),
                                        "leftCorridorGap" to (guidance.corridorLeft?.minimumGapMeters ?: 0f),
                                        "rightCorridorGap" to (guidance.corridorRight?.minimumGapMeters ?: 0f),
                                        "geometryDistanceMeters" to (guidance.geometryDistanceMeters ?: "unknown"),
                                        "leftMeters" to (guidance.leftClearanceMeters ?: "unknown"),
                                        "centerMeters" to (guidance.centerClearanceMeters ?: "unknown"),
                                        "rightMeters" to (guidance.rightClearanceMeters ?: "unknown"),
                                        "leftValidRatio" to guidance.leftValidRatio,
                                        "centerValidRatio" to guidance.centerValidRatio,
                                        "rightValidRatio" to guidance.rightValidRatio,
                                        "samples" to guidance.supportingSamples,
                                        "remembered" to guidance.remembered,
                                        "visual" to latestVisualEvidence.kind.name,
                                        "visualMatchedTiles" to latestVisualEvidence.matchedTiles,
                                        "visualTiles" to latestVisualEvidence.consensusTiles,
                                        "visualAgeMs" to riskNow-latestVisualEvidence.timeMs,
                                        "rotationRate" to motionSnapshot.rotationRate,
                                        "tiltRotationRate" to motionSnapshot.tiltRotationRate,
                                        "motion" to motionSnapshot.state.name,"turnState" to (guidance.turnState?.name ?: "legacy"),
                                        "turnDirection" to (guidance.reobserveDirection?.name ?: "none"),"centralCoverage" to guidance.centralCoverage)+turnDiagnosticFields("left",guidance.turnLeft)+
                                        turnDiagnosticFields("right",guidance.turnRight)+corridorDiagnosticFields("left",guidance.corridorLeft)+
                                        corridorDiagnosticFields("right",guidance.corridorRight))
                                }
                                if (result.reason != "sample_interval" &&
                                    (result.reason != lastRiskReason || result.event != null)) {
                                    lastRiskReason = result.reason
                                    log.append("local_risk_evaluation",mapOf("reason" to result.reason,
                                        "episodeId" to (result.episodeId ?: "none"),
                                        "heightBand" to result.heightBand.name,"heightLayersStatus" to result.heightLayersStatus,
                                        "nearestMeters" to (result.nearestMeters ?: "unknown"),
                                        "level" to (result.level?.name ?: "none"),
                                        "unknownMeters" to (result.unknownMeters ?: "none"),
                                        "eventId" to (result.event?.id ?: "none")))
                                }
                                result.event?.takeIf { enabled && running.get() && frameNavigation==DebugParameterRuntime.current.navigation }?.let { event ->
                                    log.append("local_risk_event",mapOf("id" to event.id,"key" to event.key,
                                        "level" to event.level.name,"kind" to event.kind.name, "heightBand" to event.heightBand.name,
                                        "direction" to (event.avoidanceDirection?.name ?: "none"),
                                        "rememberedDirection" to event.rememberedDirection,
                                        "classId" to event.classId,"meters" to event.distanceMeters,
                                        "expiresMs" to event.expiresMs,
                                        "space" to (event.spaceHint?.name ?: "legacy"),
                                        "confirmation_ms" to (event.confirmationMs ?: -1L),
                                        "forward_width_meters" to frameNavigation.forwardWidthMeters,
                                        "frame_to_decision_ms" to (SystemClock.elapsedRealtime()-processingStart),
                                        "geometry_compute_ms" to (SystemClock.elapsedRealtime()-riskNow)))
                                    runCatching { onRisk(event) }.onFailure {
                                        log.append("local_risk_delivery_error",mapOf("error" to it.toString()))
                                    }
                                }
                            }
                        }
                        if (rgb != null) {
                            SceneFrames.offer(rgb, now, orientation.snapshot(), targets, findEvidence?.copy(targets=targets))
                            if (dep != null && previewEnabled) {
                                val pair = PreviewPair(rgb, dep, previewReleased, targets,regionMask)
                                rgb = null; dep = null
                                previewCreated.incrementAndGet()
                                previews.offer(pair)
                            }
                            processingRate.record(SystemClock.elapsedRealtime())
                            if (detector != null) detectionTiming.record(SystemClock.elapsedRealtime()-processingStart)
                            if (detector != null) {
                                val age = System.currentTimeMillis() - hostArrivalUs/1000
                                if (hostArrivalUs > 0 && age in 0..60000) arrivalTiming.record(age)
                                if (color.count == 1L) log.append("yolo_clock_check",mapOf("hostArrivalUs" to hostArrivalUs,"wallMs" to System.currentTimeMillis(),"elapsedMs" to SystemClock.elapsedRealtime()))
                            }
                        }
                    } finally { rgb?.recycle(); dep?.recycle() }
                  }
                }
            } catch (error: Exception) {
                if (running.get() && enabled) {
                    log.append("camera_video_error", mapOf("error" to error.toString()))
                    message = "视频采集异常，等待自动恢复"
                }
                break
            }
        }
        } } catch (error: Exception) {
            log.append("camera_alignment_error", mapOf("error" to error.toString()))
            message = "对齐失败，等待重连"
        } finally { detectorReady=false;detector?.close() }
    }

    private fun depthBitmap(frame: DepthFrame): Bitmap {
        val bytes = ByteArray(frame.dataSize); frame.getData(bytes)
        val sourceWidth = frame.width
        val sourceHeight = frame.height
        val scale = frame.valueScale
        val width = minOf(sourceWidth, 320)
        val height = sourceHeight * width / sourceWidth
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = ((y * sourceHeight / height) * sourceWidth + x * sourceWidth / width) * 2
            if (offset + 1 >= bytes.size) continue
            val raw = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
            pixels[y * width + x] = DepthPalette.color(raw * scale)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun imuLoop(pipeline: Pipeline) {
        while (running.get() && enabled) {
            try {
                owned(pipeline.waitForFrameSet(100)) { frames ->
                    val now = SystemClock.elapsedRealtime()
                    owned(frames.getFrame<AccelFrame>(FrameType.ACCEL)) { f ->
                        accel = CameraSample(accel.count + 1, f.timeStampUs.toString(), now, f.accelData.joinToString(", ") { "%.4f".format(it) } + " m/s²")
                        motion.addAcceleration(now, f.accelData.map { it.toDouble() }.toDoubleArray())
                        FallSignals.detector.acceleration(now,f.accelData.map { it.toDouble() }.toDoubleArray())
                    }
                    owned(frames.getFrame<GyroFrame>(FrameType.GYRO)) { f ->
                        val rates = f.gyroData
                        orientation.update(f.timeStampUs.toLong(), rates[0].toDouble(), rates[1].toDouble(), rates[2].toDouble())
                        gyro = CameraSample(gyro.count + 1, f.timeStampUs.toString(), now, rates.joinToString(", ") { "%.4f".format(it) } + " rad/s")
                        motion.addRotation(now, rates.map { it.toDouble() }.toDoubleArray())
                        FallSignals.detector.rotation(now,rates.map { it.toDouble() }.toDoubleArray())
                    }
                }
            } catch (error: Exception) {
                if (running.get() && enabled) log.append("camera_imu_error", mapOf("error" to error.toString()))
                break
            }
        }
    }

    private fun fail(event: String, error: Throwable) {
        message = "相机错误：${error.message}；两秒后重试"
        OperationalStateStore.update(device = DeviceState.ERROR, motion = MotionSnapshot())
        log.append(event, mapOf("error" to error.toString()))
        shutdownSession("error")
        nextRetryMs = SystemClock.elapsedRealtime() + 2000
    }

    private fun closeResource(resource: AutoCloseable?) {
        try { resource?.close() } catch (e: Exception) { closeErrors.incrementAndGet(); log.append("camera_close_error", mapOf("error" to e.toString())) }
    }

    private fun shutdownSession(reason: String) {
        running.set(false)
        // Wait for bounded waitForFrameSet calls to finish; never destroy a pipeline while a reader uses it.
        videoThread?.join(); imuThread?.join()
        videoThread = null; imuThread = null
        listOf(videoPipeline, imuPipeline).forEach { p ->
            if (p != null) { runCatching { p.stop() }.onFailure { log.append("camera_stop_error", mapOf("error" to it.toString())) }; closeResource(p) }
        }
        videoPipeline = null; imuPipeline = null
        previews.close()
        closeResource(device); device = null
        closeResource(sdk); sdk = null
        motion.reset(); motionSnapshot = MotionSnapshot()
        navigation.reset(); riskStatus = "移动风险等待有效数据"; avoidanceStatus = "避让方向：等待有效数据"; lastRiskReason = ""; lastGuidanceReason = ""; lastGuidanceLogMs = 0L
        OperationalStateStore.update(motion = MotionSnapshot(), device = DeviceState.DISCONNECTED)
        detached.set(false)
        usbKey = null
        log.append("camera_resources_released", mapOf("reason" to reason, "framesAcquired" to acquired.get(), "framesReleased" to released.get(), "outstandingFrames" to acquired.get() - released.get(), "previewCreated" to previewCreated.get(), "previewReleased" to previewReleased.get(), "previewPending" to previews.size(), "closeErrors" to closeErrors.get()))
    }
}


private fun corridorDiagnosticFields(side: String,evidence: CorridorEvidence?): Map<String,Any> {
    if (evidence == null || !evidence.diagnosticsAvailable)
        return mapOf("${side}CoverageDiagnosticsAvailable" to false)
    return mapOf(
        "${side}CoverageDiagnosticsAvailable" to true,
        "${side}CoverageFailure" to evidence.coverageFailure,
        "${side}BlockedHeightBand" to (evidence.blockedHeightBand?.name ?: "none"),
        "${side}GroundAssessment" to evidence.groundAssessment,
        "${side}FieldOfViewCoverage" to evidence.fieldOfViewCoverage,
        "${side}ReliableDepthWithinView" to evidence.reliableDepthWithinView,
        "${side}FreeWithinReliableDepth" to evidence.freeWithinReliableDepth,
        "${side}OutOfViewFraction" to evidence.outOfViewFraction,
        "${side}InvalidDepthFraction" to evidence.invalidDepthFraction,
        "${side}OccludedFraction" to evidence.occludedFraction,
        "${side}ObservedFreeFraction" to evidence.observedFreeFraction,
        "${side}MinimumVisibleWidth" to evidence.minimumVisibleWidthMeters,
        "${side}SideVisibleWidthAtObstacle" to evidence.sideVisibleWidthAtObstacleMeters)
}

private fun turnDiagnosticFields(side: String,evidence: SideTurnEvidence?): Map<String,Any> =
    if(evidence==null) emptyMap() else mapOf(side+"TurnStatus" to evidence.status.name,
        side+"TurnAngle" to evidence.angleDegrees,side+"TurnWidth" to evidence.visibleWidthMeters,
        side+"TurnCoverage" to evidence.coverage,side+"TurnScore" to evidence.score)
