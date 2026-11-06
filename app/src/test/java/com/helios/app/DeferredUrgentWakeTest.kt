package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class DeferredUrgentWakeTest {
    private fun speech(priority:SpeechPriority=SpeechPriority.URGENT_SAFETY,detail:Boolean=false)=
        SpeechRequest("urgent-1","risk:nav:1:ALERT","前方障碍物很近。",priority,0,10_000,riskDetail=detail)

    @Test fun urgentCoreFinishesBeforeOneRememberedWakeIsConsumed() {
        val pending=DeferredUrgentWake()
        assertTrue(pending.hold(speech(),"hi helios"))
        assertEquals("urgent-1",pending.speechId)
        assertEquals("hi helios",pending.take())
        assertNull(pending.take());assertNull(pending.speechId)
    }
    @Test fun ordinaryWarningsAndDirectionSupplementsStillAllowImmediateWake() {
        for(priority in SpeechPriority.entries.filter {it!=SpeechPriority.URGENT_SAFETY}) {
            val pending=DeferredUrgentWake()
            assertFalse(pending.hold(speech(priority),"hi helios"));assertNull(pending.take())
        }
        assertFalse(DeferredUrgentWake().hold(speech(detail=true),"hi helios"))
        assertFalse(DeferredUrgentWake().hold(null,"hi helios"))
    }
    @Test fun repeatedWakeDoesNotReplaceOrMultiplyThePendingCommandWindow() {
        val pending=DeferredUrgentWake()
        pending.hold(speech(),"first")
        pending.hold(speech().copy(id="urgent-2"),"second")
        assertEquals("urgent-1",pending.speechId)
        assertEquals("first",pending.take());assertNull(pending.take())
    }
    @Test fun stopFaultOrFallAudioHandoffClearsTheRememberedWake() {
        val pending=DeferredUrgentWake()
        pending.hold(speech(),"hi helios");pending.clear()
        assertNull(pending.speechId);assertNull(pending.take())
    }
    @Test fun rememberedWakeOpensInputBeforeQueuedRiskOrDirectionSupplement() {
        val pending=DeferredUrgentWake();val arbiter=SpeechArbiter();val core=speech()
        arbiter.submit(core,0)
        arbiter.submit(core.copy(id="detail",key="risk:nav:1:direction",riskDetail=true),1,queueOnly=true)
        pending.hold(core,"hi helios")
        assertNull(arbiter.complete(core.id,2,advance=false))
        assertEquals("hi helios",pending.take())
        // Same handoff as handleWakeWord: old queued speech cannot own the new input window.
        arbiter.clear()
        assertNull(arbiter.resume(3))
    }
}
