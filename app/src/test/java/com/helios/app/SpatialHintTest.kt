package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class SpatialHintTest {
    @Test fun bothAndBlockedRequireMatchingEvidenceOnBothSides() {
        val possible=SideTurnStatus.POSSIBLE;val blocked=SideTurnStatus.BLOCKED;val unknown=SideTurnStatus.UNKNOWN
        assertEquals(SpatialHint.BOTH,SpatialHintStability.classify(possible,possible))
        assertEquals(SpatialHint.BLOCKED,SpatialHintStability.classify(blocked,blocked))
        assertEquals(SpatialHint.LEFT,SpatialHintStability.classify(possible,unknown))
        assertEquals(SpatialHint.RIGHT,SpatialHintStability.classify(unknown,possible))
        assertEquals(SpatialHint.UNKNOWN,SpatialHintStability.classify(unknown,blocked))
    }
    @Test fun newSpaceNeedsStableSupportButOldSpaceIsRevokedImmediately() {
        val stability=SpatialHintStability()
        assertEquals(SpatialHint.UNKNOWN,stability.observe(SpatialHint.LEFT,0))
        assertEquals(SpatialHint.UNKNOWN,stability.observe(SpatialHint.LEFT,200))
        assertEquals(SpatialHint.LEFT,stability.observe(SpatialHint.LEFT,500))
        assertEquals(SpatialHint.UNKNOWN,stability.observe(SpatialHint.RIGHT,600))
        assertEquals(SpatialHint.UNKNOWN,stability.observe(SpatialHint.RIGHT,800))
        assertEquals(SpatialHint.RIGHT,stability.observe(SpatialHint.RIGHT,1100))
        stability.reset();assertEquals(SpatialHint.UNKNOWN,stability.current)
    }
    @Test fun stationarySpaceChangesYieldToConversationAndStayDirectionOnly() {
        val policy=RiskSpeechPolicy()
        val first=SpeechRequest("a","risk:nav:1:TURN_GUIDANCE:WARNING:a:h0","risk",SpeechPriority.WARNING_SAFETY,
            0,1500,riskMeters=.8f,riskSpace=SpatialHint.LEFT)
        policy.started(first,0)
        val changed=first.copy(id="b",createdMs=100,expiresMs=1600,riskSpace=SpatialHint.RIGHT)
        assertFalse(policy.assess(changed,100,false,true).allowed)
        assertEquals(RiskSpeechMode.DIRECTION_UPDATE,policy.assess(changed,100,false,false).mode)
        policy.started(changed.copy(riskMode=RiskSpeechMode.DIRECTION_UPDATE),100)
        assertFalse(policy.assess(changed.copy(id="c",expiresMs=1800),200,false,false).allowed)
    }
    @Test fun sameBothSideDescriptionDoesNotRepeatWhenSelectedScoringSideChanges() {
        val policy=RiskSpeechPolicy()
        val first=SpeechRequest("a","risk:nav:1:TURN_GUIDANCE:WARNING:a:h0","risk",SpeechPriority.WARNING_SAFETY,
            0,1500,riskMeters=.8f,riskSpace=SpatialHint.BOTH,riskDirection=AvoidanceDirection.LEFT)
        policy.started(first,0,true)
        assertFalse(policy.assess(first.copy(id="b",riskDirection=AvoidanceDirection.RIGHT),100,true,false).allowed)
    }
    @Test fun queuedOldSpaceIsInvalidatedByCurrentGeometry() {
        try {
            NavigationStateStore.update(NavigationValidity(1000,true,1,RiskLevel.WARNING,
                reobserveDirection=AvoidanceDirection.LEFT,evidenceRevision=0,spaceHint=SpatialHint.BOTH))
            val key="risk:nav:1:TURN_GUIDANCE:WARNING:a:h0"
            assertFalse(NavigationStateStore.valid(key,1000,space=SpatialHint.LEFT))
            assertTrue(NavigationStateStore.valid(key,1000,space=SpatialHint.BOTH))
        } finally {NavigationStateStore.update(NavigationValidity())}
    }
    @Test fun currentSpaceSupplementCanFinishWhileStandingStillWithoutClaimingAUserAction() {
        val buffer=RiskDetailBuffer()
        val request=SpeechRequest("a","risk:nav:1:TURN_GUIDANCE:WARNING:a:h0",
            "前方有障碍物，请减速留意。两侧均有可见空间。",SpeechPriority.SAFETY,0,1500,
            riskSpace=SpatialHint.BOTH,riskNavigating=true)
        buffer.offer(request)
        val detail=buffer.take(request,1000) {it.riskSpace==SpatialHint.BOTH}!!
        assertNull(detail.riskNavigating)
        assertEquals("两侧均有可见空间。",detail.text)
    }
}
