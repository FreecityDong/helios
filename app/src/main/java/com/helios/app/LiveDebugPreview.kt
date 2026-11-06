package com.helios.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout

/** Display attachment never owns capture. Screen-off hides rendering without stopping the service. */
class LiveDebugPreview(context:Context):FrameLayout(context) {
    private val preview=CameraPreview(context)
    private val handler=Handler(Looper.getMainLooper())
    private var observed:GeminiCamera?=null
    private var active=false
    var imageAspect: Float = 4f/3f
        private set
    var imageDetails: String = "等待图像"
        private set
    fun configure(color:Boolean,depth:Boolean,regions:Boolean,boxes:Boolean,labels:Boolean) {
        preview.showColor=color;preview.showDepth=depth;preview.showRegions=regions
        preview.showBoxes=boxes;preview.showLabels=labels
    }
    init { addView(preview,LayoutParams(-1,-1));contentDescription="调试图像：上方RGB与检测框，下方对齐深度" }
    private fun stopRendering() {
        active=false;handler.removeCallbacks(refresh)
        observed?.let { it.previewEnabled=false;it.previews.take()?.close() }
        observed=null
        preview.clearPreview()
    }
    private val refresh=object:Runnable {
        override fun run() {
            if(!active) return
            val camera=SensorRuntime.camera
            if(camera!==observed) {
                observed?.let { it.previewEnabled=false;it.previews.take()?.close() }
                observed=camera
            }
            camera?.previewEnabled=true
            camera?.previews?.take()?.let { pair ->
                (pair.color ?: pair.depth)?.let { imageAspect=it.width.toFloat()/it.height }
                imageDetails=buildString {
                    appendLine("RGB在上，深度在下；单选时显示对应图像。")
                    pair.regionMask?.let { mask ->
                        mask.sideStatus.forEach { appendLine(it) }
                        appendLine(if(mask.geometryAvailable) "蒙皮：青色中央总宽%.0f厘米，红色仅表示侧区≤1米，灰色深度未知。".format(mask.forwardWidthMeters*100) else "区域未知：深度或内参不可用。")
                        appendLine("青色是中央检测范围；红色侧区不等于中央告警。白线：上/中/下；橙线：同距开口。透明区域不代表已确认可通行。")
                    }
                    pair.targets.forEach { box ->
                        appendLine("${YoloDetector.labels.getOrElse(box.classId) { "未知" }} ${(box.confidence*100).toInt()}% · ${box.distance.meters?.let { "最近 %.2f米".format(it) } ?: "距离未知"}")
                    }
                }.trim()
                preview.show(pair)
            }
            handler.postDelayed(this,50)
        }
    }
    private fun updateRendering() {
        if(isAttachedToWindow && windowVisibility==View.VISIBLE && isShown) {
            if(!active) {active=true;handler.post(refresh)}
        } else stopRendering()
    }
    override fun onAttachedToWindow() {super.onAttachedToWindow();updateRendering()}
    override fun onDetachedFromWindow() {stopRendering();super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(visibility:Int) {super.onWindowVisibilityChanged(visibility);updateRendering()}
    override fun onVisibilityChanged(changedView:View,visibility:Int) {super.onVisibilityChanged(changedView,visibility);if(isAttachedToWindow) updateRendering()}
}
