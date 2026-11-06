package com.helios.app

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity:ComponentActivity() {
    private lateinit var log:EventLog
    private var debugDisplay by mutableStateOf(false)
    private var notice by mutableStateOf("")
    private var pendingStart=false
    private var localHelp:LocalUsageHelp?=null
    private val usbAction get()="$packageName.USB_PREPARE_PERMISSION"
    private val permissionLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val start=pendingStart;pendingStart=false
        if(start) {
            if(requiredPermissions().all(::granted)) startSession()
            else notice="需要麦克风和相机权限，请在更多与帮助中完成权限准备。"
        } else notice="权限检查完成，可继续准备USB授权。"
    }
    private val exporter=registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if(uri!=null) lifecycleScope.launch {
            val result=withContext(Dispatchers.IO) {runCatching {checkNotNull(contentResolver.openOutputStream(uri,"wt")).use {log.exportTo(it)}}}
            notice=if(result.isSuccess) "诊断日志已导出" else "导出失败，请更换保存位置重试"
        }
    }
    private val usbReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action==usbAction) {
                notice=if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)) "USB授权已完成" else "USB授权未允许"
                log.append("usb_preparation_result",mapOf("granted" to intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)))
            }
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        log=EventLog(applicationContext)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(this,usbReceiver,IntentFilter(usbAction),ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF205C4C))) {
                Surface(Modifier.fillMaxSize()) {Home()}
            }
        }
        if(AppOpeningGreeting.take()) {
            localHelp=LocalUsageHelp(applicationContext)
            localHelp?.playGreeting()
        }
    }
    override fun onDestroy() {localHelp?.stop();unregisterReceiver(usbReceiver);super.onDestroy()}

    @Composable private fun Home() {
        val assistance by AssistanceStateStore.status.collectAsState()
        val startup by StartupStatusStore.status.collectAsState()
        val operational by OperationalStateStore.status.collectAsState()
        val fall by FallStatusStore.status.collectAsState()
        val help by UsageHelpStatusStore.status.collectAsState()
        val session=assistance.session
        val running=session in setOf(SessionState.ACTIVE,SessionState.PAUSED,SessionState.STARTING)
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val pageHeight=maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(pageHeight).padding(24.dp)) {
                    Column(Modifier.align(Alignment.TopStart),verticalArrangement=Arrangement.spacedBy(20.dp)) {
                        Text("Helios",style=MaterialTheme.typography.headlineLarge,modifier=Modifier.semantics {heading()})
                        Text(when(session) {
                            SessionState.OFF -> if(startup.message.startsWith("异常退出")) startup.message else "佩戴好相机，长按启动"
                            SessionState.STARTING -> startup.message
                            SessionState.ACTIVE -> when {
                                assistance.voice==VoiceState.ERROR -> "语音暂不可用，请查看调试信息"
                                operational.device!=DeviceState.STREAMING -> "相机未就绪，请检查连接"
                                !operational.motion.calibrated -> "等待本次佩戴校准"
                                operational.motion.stale -> "运动数据失效，请检查设备"
                                else -> "辅助运行中"
                            }
                            SessionState.PAUSED -> "辅助已暂停"
                            SessionState.STOPPING -> assistance.message
                            SessionState.ERROR -> startup.message.ifBlank {assistance.message}
                        },style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                    }
                    val listening=assistance.voice in setOf(VoiceState.COMMAND_LISTENING,VoiceState.CONFIRMING_EXIT)
                    val caption=when {
                        listening -> (if(assistance.wakeAcknowledged) "已识别 Hi Helios" else "正在听取指令")+"\n"+
                            assistance.liveTranscript.ifBlank {"请说出指令"}
                        assistance.voice==VoiceState.PROMPTING && assistance.wakeAcknowledged -> "已识别 Hi Helios\n准备听取指令"
                        else -> ""
                    }
                    Box(Modifier.align(Alignment.Center)) {
                        HoldSessionButton(if(running) "结束辅助" else "启动辅助",
                            session!=SessionState.STOPPING && fall.state !in setOf(FallState.CONFIRMING,FallState.HELPING),caption=caption) {
                            if(running) send(AssistanceService.ACTION_STOP) else preparePermissions(true)
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(24.dp)) {
                            TextButton(onClick={playHelp()},modifier=Modifier.weight(1f).heightIn(min=64.dp)) {Text("帮助")}
                            TextButton(onClick={showMore(running)},modifier=Modifier.weight(1f).heightIn(min=64.dp)) {Text("更多")}
                        }
                        if(help.message.isNotEmpty()) Text(help.message)
                        else if(notice.isNotEmpty()) Text(notice,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                    }
                }
                if(debugDisplay) DebugContent()
            }
        }
        if(fall.state in setOf(FallState.CONFIRMING,FallState.HELPING)) {
            AlertDialog(onDismissRequest={},title={Text(if(fall.state==FallState.CONFIRMING) "你还好吗？" else "正在本地求助")},
                text={Text(if(fall.state==FallState.CONFIRMING) "检测到疑似跌倒，请说我没事或需要帮助，无需唤醒词。" else "手机正在播放本地求助提醒，尚未联系外部人员。")},
                confirmButton={TextButton(onClick={fallReply("我没事")},modifier=Modifier.heightIn(min=64.dp)) {Text("我没事")}},
                dismissButton={TextButton(onClick={fallReply(if(fall.state==FallState.CONFIRMING) "需要帮助" else "取消求助")},modifier=Modifier.heightIn(min=64.dp)) {Text(if(fall.state==FallState.CONFIRMING) "需要帮助" else "取消求助")}})
        }
    }
    @Composable private fun DebugContent() {
        var details by remember {mutableStateOf("")}
        var imageAspect by remember {mutableStateOf(4f/3f)}
        var imageDetails by remember {mutableStateOf("等待图像")}
        var preview by remember {mutableStateOf<LiveDebugPreview?>(null)}
        var showColor by remember {mutableStateOf(true)}
        var showDepth by remember {mutableStateOf(true)}
        var showRegions by remember {mutableStateOf(true)}
        var showBoxes by remember {mutableStateOf(true)}
        var showLabels by remember {mutableStateOf(false)}
        var showLogs by remember {mutableStateOf(false)}
        var logs by remember {mutableStateOf("")}
        LaunchedEffect(showLogs) {while(true) {
            details=debugSummary()
            imageAspect=preview?.imageAspect ?: 4f/3f
            imageDetails=preview?.imageDetails ?: "等待图像"
            logs=if(showLogs) EventLog.recentLines(24).joinToString("\n") else ""
            delay(1_000)
        }}
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("调试显示",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(horizontal=16.dp))
            Row(Modifier.fillMaxWidth()) {
                DebugOption("RGB",showColor,Modifier.weight(1f)) {if(it || showDepth) showColor=it}
                DebugOption("深度",showDepth,Modifier.weight(1f)) {if(it || showColor) showDepth=it}
            }
            Row(Modifier.fillMaxWidth()) {
                DebugOption("蒙皮",showRegions,Modifier.weight(1f)) {showRegions=it}
                DebugOption("检测框",showBoxes,Modifier.weight(1f)) {showBoxes=it}
                DebugOption("框内文字",showLabels,Modifier.weight(1f)) {showLabels=it}
            }
            // Keep the full image aspect ratio; use the complete width for either view.
            AndroidView(factory={LiveDebugPreview(it).also {view->preview=view}},
                update={it.configure(showColor,showDepth,showRegions,showBoxes,showLabels)},
                modifier=Modifier.fillMaxWidth().aspectRatio(imageAspect / (if(showColor && showDepth) 2 else 1)))
            Text(imageDetails,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=16.dp))
            Text(details,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(horizontal=16.dp))
            DebugOption("显示详细调用事件日志",showLogs,Modifier.fillMaxWidth()) {showLogs=it}
            if(showLogs) Text(logs.ifBlank {"暂无事件"},style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(16.dp))
        }
    }
    @Composable private fun DebugOption(label:String,checked:Boolean,modifier:Modifier=Modifier,onChange:(Boolean)->Unit) {
        Row(modifier.heightIn(min=48.dp).toggleable(value=checked,role=Role.Checkbox,onValueChange=onChange),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=checked,onCheckedChange=null)
            Text(label,style=MaterialTheme.typography.bodySmall)
        }
    }
    private fun debugSummary():String {
        val camera=SensorRuntime.camera
        val now=SystemClock.elapsedRealtime()
        val state=OperationalStateStore.status.value
        val assistance=AssistanceStateStore.status.value
        val scene=SceneStatusStore.status.value
        return buildString {
            appendLine("版本 0.9.24/code95 · ${camera?.message ?: "采集未启动"}")
            appendLine("RGB ${camera?.color?.health(now)} · 深度 ${camera?.depth?.health(now)}")
            appendLine("加速度 ${camera?.accel?.health(now)} · 陀螺仪 ${camera?.gyro?.health(now)}")
            appendLine("运动 ${state.motion.state} · 校准 ${state.motion.calibrated} · 陈旧 ${state.motion.stale}")
            appendLine("处理 %.1f FPS · %s".format(camera?.processingRate?.fps ?: 0.0,camera?.detectionTiming?.summary().orEmpty()))
            appendLine(camera?.detectionStatus.orEmpty());appendLine(camera?.riskStatus.orEmpty())
            appendLine("语音 ${assistance.voice} · ${assistance.message}")
            appendLine("找物 ${FindStatusStore.status.value}")
            appendLine("环境 ${scene.message}\n${scene.summary}")
            appendLine("跌倒 ${FallStatusStore.status.value.monitoring}")
        }
    }
    private fun showMore(running:Boolean) {
        val options=mutableListOf<Pair<String,()->Unit>>()
        options += (if(debugDisplay) "关闭调试显示" else "开启调试显示") to {debugDisplay=!debugDisplay}
        options += "调试参数" to {showDebugParameterSettings(this)}
        options += "设备与首次准备" to {showPreparation()}
        options += "环境理解配置" to {showSceneSettings(this)}
        options += "后台电源设置" to {startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}
        options += "导出诊断日志" to {exporter.launch("helios-${System.currentTimeMillis()}.jsonl")}
        if(running) {
            val paused=AssistanceStateStore.status.value.session==SessionState.PAUSED
            options += (if(paused) "恢复辅助" else "暂停辅助") to {
                if(AssistanceStateStore.status.value.session==SessionState.STARTING) notice="准备期间可以结束辅助，完成后再暂停。"
                else confirmIfFall {send(if(paused) AssistanceService.ACTION_RESUME else AssistanceService.ACTION_PAUSE)}
            }
            options += "结束辅助" to {android.app.AlertDialog.Builder(this).setTitle("结束辅助？").setMessage("将停止当前监测、采集、语音和本地提醒。").setPositiveButton("结束") {_,_->send(AssistanceService.ACTION_STOP)}.setNegativeButton("继续使用",null).show()}
            options += "重新佩戴校准" to {send(AssistanceService.ACTION_RECALIBRATE)}
        }
        if(debugDisplay && running && applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0) {
            options += "开发测试（模拟事件）" to {showTests()}
        }
        android.app.AlertDialog.Builder(this).setTitle("更多").setItems(options.map {it.first}.toTypedArray()) {_,i->options[i].second()}.show()
    }
    private fun confirmIfFall(action:()->Unit) {
        if(FallStatusStore.status.value.state in setOf(FallState.CONFIRMING,FallState.HELPING))
            android.app.AlertDialog.Builder(this).setTitle("将停止监测和当前提醒").setPositiveButton("确认暂停") {_,_->action()}.setNegativeButton("返回",null).show()
        else action()
    }
    private fun showPreparation() {
        val usb=getSystemService(UsbManager::class.java)
        val devices=usb.deviceList.values.filter {it.vendorId==0x2bc5 && it.productId==0x0670}
        val info="麦克风：${granted(Manifest.permission.RECORD_AUDIO)}\n相机权限：${granted(Manifest.permission.CAMERA)}\nUSB设备：${devices.size}，已授权：${devices.any {usb.hasPermission(it)}}\n每次启动后由使用者胸前佩戴并停稳，按语音完成校准。"
        android.app.AlertDialog.Builder(this).setTitle("设备与首次准备").setMessage(info)
            .setPositiveButton("准备权限") {_,_->preparePermissions(false)}
            .setNeutralButton("USB授权") {_,_->
                val device=devices.firstOrNull()
                if(device==null) notice="请先连接Gemini 2相机"
                else usb.requestPermission(device,PendingIntent.getBroadcast(this,device.deviceId,Intent(usbAction).setPackage(packageName),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            }.setNegativeButton("系统设置") {_,_->startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))}.show()
    }
    private fun playHelp() {
        if(UsageHelpStatusStore.status.value.playing) {
            if(localHelp!=null) {localHelp?.stop();localHelp=null}
            if(AssistanceStateStore.status.value.session in setOf(SessionState.ACTIVE,SessionState.PAUSED)) send(AssistanceService.ACTION_USAGE_HELP_STOP)
            return
        }
        when(AssistanceStateStore.status.value.session) {
            SessionState.ACTIVE,SessionState.PAUSED -> send(AssistanceService.ACTION_USAGE_HELP)
            SessionState.STARTING,SessionState.STOPPING -> UsageHelpStatusStore.update(UsageHelpStatus(message="请等当前准备或退出完成后再播放帮助"))
            else -> {localHelp?.stop();localHelp=LocalUsageHelp(applicationContext);localHelp?.play()}
        }
    }
    private fun showTests() {
        val actions=listOf("模拟普通播报" to AssistanceService.ACTION_INJECT_ORDINARY,"模拟安全打断" to AssistanceService.ACTION_INJECT_SAFETY,"模拟跌倒确认" to AssistanceService.ACTION_INJECT_FALL)
        android.app.AlertDialog.Builder(this).setTitle("开发测试：下次操作为模拟事件").setItems(actions.map {it.first}.toTypedArray()) {_,i->
            notice="开发模拟事件：${actions[i].first}，不代表真实检测结果"
            send(actions[i].second)
        }.setNegativeButton("取消",null).show()
    }
    private fun fallReply(text:String) {startService(Intent(this,AssistanceService::class.java).setAction(AssistanceService.ACTION_FALL_REPLY).putExtra(AssistanceService.EXTRA_FALL_REPLY,text))}
    private fun send(action:String) {startService(Intent(this,AssistanceService::class.java).setAction(action))}
    private fun requiredPermissions()=listOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA)
    private fun granted(permission:String)=ContextCompat.checkSelfPermission(this,permission)==PackageManager.PERMISSION_GRANTED
    private fun preparePermissions(start:Boolean) {
        if(start) {localHelp?.stop();localHelp=null}
        pendingStart=start
        val permissions=requiredPermissions()+listOf(Manifest.permission.BLUETOOTH_CONNECT)+
            if(Build.VERSION.SDK_INT>=33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing=permissions.filterNot(::granted)
        if(missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else {pendingStart=false;if(start) startSession() else notice="权限已准备好"}
    }
    private fun startSession() {
        notice=""
        runCatching {ContextCompat.startForegroundService(this,Intent(this,AssistanceService::class.java).setAction(AssistanceService.ACTION_START))}
            .onFailure {notice="启动失败，请保持App在前台并重试";log.append("assistance_start_failed",mapOf("error" to it.javaClass.simpleName))}
    }
}
