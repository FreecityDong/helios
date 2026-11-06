package com.helios.app

/** Fixed-size window; no unbounded per-frame history. Durations include alignment and preprocessing. */
class DetectionTiming {
    private val samples = LongArray(120)
    private var cursor = 0
    private var count = 0
    @Synchronized fun reset() { cursor=0; count=0 }
    @Synchronized fun record(ms: Long) { samples[cursor]=ms; cursor=(cursor+1)%samples.size; count=minOf(count+1,samples.size) }
    @Synchronized fun summary(): String {
        if (count==0) return "等待检测"
        val sorted=samples.copyOf(count).sorted()
        return "处理耗时 P50 ${sorted[(count-1)/2]} / P95 ${sorted[kotlin.math.ceil(count*0.95).toInt()-1]} ms（最近 $count 组）"
    }
}
