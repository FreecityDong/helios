package com.helios.app

object LocalRiskSpeech {
    private fun name(classId:Int,labels:List<String>)=if(classId>=0) labels.getOrNull(classId) ?: "物体" else "障碍物"
    private fun hint(direction:AvoidanceDirection?)=when(direction) {
        AvoidanceDirection.LEFT -> SpatialHint.LEFT
        AvoidanceDirection.RIGHT -> SpatialHint.RIGHT
        null -> SpatialHint.UNKNOWN
    }
    fun spaceText(space:SpatialHint)=when(space) {
        SpatialHint.LEFT -> "左侧可见空间较开阔。"
        SpatialHint.RIGHT -> "右侧可见空间较开阔。"
        SpatialHint.BOTH -> "两侧均有可见空间。"
        SpatialHint.BLOCKED -> "两侧近处也有障碍，请留意。"
        SpatialHint.UNKNOWN -> "当前左右空间暂不能确认。"
    }
    fun stationary(level: RiskLevel, classId: Int, labels: List<String>, band: ImageHeightBand,
        spaceHint:SpatialHint?=null): String {
        val objectName=name(classId,labels)
        val core=when(level) {
            RiskLevel.URGENT -> "紧急，${band.label}${objectName}很近，请留意。"
            RiskLevel.WARNING -> "${band.label}有${objectName}，距离较近，请留意。"
            RiskLevel.CAUTION -> "${band.label}有${objectName}，请留意。"
        }
        return core+(spaceHint?.let(::spaceText) ?: "")
    }
    @Suppress("UNUSED_PARAMETER")
    fun directionUpdate(direction:AvoidanceDirection?,level:RiskLevel=RiskLevel.WARNING,spaceHint:SpatialHint?=null)=
        spaceText(spaceHint ?: hint(direction))

    @Suppress("UNUSED_PARAMETER")
    fun text(level:RiskLevel,classId:Int,labels:List<String>,kind:RiskEventKind=RiskEventKind.OBSTACLE,
        direction:AvoidanceDirection?=null,rememberedDirection:Boolean=false,observationOnly:Boolean=false,
        heightBand:ImageHeightBand=ImageHeightBand.FRONT,spaceHint:SpatialHint?=null):String {
        if(kind in setOf(RiskEventKind.HEADING_CLEAR,RiskEventKind.AREA_CLEARED))
            return "当前前方未见障碍，可缓慢前进。"
        if(kind==RiskEventKind.LEFT_FORWARD_VIEW) return "刚才的障碍已离开当前前进画面，请继续留意前方。"
        if(classId==-4) return "前方暂不能确认，请留意。"
        val objectName=name(classId,labels)
        val core=when(level) {
            RiskLevel.CAUTION -> "${heightBand.label}有${objectName}，请减速留意。"
            RiskLevel.WARNING -> "${heightBand.label}${objectName}较近，请停下确认。"
            RiskLevel.URGENT -> "立即停下，${heightBand.label}${objectName}很近。"
        }
        val space=if(rememberedDirection || kind==RiskEventKind.REOBSERVE) SpatialHint.UNKNOWN else spaceHint ?: hint(direction)
        return core+spaceText(space)
    }
}
