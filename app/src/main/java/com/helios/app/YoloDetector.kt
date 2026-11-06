package com.helios.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Thread confined to video worker; no SDK frame retained by inference. */
class YoloDetector(context: Context) : AutoCloseable {
    val inputSize = INPUT_SIZE
    val modelName = MODEL_NAME
    private val env = OrtEnvironment.getEnvironment()
    val backend = if (OrtEnvironment.getAvailableProviders().contains(OrtProvider.XNNPACK)) "XNNPACK" else "CPU"
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(if (backend == "XNNPACK") 1 else 2)
        setInterOpNumThreads(1)
        addConfigEntry("session.intra_op.allow_spinning", "0")
        if (backend == "XNNPACK") addXnnpack(mapOf("intra_op_num_threads" to "3"))
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val session = try {
        env.createSession(context.assets.open("yolo/$MODEL_NAME-$INPUT_SIZE.onnx").use { it.readBytes() }, options)
    } catch (error: Exception) { options.close(); throw error }
    private val input = ByteBuffer.allocateDirect(3*INPUT_SIZE*INPUT_SIZE*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val tensor = OnnxTensor.createTensor(env,input,longArrayOf(1,3,INPUT_SIZE.toLong(),INPUT_SIZE.toLong()))
    private val scaled = Bitmap.createBitmap(INPUT_SIZE,INPUT_SIZE,Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(scaled)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(INPUT_SIZE*INPUT_SIZE)
    private val channels = FloatArray(3*INPUT_SIZE*INPUT_SIZE)
    fun detect(bitmap: Bitmap): List<TargetBox> {
        val ratio = minOf(INPUT_SIZE.toFloat()/bitmap.width,INPUT_SIZE.toFloat()/bitmap.height)
        val w = (bitmap.width*ratio).roundToInt(); val h = (bitmap.height*ratio).roundToInt()
        val x = (INPUT_SIZE-w)/2; val y = (INPUT_SIZE-h)/2
        canvas.drawColor(0xff727272.toInt())
        canvas.drawBitmap(bitmap,null,Rect(x,y,x+w,y+h),paint)
        scaled.getPixels(pixels,0,INPUT_SIZE,0,0,INPUT_SIZE,INPUT_SIZE)
        for (i in pixels.indices) {
            val p = pixels[i]
            channels[i]=((p shr 16) and 255)/255f
            channels[i+pixels.size]=((p shr 8) and 255)/255f
            channels[i+pixels.size*2]=(p and 255)/255f
        }
        input.rewind(); input.put(channels); input.rewind()
        session.run(mapOf("images" to tensor)).use { results ->
            @Suppress("UNCHECKED_CAST")
            val output = results[0].value as Array<Array<FloatArray>>
            return DetectionGeometry.decode(output[0],bitmap.width,bitmap.height,INPUT_SIZE)
        }
    }
    override fun close() { try { tensor.close() } finally { try { session.close() } finally { options.close(); scaled.recycle() } } }
    companion object {
        private const val MODEL_NAME = "yolo26n"
        private const val INPUT_SIZE = 416
        val labels = "人,自行车,汽车,摩托车,飞机,公交车,火车,卡车,船,交通灯,消防栓,停车标志,停车计时器,长椅,鸟,猫,狗,马,羊,牛,象,熊,斑马,长颈鹿,背包,雨伞,手提包,领带,行李箱,飞盘,滑雪板,单板滑雪板,运动球,风筝,棒球棒,棒球手套,滑板,冲浪板,网球拍,瓶子,酒杯,杯子,叉子,刀,勺子,碗,香蕉,苹果,三明治,橙子,西兰花,胡萝卜,热狗,披萨,甜甜圈,蛋糕,椅子,沙发,盆栽,床,餐桌,马桶,电视,笔记本电脑,鼠标,遥控器,键盘,手机,微波炉,烤箱,烤面包机,水槽,冰箱,书,钟,花瓶,剪刀,泰迪熊,吹风机,牙刷".split(",")
    }
}
