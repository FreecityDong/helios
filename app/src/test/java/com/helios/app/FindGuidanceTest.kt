package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class FindGuidanceTest {
    private val target=FindTarget.resolve("手机")!!
    private val observed=FindObservation(true,.95,FindRegion(.1,.1,.3,.3),1.2f,true,"在桌面左侧，靠近显示器")
    @Test fun guidanceCombinesDirectionDistanceAndVisibleReference() {
        val text=FindGuidance.describe(target,observed,true)
        assertTrue(text.contains("左上方"));assertTrue(text.contains("约1.2米"));assertTrue(text.contains("桌面左侧"))
        assertTrue(text.contains("调整") || text.contains("对准"));assertFalse(text.contains("找物结束"))
    }
    @Test fun nearCompletionDoesNotAskForMoreCameraAiming() {
        val text=FindGuidance.describe(target,observed.copy(meters=.4f),false,true)
        assertTrue(text.contains("本次找物结束"));assertFalse(text.contains("对准"))
    }
    @Test fun stableViewsAreQuietUntilReminderInterval() {
        val policy=FindGuidance();assertTrue(policy.shouldSpeak(observed,0));policy.started(observed,0)
        assertFalse(policy.shouldSpeak(observed,4_000));assertTrue(policy.shouldSpeak(observed,8_000))
    }
    @Test fun newDirectionDepthReferenceOrObstaclePromptsAnUpdate() {
        val policy=FindGuidance();policy.started(observed,0)
        assertTrue(policy.shouldSpeak(observed.copy(region=FindRegion(.7,.1,.9,.3)),1_000))
        assertTrue(policy.shouldSpeak(observed.copy(meters=.7f),1_000))
        assertTrue(policy.shouldSpeak(observed.copy(context="在柜子旁"),1_000))
        assertTrue(policy.shouldSpeak(observed.copy(hazard=FindHazard("桌子",.8f)),1_000))
    }
    @Test fun largeObstacleIsMentionedWithoutAWalkingInstruction() {
        val text=FindGuidance.describe(target,observed.copy(hazard=FindHazard("桌子",.8f)),false)
        assertTrue(text.contains("前方近处有桌子，请留意"));assertFalse(text.contains("往前走"))
    }
    @Test fun notFoundDoesNotRepeatPreviousPositionAndProvidesScanGuidance() {
        val text=FindGuidance.notFound(target,FindObservation(false,context="桌面部分被遮挡"),2,true)
        assertTrue(text.contains("当前画面暂时看不到"));assertTrue(text.contains("遮挡"));assertTrue(text.contains("向右"))
        assertFalse(text.contains("约1.2米"))
    }
    @Test fun resetAfterMovementRequiresFreshGuidance() {
        val policy=FindGuidance();policy.started(observed,0);assertFalse(policy.shouldSpeak(observed,1_000))
        policy.reset();assertTrue(policy.shouldSpeak(observed,1_000))
    }
}
