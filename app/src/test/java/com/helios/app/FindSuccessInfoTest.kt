package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class FindSuccessInfoTest {
    private fun completion() = FindCompletion(1,FindTarget.resolve("杯子")!!,
        FindObservation(true,.95,FindRegion(.7,.7,.9,.9),.4f,true,"在桌面上，靠近显示器"),2_000,1_000)
    @Test fun supplementsSuccessWithPositionDistanceAndReferenceWithoutRepeatingConfirmation() {
        val text=FindSuccessInfo(completion()).text()
        assertTrue(text.contains("右下方"));assertTrue(text.contains("约40厘米"));assertTrue(text.contains("桌面上，靠近显示器"))
        assertFalse(text.contains("已经找到"));assertFalse(text.contains("对准"))
    }
    @Test fun staleDetailIsDroppedWithoutChangingCompletionFact() {
        val done=completion();val info=FindSuccessInfo(done)
        assertTrue(info.valid(9_000));assertFalse(info.valid(9_001));assertEquals("杯子",done.target.name)
    }
    @Test fun movingOrTurningDuringShortSuccessIrreversiblyInvalidatesOldPosition() {
        val info=FindSuccessInfo(completion());info.observe(false);info.observe(true)
        assertFalse(info.valid(3_000));assertEquals(1L,info.completion.taskId)
    }
    @Test fun absentContextDoesNotInventReferenceFurniture() {
        val info=FindSuccessInfo(completion().copy(observation=completion().observation.copy(context="")))
        assertFalse(info.text().contains("桌"));assertFalse(info.text().contains("柜"))
    }
}
