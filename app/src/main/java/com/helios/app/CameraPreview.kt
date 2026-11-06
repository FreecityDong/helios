package com.helios.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicLong

class PreviewPair(val color: Bitmap?, val depth: Bitmap?, private val released: AtomicLong, val targets: List<TargetBox> = emptyList(),
    val regionMask: NavigationRegionMask? = null) : AutoCloseable {
    override fun close() { color?.recycle(); depth?.recycle(); released.incrementAndGet() }
}

/** lockCanvas is a synchronous software draw: bitmaps can be recycled after unlockCanvasAndPost. */
class CameraPreview(context: Context) : SurfaceView(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val regionPaint = Paint()
    var showRegions = true
    var showBoxes = true
    var showLabels = true
    var showColor = true
    var showDepth = true
    fun show(pair: PreviewPair) {
        pair.use {
            if (!holder.surface.isValid) return
            val canvas = holder.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val count = (if (showColor) 1 else 0) + (if (showDepth) 1 else 0)
                if (count == 0) return
                val areaHeight = canvas.height / count
                fun draw(bitmap: Bitmap, top: Int, areaHeight: Int, isColor: Boolean) {
                    val scale = minOf(canvas.width.toFloat() / bitmap.width, areaHeight.toFloat() / bitmap.height)
                    val w = (bitmap.width * scale).toInt(); val h = (bitmap.height * scale).toInt()
                    val left = (canvas.width-w)/2; val y = top+(areaHeight-h)/2
                    canvas.drawBitmap(bitmap, null, Rect(left, y, left+w, y+h), paint)
                    if (showRegions) pair.regionMask?.let { mask ->
                        // RGB and aligned-depth previews share image coordinates,
                        // even when the depth bitmap is downsampled for display.
                        val sx=w.toFloat()/mask.sourceWidth;val sy=h.toFloat()/mask.sourceHeight
                        for (row in 0 until mask.rows) {
                            var col=0
                            while (col<mask.columns) {
                                val type=mask.regions[row*mask.columns+col]
                                val begin=col++
                                while (col<mask.columns && mask.regions[row*mask.columns+col]==type) col++
                                regionPaint.color=when(type) {
                                    NavigationRegionOverlay.FORWARD -> 0x5500d9ff
                                    NavigationRegionOverlay.SIDE_TOO_CLOSE -> 0x99ff3030.toInt()
                                    NavigationRegionOverlay.UNKNOWN -> 0x55777777
                                    else -> Color.TRANSPARENT
                                }
                                if (type != NavigationRegionOverlay.SIDE_CLEAR) {
                                    canvas.drawRect(left+begin*mask.cellSize*sx,y+row*mask.cellSize*sy,
                                        left+minOf(mask.sourceWidth,col*mask.cellSize)*sx,
                                        y+minOf(mask.sourceHeight,(row+1)*mask.cellSize)*sy,regionPaint)
                                }
                            }
                        }
                        regionPaint.color=0xffffa500.toInt();regionPaint.strokeWidth=3f
                        mask.openingMeasurements.forEachIndexed { index,measurement ->
                            val a=measurement.startPixel;val b=measurement.endPixel
                            if (a!=null && b!=null) {
                                val yy=y+h*(if(index==0) 0.47f else 0.55f)
                                canvas.drawLine(left+a*sx,yy,left+b*sx,yy,regionPaint)
                                canvas.drawLine(left+a*sx,yy-8,left+a*sx,yy+8,regionPaint)
                                canvas.drawLine(left+b*sx,yy-8,left+b*sx,yy+8,regionPaint)
                            }
                        }
                        regionPaint.color=Color.WHITE;regionPaint.style=Paint.Style.STROKE;regionPaint.strokeWidth=2f
                        canvas.drawLine(left.toFloat(),y+h/3f,(left+w).toFloat(),y+h/3f,regionPaint)
                        canvas.drawLine(left.toFloat(),y+h*2f/3f,(left+w).toFloat(),y+h*2f/3f,regionPaint)
                        regionPaint.style=Paint.Style.FILL

                    }
                    if (isColor && showBoxes) for (box in pair.targets) {
                        val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.YELLOW; strokeWidth=3f; style=Paint.Style.STROKE }
                        val bx=left+box.left*scale; val by=y+box.top*scale
                        canvas.drawRect(bx,by,left+box.right*scale,y+box.bottom*scale,overlay)
                        if (!showLabels) continue
                        val distance = box.distance.meters?.let { "最近 %.2f m".format(it) } ?: "距离未知"
                        val label = "${YoloDetector.labels.getOrElse(box.classId) { "未知" }} ${(box.confidence*100).toInt()}% $distance"
                        overlay.style=Paint.Style.FILL; overlay.textSize=26f
                        val textX=bx.coerceIn(left.toFloat(),maxOf(left.toFloat(),left+w-overlay.measureText(label)))
                        val textY=maxOf(y+28f,by)
                        overlay.color=0xcc000000.toInt()
                        canvas.drawRect(textX,textY-28,textX+overlay.measureText(label),textY+4,overlay)
                        overlay.color=Color.YELLOW; canvas.drawText(label,textX,textY,overlay)
                    }
                }
                if (showColor) pair.color?.let { draw(it, 0, areaHeight, true) }
                if (showDepth) pair.depth?.let { draw(it, if (showColor) areaHeight else 0, areaHeight, false) }
            } finally { holder.unlockCanvasAndPost(canvas) }
        }
    }
    fun clearPreview() {
        if (!holder.surface.isValid) return
        val canvas = holder.lockCanvas() ?: return
        try { canvas.drawColor(Color.BLACK) } finally { holder.unlockCanvasAndPost(canvas) }
    }
}
