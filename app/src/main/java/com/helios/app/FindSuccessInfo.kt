package com.helios.app

/** Success is permanent; this optional position detail remains tied to the captured view. */
class FindSuccessInfo(val completion: FindCompletion) {
    var sameView = true; private set
    fun observe(viewStillValid: Boolean) { if(!viewStillValid) sameView=false }
    fun valid(now: Long) = sameView && now-completion.capturedMs in 0..8_000
    fun text(): String {
        val observation=completion.observation
        val position=FindGuidance.position(observation.region!!).removePrefix("画面")
        val distance=observation.meters?.takeIf { observation.depthVerified }?.let {
            if(it<1f) "约${kotlin.math.round(it*100).toInt()}厘米" else "约%.1f米".format(java.util.Locale.ROOT,it)
        } ?: "距离暂不能确认"
        val context=observation.context.takeIf { it.isNotBlank() }?.let { "。$it" } ?: ""
        return "${completion.target.name}在$position，$distance$context。"
    }
}
