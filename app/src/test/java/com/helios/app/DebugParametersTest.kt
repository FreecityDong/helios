package com.helios.app
import org.junit.Assert.*
import org.junit.Test

class DebugParametersTest {
    private fun invalid(patch:Map<String,Double>) {
        try {DebugParameters.defaults().patched(patch);fail("invalid parameters accepted")}
        catch (_:IllegalArgumentException) {}
    }
    @Test fun defaultsPreserveTheExistingProductionThresholds() {
        val p=DebugParameters.defaults()
        assertEquals(NavigationConfig(),p.navigation)
        assertEquals(FallCandidateConfig(),p.fallCandidate)
        assertEquals(FallConfig(),p.fallFlow)
        assertEquals(MotionConfig(),p.motion)
    }
    @Test fun invalidRangesNonFiniteAndUnknownFieldsAreRejected() {
        invalid(mapOf("moveAccel" to Double.NaN))
        invalid(mapOf("urgentRepeat" to Double.POSITIVE_INFINITY))
        invalid(mapOf("fallImpact" to -1.0))
        invalid(mapOf("not_a_setting" to 1.0))
    }
    @Test fun distanceLevelsCannotBeReversedOrEqual() {
        invalid(mapOf("urgent" to 1.2))
        invalid(mapOf("warning" to 2.0))
        invalid(mapOf("clear" to 1.9))
    }
    @Test fun impossibleFallResponseAndEvidenceWindowsAreRejected() {
        invalid(mapOf("fallResponse" to 40.0,"fallWall" to 40.0))
        invalid(mapOf("fallQuiet" to 3.0,"fallWindow" to 2.0))
        invalid(mapOf("fallRepeat" to 6.0,"fallGap" to 8.0))
    }
    @Test fun groupUpdatesPreserveOtherGroupsAndRoundTripTheFullStoredMap() {
        val p=DebugParameters.defaults().patched(mapOf("moveAccel" to .4,"urgentRepeat" to 3.5))
        assertEquals(.4,p.motion.acceleration,.0001)
        assertEquals(3_500L,p.navigation.urgentRepeatMs)
        assertEquals(FallCandidateConfig(),p.fallCandidate)
        assertEquals(p,DebugParameters.from(p.asMap()))
    }
    @Test fun changingDistancesActuallyChangesRiskLevels() {
        val p=DebugParameters.defaults().patched(mapOf("urgent" to .3,"warning" to .6))
        assertEquals(2,NavigationConfig().distancePriority(.75f))
        assertEquals(1,p.navigation.distancePriority(.75f))
    }
    @Test fun customCooldownActuallyChangesWhenMovingSpeechRepeats() {
        val p=DebugParameters.defaults().patched(mapOf("warningRepeat" to 6.0))
        val policy=RiskSpeechPolicy(p.navigation)
        fun request(id:String,now:Long)=SpeechRequest(id,"risk:nav:1:TURN_GUIDANCE:WARNING:$id:h0","风险",
            SpeechPriority.WARNING_SAFETY,now,now+1_500,riskMeters=.8f,riskBand=ImageHeightBand.FRONT)
        policy.started(request("first",0),0,moving=true)
        assertFalse(policy.assess(request("second",4_100),4_100,true,false).allowed)
        assertEquals(RiskSpeechMode.REPEAT,policy.assess(request("third",6_100),6_100,true,false).mode)
    }
    @Test fun changingMotionSensitivityKeepsCalibrationAndChangesTheActualDecision() {
        val t=MotionTracker();t.requestCalibration()
        fun sample(now:Long,amplitude:Double) {
            val x=if(now/100%2L==0L) amplitude else -amplitude
            t.addAcceleration(now,doubleArrayOf(x,0.0,9.81));t.addRotation(now,doubleArrayOf(0.0,0.0,0.0))
            if(now%100==0L)t.snapshot(now)
        }
        for(now in 100L..4_000L step 10)sample(now,0.0)
        for(now in 4_010L..7_000L step 10)sample(now,.5)
        assertEquals(MotionState.STILL,t.snapshot(7_000).state)
        t.configure(DebugParameters.defaults().patched(mapOf("moveAccel" to .3)).motion)
        for(now in 7_010L..9_000L step 10)sample(now,.5)
        assertEquals(MotionState.MOVING,t.snapshot(9_000).state)
        assertTrue(t.snapshot(9_000).calibrated)
    }
    @Test fun activeFallResponseCannotHaveItsTimingReconfigured() {
        val flow=FallFlow();flow.trigger(1_000,"test")
        try {flow.configure(FallConfig(responseMs=30_000));fail("active response changed")}
        catch (_:IllegalStateException) {}
        assertEquals(20_000L,flow.config.responseMs)
    }
}
