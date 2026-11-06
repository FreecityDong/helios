package com.helios.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class GyroCubeView(context: Context) : View(context) {
    var orientation = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    var fresh = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(18, 24, 32))
        val (w, x, y, z) = orientation
        fun project(a: Double, b: Double, c: Double): Pair<Float, Float> {
            val rx = (1-2*y*y-2*z*z)*a + (2*x*y-2*z*w)*b + (2*x*z+2*y*w)*c
            val ry = (2*x*y+2*z*w)*a + (1-2*x*x-2*z*z)*b + (2*y*z-2*x*w)*c
            val rz = (2*x*z-2*y*w)*a + (2*y*z+2*x*w)*b + (1-2*x*x-2*y*y)*c
            // Fixed oblique view makes all three axes visible at reset.
            return Pair((width*0.5 + (rx+rz*0.45)*height*0.23).toFloat(), (height*0.48 + (ry-rz*0.3)*height*0.23).toFloat())
        }
        val points = (0..7).map { project(if (it and 1 == 0) -1.0 else 1.0, if (it and 2 == 0) -1.0 else 1.0, if (it and 4 == 0) -1.0 else 1.0) }
        paint.strokeWidth = 3f
        paint.color = if (fresh) Color.CYAN else Color.GRAY
        for (i in 0..7) for (mask in listOf(1, 2, 4)) if (i and mask == 0) {
            val a = points[i]; val b = points[i or mask]; canvas.drawLine(a.first, a.second, b.first, b.second, paint)
        }
        val origin = project(0.0, 0.0, 0.0)
        listOf(Triple(1.5, 0.0, 0.0), Triple(0.0, 1.5, 0.0), Triple(0.0, 0.0, 1.5)).forEachIndexed { i, v ->
            val end = project(v.first, v.second, v.third)
            paint.color = listOf(Color.RED, Color.GREEN, Color.BLUE)[i]
            canvas.drawLine(origin.first, origin.second, end.first, end.second, paint)
            paint.textSize = 24f; canvas.drawText(listOf("X", "Y", "Z")[i], end.first, end.second, paint)
        }
        paint.color = Color.WHITE; paint.textSize = 24f
        canvas.drawText(if (fresh) "陀螺仪相对姿态 · 点击归零（存在漂移）" else "陀螺仪无新数据", 12f, height-10f, paint)
    }
}
