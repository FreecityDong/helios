package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class LocalRiskSpeechTest {
    private val labels=listOf("人","椅子")
    @Test fun movingLevelsHaveDistinctActionsAndUrgentActionComesFirst() {
        for(band in ImageHeightBand.entries) {
            val caution=LocalRiskSpeech.text(RiskLevel.CAUTION,1,labels,RiskEventKind.TURN_GUIDANCE,heightBand=band)
            val warning=LocalRiskSpeech.text(RiskLevel.WARNING,1,labels,RiskEventKind.TURN_GUIDANCE,heightBand=band)
            val urgent=LocalRiskSpeech.text(RiskLevel.URGENT,1,labels,RiskEventKind.TURN_GUIDANCE,heightBand=band)
            assertTrue(caution.contains("减速留意"));assertFalse(caution.contains("停下"))
            assertTrue(warning.contains("请停下确认"));assertFalse(warning.contains("立即停下"))
            assertTrue(urgent.startsWith("立即停下"));assertTrue(urgent.contains(band.label))
        }
    }
    @Test fun noRiskVariantTellsTheUserToTurnOrTracksTheirTurn() {
        for(level in RiskLevel.entries) for(kind in RiskEventKind.entries)
            for(direction in listOf(null,AvoidanceDirection.LEFT,AvoidanceDirection.RIGHT)) {
                val text=LocalRiskSpeech.text(level,1,labels,kind,direction,rememberedDirection=true,observationOnly=true)
                for(order in listOf("原地","转动","转身","向左避让","向右避让","继续转","停止转"))
                    assertFalse("$kind $level: $text",text.contains(order))
                if(level==RiskLevel.CAUTION) assertFalse(text.contains("停下"))
            }
    }
    @Test fun allFiveSideStatesDescribeCurrentVisibleSpace() {
        val phrases=SpatialHint.entries.map(LocalRiskSpeech::spaceText)
        assertEquals(5,phrases.toSet().size)
        assertTrue(LocalRiskSpeech.spaceText(SpatialHint.BOTH).contains("两侧均有"))
        assertTrue(LocalRiskSpeech.spaceText(SpatialHint.BLOCKED).contains("两侧近处"))
        for(space in SpatialHint.entries) {
            val text=LocalRiskSpeech.text(RiskLevel.CAUTION,1,labels,spaceHint=space)
            assertTrue(text.contains(LocalRiskSpeech.spaceText(space)))
            assertFalse(text.contains("停下"))
        }
    }
    @Test fun sideUpdatesAtEveryRiskLevelContainNoTurnInstructions() {
        for(level in RiskLevel.entries) for(direction in listOf(null,AvoidanceDirection.LEFT,AvoidanceDirection.RIGHT)) {
            val text=LocalRiskSpeech.directionUpdate(direction,level)
            assertFalse(text.contains("停下"));assertFalse(text.contains("原地"));assertFalse(text.contains("转"))
            assertTrue(text.contains("空间"))
        }
    }
    @Test fun lowerImageBandIsSpokenAsBelowRatherThanGround() {
        assertEquals("下方",ImageHeightBand.GROUND.label)
        for(level in RiskLevel.entries) {
            val moving=LocalRiskSpeech.text(level,1,labels,heightBand=ImageHeightBand.GROUND)
            val still=LocalRiskSpeech.stationary(level,1,labels,ImageHeightBand.GROUND)
            assertTrue(moving.contains("下方"));assertTrue(still.contains("下方"))
            assertFalse(moving.contains("地面"));assertFalse(still.contains("地面"))
        }
    }
    @Test fun stationaryRiskHasNoStopOrGoInstructionAndCanDescribeSpace() {
        for(level in RiskLevel.entries) {
            val text=LocalRiskSpeech.stationary(level,1,labels,ImageHeightBand.FRONT,SpatialHint.RIGHT)
            assertTrue(text.contains("椅子"));assertTrue(text.contains("右侧可见空间"))
            assertFalse(text.contains("停下"));assertFalse(text.contains("前进"))
        }
    }
    @Test fun unknownObjectAndRememberedDirectionDoNotAssertOldSpaceAsCurrent() {
        val text=LocalRiskSpeech.text(RiskLevel.WARNING,-1,labels,direction=AvoidanceDirection.LEFT,rememberedDirection=true)
        assertTrue(text.contains("障碍物"));assertTrue(text.contains("暂不能确认"))
        assertFalse(text.contains("左侧可见空间较开阔"))
    }
    @Test fun releaseUsesCautiousForwardLanguageAndDoesNotClaimCompletedMovement() {
        for(kind in listOf(RiskEventKind.HEADING_CLEAR,RiskEventKind.AREA_CLEARED)) {
            val text=LocalRiskSpeech.text(RiskLevel.CAUTION,-1,labels,kind)
            assertTrue(text.contains("未见障碍"));assertTrue(text.contains("缓慢前进"))
            assertFalse(text.contains("已离开"))
        }
    }
}
