package com.helios.app

import kotlin.math.abs

/** Refresh useful guidance; suppress unchanged frames and never tell the wearer to walk. */
class FindGuidance {
    private var previous: FindObservation? = null
    private var lastSpokenMs: Long? = null
    fun reset() { previous=null;lastSpokenMs=null }
    fun shouldSpeak(observation: FindObservation, now: Long): Boolean {
        val old=previous ?: return true
        return lastSpokenMs?.let { now-it>=8_000 } != false ||
            position(old.region!!) != position(observation.region!!) || old.context!=observation.context ||
            old.depthVerified!=observation.depthVerified || old.hazard?.name!=observation.hazard?.name ||
            (old.hazard!=null && observation.hazard!=null && observation.hazard.meters<=old.hazard.meters-.25f) ||
            (old.meters!=null && observation.meters!=null && abs(old.meters-observation.meters)>=.25f)
    }
    fun started(observation: FindObservation, now: Long) { previous=observation;lastSpokenMs=now }
    companion object {
        fun position(region: FindRegion): String {
            val horizontal=when { (region.left+region.right)/2 < .35 -> "左"; (region.left+region.right)/2 > .65 -> "右"; else -> "" }
            val vertical=when { (region.top+region.bottom)/2 < .33 -> "上"; (region.top+region.bottom)/2 > .67 -> "下"; else -> "" }
            return when { horizontal+vertical=="" -> "画面中间"; vertical=="" -> "画面${horizontal}侧"; else -> "画面$horizontal${vertical}方" }
        }
        fun describe(target: FindTarget, observation: FindObservation, first: Boolean, completed: Boolean = false): String {
            val name=if(observation.confidence>=.90) target.name else "疑似${target.name}"
            val distance=observation.meters?.takeIf { observation.depthVerified }?.let { "，约%.1f米".format(java.util.Locale.ROOT,it) } ?: "，距离暂不能确认"
            val context=observation.context.takeIf { it.isNotBlank() }?.let { "。$it" } ?: ""
            val horizontal=(observation.region!!.left+observation.region.right)/2
            val vertical=(observation.region.top+observation.region.bottom)/2
            val aim=when { completed -> "目标已在50厘米内，本次找物结束"; horizontal<.35 -> "可将镜头缓慢向左对准"; horizontal>.65 -> "可将镜头缓慢向右对准"; vertical<.33 -> "可将镜头缓慢向上对准"; vertical>.67 -> "可将镜头缓慢向下对准"; else -> if(first) "运动后停稳，我会再次识别" else "" }
            val hazard=observation.hazard?.let { "前方近处有${it.name}，请留意。" } ?: ""
            return "${name}在${position(observation.region)}$distance$context。$hazard" + if(aim.isNotBlank()) "$aim。" else ""
        }
        fun notFound(target: FindTarget, observation: FindObservation, attempt: Int, previouslySeen: Boolean): String {
            val opening=if(previouslySeen) "当前画面暂时看不到${target.name}" else "暂未看到${target.name}"
            val context=observation.context.takeIf { it.isNotBlank() }?.let { "。$it" } ?: ""
            val scan=when((attempt-1)%3) { 0 -> "可保持站定，缓慢调整镜头向左查看，停稳后继续"; 1 -> "可缓慢调整镜头向右查看，停稳后继续"; else -> "也可稍微调整镜头俯仰，留意遮挡区域，停稳后继续" }
            val hazard=observation.hazard?.let { "前方近处有${it.name}，请留意。" } ?: ""
            return "$opening$context。$hazard$scan。"
        }
    }
}
