package com.helios.app

internal data class ParameterSpec(val key:String,val group:String,val label:String,val unit:String,
    val default:Double,val min:Double,val max:Double,val hint:String="")

internal object ParameterCatalog {
    val specs=listOf(
        ParameterSpec("forwardWidth","检测范围","中央检测总宽度","厘米",60.0,20.0,150.0,"左右合计；检测、解除和青色蒙皮同步改变"),
        ParameterSpec("caution","告警距离与级别","普通提醒距离","米",2.0,.5,3.8),
        ParameterSpec("warning","告警距离与级别","警告距离","米",1.0,.3,3.0),
        ParameterSpec("urgent","告警距离与级别","紧急距离","米",.5,.25,2.0),
        ParameterSpec("clear","告警距离与级别","障碍解除距离","米",2.3,.8,4.0,"必须大于普通提醒距离"),
        ParameterSpec("clearHold","告警距离与级别","确认无障碍时间","秒",1.0,1.0,3.0),
        ParameterSpec("cautionRepeat","播报冷却","运动中普通提醒间隔","秒",8.0,1.0,30.0),
        ParameterSpec("warningRepeat","播报冷却","运动中警告间隔","秒",4.0,.5,20.0),
        ParameterSpec("urgentRepeat","播报冷却","运动中紧急提醒间隔","秒",2.0,.5,10.0),
        ParameterSpec("clearRepeat","播报冷却","运动中无障碍提示间隔","秒",3.0,3.0,30.0,"仅实际深度确认无障碍且声音空闲时播；静止不循环"),
        ParameterSpec("closer","播报冷却","重新提醒的接近距离差","米",.2,.1,1.0,"静止也适用；新风险和等级升级不受重复间隔限制"),
        ParameterSpec("moveAccel","运动与静止判断","运动加速度波动门槛","m/s²",.75,.2,2.5,"越低越敏感；使用去均值波动而非重力总值"),
        ParameterSpec("moveGyro","运动与静止判断","运动角速度门槛","rad/s",.3,.1,1.5,"越低越敏感；视觉运动也可能判为运动"),
        ParameterSpec("stillHold","运动与静止判断","进入静止的稳定时间","秒",2.0,.5,5.0),
        ParameterSpec("movingHold","运动与静止判断","进入运动的确认时间","秒",.7,.1,2.0),
        ParameterSpec("shakingHold","运动与静止判断","进入晃动的确认时间","秒",.25,.1,1.0),
        ParameterSpec("shakeAccel","晃动判断","持续强加速度门槛","m/s²",6.0,3.0,12.0),
        ParameterSpec("shakeTilt","晃动判断","持续倾斜角速度门槛","rad/s",1.0,.4,3.0),
        ParameterSpec("shakeImpact","晃动判断","短冲击门槛","m/s²",8.0,4.0,18.0),
        ParameterSpec("shakeImpactTilt","晃动判断","短冲击倾斜角速度门槛","rad/s",.6,.2,2.0),
        ParameterSpec("fallImpact","跌倒检测敏感度","冲击门槛（原始加速度）","m/s²",26.0,12.0,60.0,"越低越敏感；疑似检测使用多项证据"),
        ParameterSpec("fallTilt","跌倒检测敏感度","倾斜角速度门槛","rad/s",1.0,.3,4.0,"越低越敏感"),
        ParameterSpec("fallAngle","跌倒检测敏感度","事后姿态变化门槛","度",45.0,20.0,90.0,"越低越敏感"),
        ParameterSpec("fallQuiet","跌倒检测敏感度","事后低活动确认时间","秒",1.5,.5,3.0),
        ParameterSpec("fallWindow","跌倒检测敏感度","疑似事件证据窗口","秒",4.0,2.0,6.0),
        ParameterSpec("fallResponse","跌倒语音流程","有效收音等待时长","秒",20.0,10.0,40.0),
        ParameterSpec("fallWall","跌倒语音流程","无回应总等待上限","秒",40.0,20.0,90.0,"至少比有效收音等待长8秒"),
        ParameterSpec("fallRepeat","跌倒语音流程","本地求助重复间隔","秒",10.0,6.0,30.0),
        ParameterSpec("fallGap","跌倒语音流程","求助间保留有效收音","秒",6.0,3.0,15.0),
        ParameterSpec("fallRecovery","跌倒语音流程","回应后的恢复观察时间","秒",30.0,10.0,120.0),
        ParameterSpec("speechRate","语音播报","播报语速","倍",1.0,.6,1.5)
    )
    val groups=specs.map {it.group}.distinct()
}

internal data class MotionConfig(val acceleration:Double=.75,val gyro:Double=.3,
    val stillMs:Long=2_000,val movingMs:Long=700,val shakingMs:Long=250,
    val shakeAcceleration:Double=6.0,val shakeTilt:Double=1.0,val impact:Double=8.0,val impactTilt:Double=.6)

internal class DebugParameters private constructor(private val values:Map<String,Double>) {
    operator fun get(key:String)=values.getValue(key)
    fun asMap():Map<String,Double> = values.toMap()
    private fun ms(key:String)=(get(key)*1_000).toLong()
    val navigation by lazy { NavigationConfig(cautionMeters=get("caution").toFloat(),warningMeters=get("warning").toFloat(),
        urgentMeters=get("urgent").toFloat(),clearMeters=get("clear").toFloat(),
        cautionRepeatMs=ms("cautionRepeat"),warningRepeatMs=ms("warningRepeat"),urgentRepeatMs=ms("urgentRepeat"),clearRepeatMs=ms("clearRepeat"),
        turnClearHoldMs=ms("clearHold"),closerMeters=get("closer").toFloat(),
        forwardWidthMeters=(get("forwardWidth")/100).toFloat()) }
    val motion by lazy { MotionConfig(get("moveAccel"),get("moveGyro"),ms("stillHold"),ms("movingHold"),ms("shakingHold"),
        get("shakeAccel"),get("shakeTilt"),get("shakeImpact"),get("shakeImpactTilt")) }
    val fallCandidate by lazy { FallCandidateConfig(impactMetersPerSecond2=get("fallImpact"),tiltRadiansPerSecond=get("fallTilt"),
        changedDegrees=get("fallAngle"),quietMs=ms("fallQuiet"),eventWindowMs=ms("fallWindow")) }
    val fallFlow by lazy { FallConfig(ms("fallResponse"),ms("fallWall"),ms("fallRepeat"),ms("fallGap"),ms("fallRecovery")) }
    fun patched(patch:Map<String,Double>)=from(values+patch)
    override fun equals(other:Any?)=other is DebugParameters && values==other.values
    override fun hashCode()=values.hashCode()
    companion object {
        fun defaults()=DebugParameters(ParameterCatalog.specs.associate {it.key to it.default})
        /** Upgrade only the old shorter clearance setting; preserve all other saved tuning. */
        fun fromSaved(values:Map<String,Double>):DebugParameters {
            val hold=values["clearHold"]
            return from(if(hold!=null && hold.isFinite() && hold>=.2 && hold<1.0) values+("clearHold" to 1.0) else values)
        }
        fun from(values:Map<String,Double>):DebugParameters {
            require(values.keys.all {k->ParameterCatalog.specs.any {it.key==k}}) {"含未知参数"}
            val merged=defaults().asMap()+values
            ParameterCatalog.specs.forEach {spec->
                val v=merged.getValue(spec.key)
                require(v.isFinite() && v in spec.min..spec.max) {"${spec.label}应为${spec.min}–${spec.max}${spec.unit}"}
            }
            fun v(key:String)=merged.getValue(key)
            require(v("urgent")<v("warning") && v("warning")<v("caution") && v("caution")<v("clear")) {"距离需满足：紧急 < 警告 < 普通提醒 < 障碍解除"}
            require(v("fallQuiet")+.5<=v("fallWindow")) {"跌倒证据窗口至少比低活动确认长0.5秒"}
            require(v("fallWall")>=v("fallResponse")+8) {"无回应总等待上限至少比有效收音等待长8秒"}
            require(v("fallGap")<=v("fallRepeat")) {"求助收音时间不能超过求助重复间隔"}
            return DebugParameters(merged)
        }
    }
}

internal object DebugParameterRuntime {
    @Volatile var current=DebugParameters.defaults()
}
