package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class PendingClearanceTest {
    private fun request()=SpeechRequest("clear","risk:nav:7:HEADING_CLEAR:CAUTION:1:h0",
        "当前前方未见障碍，可缓慢前进。",SpeechPriority.ORDINARY,1000,2500,
        riskMeters=2.3f,riskNavigating=false)
    private fun state(now:Long,revision:Long=0)=NavigationValidity(timeMs=now,available=true,clearedId=7,
        evidenceRevision=revision,forwardClear=true)
    @Test fun stationaryClearanceIsDeliveredOnceWithoutASecondUploadOrUserMovement() {
        val pending=PendingClearance();pending.offer(request())
        val spoken=pending.take(1100,state(1100),true)!!
        assertEquals("当前前方未见障碍，可缓慢前进。",spoken.text)
        assertNull(spoken.riskNavigating)
        assertNull(pending.take(1200,state(1200),true))
        pending.offer(request());assertNull(pending.take(1300,state(1300),true))
    }
    @Test fun dialogueCanFinishAfterOriginalEventExpiryButCurrentSceneMustStillBeClear() {
        val pending=PendingClearance();pending.offer(request())
        assertNull(pending.take(1100,state(1100),false))
        assertNull(pending.take(20000,state(20000,4),false))
        val spoken=pending.take(20100,state(20100,4),true)!!
        assertTrue(spoken.key.endsWith(":h4"))
        assertEquals(21600L,spoken.expiresMs)
        try {
            NavigationStateStore.update(state(20100,4))
            assertTrue(NavigationStateStore.valid(spoken.key,20100))
        } finally {NavigationStateStore.update(NavigationValidity())}
    }
    @Test fun aNewObstacleCancelsDelayedPermission() {
        val pending=PendingClearance();pending.offer(request())
        assertNull(pending.take(1100,state(1100).copy(episodeId=8,clearedId=null,forwardClear=false),false))
        assertNull(pending.take(1200,state(1200),true))
    }
    @Test fun uncertainOrStaleProofNeverSpeaksAndNeedsFreshConfirmedMeasurement() {
        for(uncertain in listOf(state(1100).copy(forwardClear=false),
            state(1100).copy(available=false),state(1100).copy(timeMs=100))) {
            val pending=PendingClearance();pending.offer(request())
            assertNull(pending.take(1100,uncertain,true))
            assertNull(pending.take(1200,state(1200).copy(forwardClear=false),true))
            assertNotNull(pending.take(2200,state(2200,5),true))
            assertNull(pending.take(2300,state(2300,5),true))
        }
    }
    @Test fun lifecycleResetDropsPendingClearanceAndNewHazardMayHaveItsOwnFeedback() {
        val pending=PendingClearance();pending.offer(request());pending.clear()
        assertNull(pending.take(1100,state(1100),true))
        pending.offer(request().copy(id="new",key="risk:nav:8:HEADING_CLEAR:CAUTION:1:h0"))
        assertNotNull(pending.take(1200,state(1200).copy(clearedId=8),true))
    }
    @Test fun explicitlyLostClearanceCannotPassQueueRevalidation() {
        try {
            NavigationStateStore.update(state(1100).copy(forwardClear=false))
            assertFalse(NavigationStateStore.valid(request().key,1100))
        } finally {NavigationStateStore.update(NavigationValidity())}
    }
    @Test fun oldSavedShorterClearTimeUpgradesWithoutResettingWidthOrOtherTuning() {
        val saved=DebugParameters.fromSaved(mapOf("clearHold" to .6,"forwardWidth" to 90.0,"urgentRepeat" to 3.0))
        assertEquals(1.0,saved["clearHold"],0.0)
        assertEquals(90.0,saved["forwardWidth"],0.0)
        assertEquals(3.0,saved["urgentRepeat"],0.0)
        assertEquals(1000L,saved.navigation.turnClearHoldMs)
        assertEquals(2.0,DebugParameters.fromSaved(mapOf("clearHold" to 2.0))["clearHold"],0.0)
        try {DebugParameters.defaults().patched(mapOf("clearHold" to .6));fail("short confirmation accepted")}
        catch(_:IllegalArgumentException) {}
    }
}
