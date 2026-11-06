package com.helios.app

import kotlin.math.*

data class TargetBox(val classId: Int, val confidence: Float, val left: Float, val top: Float, val right: Float, val bottom: Float,
    val distance: DepthEstimate = DepthEstimate(null, 0f, "未测距"))
data class DepthEstimate(val meters: Float?, val validRatio: Float, val reason: String)

/** Coordinates are full-resolution RGB/aligned-depth pixels, never preview pixels. */
object DetectionGeometry {
    fun decode(output: Array<FloatArray>, width: Int, height: Int, size: Int = 320): List<TargetBox> {
        require(output.size == 84 && output.all { it.size == output[0].size })
        val scale = min(size.toFloat()/width, size.toFloat()/height)
        val padX = (size - (width*scale).roundToInt())/2
        val padY = (size - (height*scale).roundToInt())/2
        val candidates = ArrayList<TargetBox>()
        for (i in output[0].indices) {
            var cls = 0; var score = 0f
            for (c in 0 until 80) if (output[c+4][i] > score) { score = output[c+4][i]; cls = c }
            if (!score.isFinite() || score < 0.35f) continue
            val cx = output[0][i]; val cy = output[1][i]; val w = output[2][i]; val h = output[3][i]
            if (!cx.isFinite() || !cy.isFinite() || !w.isFinite() || !h.isFinite() || w <= 0 || h <= 0) continue
            val l = ((cx-w/2-padX)/scale).coerceIn(0f,width.toFloat())
            val t = ((cy-h/2-padY)/scale).coerceIn(0f,height.toFloat())
            val r = ((cx+w/2-padX)/scale).coerceIn(0f,width.toFloat())
            val b = ((cy+h/2-padY)/scale).coerceIn(0f,height.toFloat())
            if (r-l >= 2 && b-t >= 2) candidates.add(TargetBox(cls,score,l,t,r,b))
        }
        return nms(candidates)
    }
    fun nms(boxes: List<TargetBox>): List<TargetBox> {
        val result = ArrayList<TargetBox>()
        for (box in boxes.sortedByDescending { it.confidence }.take(300)) {
            if (result.none { it.classId == box.classId && iou(it, box) > 0.45f }) result.add(box)
            if (result.size >= 30) break
        }
        return result
    }
    private fun iou(a: TargetBox,b: TargetBox): Float {
        val intersection = max(0f,min(a.right,b.right)-max(a.left,b.left))*max(0f,min(a.bottom,b.bottom)-max(a.top,b.top))
        return intersection / ((a.right-a.left)*(a.bottom-a.top)+(b.right-b.left)*(b.bottom-b.top)-intersection).coerceAtLeast(1e-6f)
    }
    fun distance(box: TargetBox, bytes: ByteArray, width: Int, height: Int, scaleMm: Float): DepthEstimate =
        distance(box, listOf(box), bytes, width, height, scaleMm)

    fun distance(box: TargetBox, detections: List<TargetBox>, bytes: ByteArray, width: Int, height: Int, scaleMm: Float): DepthEstimate {
        val index = detections.indexOfFirst { it === box }.takeIf { it >= 0 } ?: detections.indexOf(box)
        return if (index < 0) distances(listOf(box),bytes,width,height,scaleMm).first()
            else distances(detections,bytes,width,height,scaleMm)[index]
    }

    private class Sample(val width: Int, val height: Int, val depths: FloatArray, val seeds: BooleanArray,
        val sharedWith: IntArray, val candidates: FloatArray, val owned: Int, val valid: Int, val overlapping: Boolean,
        val partial: Boolean, val topologyAssigned: Boolean)

    private data class EdgeSegments(val count: Int, val shortest: Float)

    /** Lengths of this rectangle's edges that pass through the other rectangle. */
    private fun edgeSegments(box: TargetBox, other: TargetBox): EdgeSegments {
        val overlapWidth = min(box.right,other.right)-max(box.left,other.left)
        val overlapHeight = min(box.bottom,other.bottom)-max(box.top,other.top)
        if (overlapWidth <= 0f || overlapHeight <= 0f) return EdgeSegments(0,Float.POSITIVE_INFINITY)
        var count = 0; var shortest = Float.POSITIVE_INFINITY
        for (x in floatArrayOf(box.left,box.right)) if (x > other.left && x < other.right) {
            count++; shortest=min(shortest,overlapHeight)
        }
        for (y in floatArrayOf(box.top,box.bottom)) if (y > other.top && y < other.bottom) {
            count++; shortest=min(shortest,overlapWidth)
        }
        return EdgeSegments(count,shortest)
    }

    /** Each partial-overlap pixel is admitted only when two exclusive depth references clearly differ. */
    fun distances(boxes: List<TargetBox>, bytes: ByteArray, width: Int, height: Int, scaleMm: Float): List<DepthEstimate> {
        if (boxes.isEmpty()) return emptyList()
        if (width <= 0 || height <= 0 || bytes.size.toLong() < width.toLong()*height*2 || !scaleMm.isFinite() || scaleMm <= 0)
            return boxes.map { DepthEstimate(null,0f,"深度格式无效") }
        val samples = boxes.indices.map { i -> sample(boxes,i,bytes,width,height,scaleMm) }
        val exclusive = samples.map { surface(it,it.depths,it.owned,it.valid) }
        // A sparse but connected exclusive surface can still anchor shared pixels. The
        // ordinary 50% quality threshold applies to the final distance, after extension.
        val references = samples.indices.map { i ->
            exclusive[i].meters ?: if (samples[i].partial) {
                surface(samples[i],samples[i].depths,samples[i].owned,samples[i].valid,true).meters
            } else null
        }
        return boxes.indices.map { i ->
            val s = samples[i]
            val base = exclusive[i]
            val ownReference = references[i]?.times(1000f)
            if (!s.partial || ownReference == null) return@map base
            val extended = s.depths.copyOf()
            var assigned = 0
            for (p in s.sharedWith.indices) {
                val otherIndex = s.sharedWith[p]
                if (otherIndex < 0 || s.candidates[p] == 0f) continue
                val otherReference = references[otherIndex]?.times(1000f) ?: continue
                val depth = s.candidates[p]
                val ownDelta = abs(depth-ownReference)
                val otherDelta = abs(depth-otherReference)
                val separate = abs(ownReference-otherReference) > max(250f,max(ownReference,otherReference)*0.10f)
                if (separate && ownDelta <= max(150f,ownReference*0.10f) && ownDelta+100f < otherDelta) {
                    extended[p] = depth
                    assigned++
                }
            }
            if (assigned == 0) base else surface(s,extended,s.owned+assigned,s.valid+assigned)
                .takeIf { it.meters != null && it.reason == "交叉区域连续深度归属" } ?: base
        }
    }

    private fun sample(boxes: List<TargetBox>, index: Int, bytes: ByteArray, width: Int, height: Int, scaleMm: Float): Sample {
        val box = boxes[index]
        val dx = (box.right-box.left)*0.10f; val dy = (box.bottom-box.top)*0.10f
        val l = ceil(box.left+dx).toInt().coerceIn(0,width); val r = floor(box.right-dx).toInt().coerceIn(0,width)
        val t = ceil(box.top+dy).toInt().coerceIn(0,height); val b = floor(box.bottom-dy).toInt().coerceIn(0,height)
        if (r<=l || b<=t) return Sample(0,0,FloatArray(0),BooleanArray(0),IntArray(0),FloatArray(0),0,0,false,false,false)
        val step = max(1,max(r-l,b-t)/48)
        val gw = (r-l+step-1)/step; val gh = (b-t+step-1)/step
        val competitors = boxes.indices.filter { j -> j != index && boxes[j].right > box.left && boxes[j].left < box.right &&
            boxes[j].bottom > box.top && boxes[j].top < box.bottom }
        // Relation is constant for a pair of boxes; calculate it once, outside the sample grid.
        val relations = IntArray(boxes.size) { j ->
            val other = boxes[j]
            val boxContainsOther = box.left <= other.left && box.top <= other.top && box.right >= other.right && box.bottom >= other.bottom
            val otherContainsBox = other.left <= box.left && other.top <= box.top && other.right >= box.right && other.bottom >= box.bottom
            when {
                boxContainsOther && otherContainsBox -> if (other.confidence > box.confidence) 1 else 2
                boxContainsOther -> 1
                otherContainsBox -> 2
                else -> {
                    val ownEdges=edgeSegments(box,other)
                    val otherEdges=edgeSegments(other,box)
                    if (ownEdges.count+otherEdges.count==4 &&
                        (ownEdges.count==1 || otherEdges.count==1) &&
                        abs(ownEdges.shortest-otherEdges.shortest)>1f) {
                        if (ownEdges.shortest<otherEdges.shortest) 4 else 5
                    } else 3 // 2/2, aligned edges or a length tie: use depth continuity.
                }
            }
        }
        val depths = FloatArray(gw*gh); val seeds = BooleanArray(depths.size)
        val mayShare = competitors.any { relations[it] == 3 }
        val sharedWith = if (mayShare) IntArray(depths.size) { -1 } else IntArray(0)
        val candidates = if (mayShare) FloatArray(depths.size) else FloatArray(0)
        var owned = 0; var valid = 0; var overlapping = false; var partial = false; var topologyAssigned = false
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val px=l+gx*step; val py=t+gy*step; val p=gy*gw+gx
            var excluded = false; var partialOther = -1; var overlapCount = 0; var topologyOwned = false
            for (j in competitors) {
                val other=boxes[j]
                if (px < other.left || px >= other.right || py < other.top || py >= other.bottom) continue
                overlapCount++
                overlapping = true
                when (relations[j]) {
                    1 -> excluded = true // Smaller nested target owns the overlap.
                    2 -> Unit
                    4 -> topologyOwned = true // Shorter intersecting edge segment owns this 1/3 overlap.
                    5 -> excluded = true
                    else -> { partialOther = j; partial = true }
                }
            }
            if (excluded || overlapCount > 1) continue // A pairwise rule cannot settle three-way pixels.
            val offset=(py*width+px)*2
            val raw=(bytes[offset].toInt() and 255) or ((bytes[offset+1].toInt() and 255) shl 8)
            val mm=raw*scaleMm
            if (partialOther >= 0) {
                if (raw != 0 && mm in 200f..10000f) { sharedWith[p]=partialOther; candidates[p]=mm }
                continue
            }
            if (topologyOwned) topologyAssigned = true
            owned++
            if (raw != 0 && mm in 200f..10000f) { depths[p]=mm; seeds[p]=true; valid++ }
        }
        return Sample(gw,gh,depths,seeds,sharedWith,candidates,owned,valid,overlapping,partial,topologyAssigned)
    }

    private fun surface(s: Sample, depths: FloatArray, owned: Int, valid: Int, provisional: Boolean = false): DepthEstimate {
        if (depths.isEmpty()) return DepthEstimate(null,0f,"目标区域过小")
        if (owned < 12 || (s.partial && s.owned < s.depths.size*0.20f)) return DepthEstimate(null,0f,"重叠后可归属区域不足")
        val ratio = valid.toFloat()/owned
        if (valid < 12 || ratio < (if (provisional) 0.20f else 0.5f)) return DepthEstimate(null,ratio,"有效深度不足")
        val visited = BooleanArray(depths.size); val queue = IntArray(depths.size); val component = FloatArray(depths.size)
        val minSupport = if (provisional) max(12,ceil(owned*0.10f).toInt()) else max(8,ceil(valid*0.03f).toInt())
        var closest = Float.POSITIVE_INFINITY; var chosenUsesShared = false
        for (start in depths.indices) {
            if (visited[start] || depths[start] == 0f) continue
            visited[start] = true; queue[0] = start
            var head = 0; var tail = 1; var count = 0; var hasSeed = false; var hasShared = false
            while (head < tail) {
                val at = queue[head++]; val current = depths[at]
                component[count++] = current
                if (s.seeds[at]) hasSeed = true else hasShared = true
                val x=at%s.width; val y=at/s.width
                for (ny in max(0,y-1)..min(s.height-1,y+1)) for (nx in max(0,x-1)..min(s.width-1,x+1)) {
                    val next=ny*s.width+nx; val candidate=depths[next]
                    if (candidate>0f && !visited[next] && abs(candidate-current)<=max(150f,current*0.10f)) {
                        visited[next]=true; queue[tail++]=next
                    }
                }
            }
            if (count < minSupport || !hasSeed) continue
            component.sort(0,count)
            val percentile=component[(count*0.10f).toInt().coerceIn(0,count-1)]
            if (percentile < closest) { closest=percentile; chosenUsesShared=hasShared }
        }
        if (!closest.isFinite()) return DepthEstimate(null,ratio,"无连续有效表面")
        val reason = when {
            chosenUsesShared -> "交叉区域连续深度归属"
            s.topologyAssigned -> "一三边短交叉段优先"
            s.overlapping -> "未重叠区域最近连续表面"
            else -> "框内最近连续表面"
        }
        return DepthEstimate(closest/1000f,ratio,reason)
    }
}
