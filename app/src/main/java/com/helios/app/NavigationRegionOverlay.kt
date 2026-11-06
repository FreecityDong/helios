package com.helios.app

import kotlin.math.abs

/** Per-depth-point camera-relative debug mask. Unmeasured pixels never become free space. */
data class NavigationRegionMask(val sourceWidth: Int,val sourceHeight: Int,
    val columns: Int,val rows: Int,val cellSize: Int,val regions: ByteArray,
    val geometryAvailable: Boolean, val sideStatus: List<String> = emptyList(),
    val openingMeasurements: List<OpeningMeasurement> = emptyList(),
    val forwardWidthMeters: Float = HorizontalProjection.DEFAULT_FORWARD_WIDTH_METERS)

object NavigationRegionOverlay {
    const val SIDE_CLEAR: Byte = 0
    const val FORWARD: Byte = 1
    const val SIDE_TOO_CLOSE: Byte = 2
    const val UNKNOWN: Byte = 3
    const val SIDE_DISTANCE_METERS = 1.0f

    fun withCorridorStatus(mask: NavigationRegionMask,guidance: AvoidanceSnapshot): NavigationRegionMask {
        if(guidance.geometryMode=="turn_priority") {
            fun simple(name: String,side: SideTurnEvidence?,direction: AvoidanceDirection): String {
                val status=when {
                    side==null -> "等待检测"
                    guidance.reobserveDirection==direction -> "当前可见空间较开阔"
                    side.status==SideTurnStatus.POSSIBLE -> "有开口迹象"
                    side.status==SideTurnStatus.BLOCKED -> "有近物"
                    else -> "证据不足"
                }
                val coverage=side?.coverage?.takeIf {it.size==3}?.let { "\n上/中/下 %.0f/%.0f/%.0f%%".format(it[0]*100,it[1]*100,it[2]*100) } ?: ""
                return "$name：$status"+(side?.opening?.let { "\n已见%.2fm · %.0f°".format(it.widthMeters,side.angleDegrees) } ?: "")+coverage
            }
            return mask.copy(sideStatus=listOf(simple("左侧",guidance.turnLeft,AvoidanceDirection.LEFT),simple("右侧",guidance.turnRight,AvoidanceDirection.RIGHT)),
                openingMeasurements=listOfNotNull(guidance.turnLeft?.opening,guidance.turnRight?.opening))
        }
        fun line(name: String,evidence: CorridorEvidence?,direction: AvoidanceDirection): String {
            val status=when {
                evidence==null -> "未评估"
                evidence.availableForObservation && guidance.direction==direction -> "建议观察（脚下待确认）"
                evidence.availableForObservation -> "候选待确认（脚下待确认）"
                evidence.sideSpace?.eligible==true && guidance.reobserveDirection==direction -> "可见空间待确认"
                evidence.sideSpace?.eligible==true -> "开口迹象，等待复核确认"
                evidence.reason=="no_current_obstacle" -> "无前方风险，未评估通道"
                evidence.reason=="near_body_coverage_unknown" -> "近身空间未知"
                evidence.reason=="intrinsics_unavailable" -> "内参不可用"
                evidence.reason=="depth_unavailable" -> "深度不可用"
                evidence.coverageFailure=="occupied" -> "被挡"+(evidence.blockedHeightBand?.let { "（${it.label}）" } ?: "")
                evidence.coverageFailure=="out_of_view" -> "视野不足"
                evidence.coverageFailure=="invalid_depth" -> "深度不足"
                evidence.coverageFailure=="occluded_or_near_return" -> "遮挡/近物"
                evidence.coverageFailure=="insufficient_opening" -> "开口不足"
                guidance.reason=="corridor_motion_unavailable" -> "运动状态不适合判断"
                guidance.reason=="corridor_pose_unavailable" -> "姿态未知"
                else -> "通道未确认"
            }
            val widths=if (evidence?.diagnosticsAvailable==true)
                (when(evidence.openingMeasurement?.boundary) {
                    "invalid_depth" -> " · 开口未测完整（缺测）"
                    "obstacle_continues_out_of_view" -> " · 障碍延伸出画面"
                    else -> ""
                })+
                " · 已见%s / 需%.2fm".format(evidence.measuredSideOpeningWidthMeters?.let {
                    (if (evidence.openingMeasurement?.boundary in listOf("field_of_view","invalid_depth")) "≥" else "")+"%.2fm".format(it)
                } ?: "未知",evidence.requiredWidthMeters)+
                (evidence.openingMeasurement?.let { " @%.2fm".format(it.distanceMeters) } ?: "") else ""
            val space=evidence?.sideSpace
            val coverage=if(space?.upper!=null && space.middle!=null && space.lower!=null)
                " · 上/中/下覆盖%.0f/%.0f/%.0f%% · 未知块%.2fm".format(
                    space.upper.validRatio*100,space.middle.validRatio*100,space.lower.validRatio*100,space.middle.largestUnknownMeters) else ""
            return "$name：$status$widths$coverage"
        }
        return mask.copy(sideStatus=listOf(line("左侧",guidance.corridorLeft,AvoidanceDirection.LEFT),
            line("右侧",guidance.corridorRight,AvoidanceDirection.RIGHT)),
            openingMeasurements=listOfNotNull(guidance.corridorLeft?.openingMeasurement,guidance.corridorRight?.openingMeasurement))
    }

    fun classify(pixelX:Float,z:Float?,camera:HorizontalProjection):Byte =
        if(z==null) UNKNOWN else classifyMeasured(pixelX,z,camera)

    private fun classifyMeasured(pixelX:Float,z:Float,camera:HorizontalProjection):Byte {
        if(!z.isFinite() || z !in 0.25f..HorizontalProjection.MAX_OBSERVED_DEPTH_METERS) return UNKNOWN
        if(camera.inForward(pixelX,z)) return FORWARD
        return if(z<=SIDE_DISTANCE_METERS) SIDE_TOO_CLOSE else SIDE_CLEAR
    }

    /** Exact source-pixel geometry. Five-point tile sampling can hide an in-region surface. */
    fun build(bytes:ByteArray,width:Int,height:Int,scaleMm:Float,
        camera:HorizontalProjection?):NavigationRegionMask {
        require(width>0 && height>0)
        val regions=ByteArray(width*height) {UNKNOWN}
        val valid=camera?.matches(width,height)==true && scaleMm.isFinite() && scaleMm>0f &&
            bytes.size.toLong()>=width.toLong()*height*2
        if(valid) for(y in 0 until height) for(x in 0 until width) {
            val offset=(y*width+x)*2
            val raw=(bytes[offset].toInt() and 255) or ((bytes[offset+1].toInt() and 255) shl 8)
            if(raw!=0) regions[y*width+x]=classifyMeasured(x.toFloat(),raw*scaleMm/1000f,camera!!)
        }
        return NavigationRegionMask(width,height,width,height,1,regions,valid,
            forwardWidthMeters=camera?.forwardWidthMeters ?: HorizontalProjection.DEFAULT_FORWARD_WIDTH_METERS)
    }
}
