package com.helios.app
import org.junit.Assert.*
import org.junit.Test

class FallCandidateDetectorTest {
    private val gravity=listOf(0.0,9.81,0.0)
    private fun run(detector: FallCandidateDetector,end: Long=5000,signal: (Long)->Pair<DoubleArray,DoubleArray>): List<FallCandidate> {
        val found=mutableListOf<FallCandidate>()
        for(t in 1000L..end step 10) {
            val (a,g)=signal(t);detector.acceleration(t,a);detector.rotation(t,g)
            if(t%100==0L) detector.poll(t,gravity,true)?.let(found::add)
        }
        return found
    }
    private fun fall(t: Long): Pair<DoubleArray,DoubleArray> {
        val a=when {t<1800->doubleArrayOf(0.0,9.81,0.0);t<1860->doubleArrayOf(0.0,2.0,0.0)
            t<1900->doubleArrayOf(0.0,9.81,0.0);t<1930->doubleArrayOf(0.0,30.0,0.0);else->doubleArrayOf(9.81,0.0,0.0)}
        return a to if(t in 1800..2050) doubleArrayOf(2.0,0.0,0.0) else doubleArrayOf(0.0,0.0,0.0)
    }
    @Test fun impactTiltAndQuietChangedPoseProduceOneCandidate() {
        val found=run(FallCandidateDetector(),end=12_000,signal=::fall)
        assertEquals(1,found.size)
        assertTrue(found.single().lowGravityBeforeImpact)
        assertEquals(90.0,found.single().changedDegrees,0.01)
        assertTrue(found.single().timeMs>=3500)
    }
    @Test fun normalYawEvenWithAnImpactDoesNotTrigger() {
        assertTrue(run(FallCandidateDetector()) {t->
            doubleArrayOf(0.0,if(t in 1900..1920) 30.0 else 9.81,0.0) to doubleArrayOf(0.0,2.0,0.0)
        }.isEmpty())
    }
    @Test fun bendingAndQuickSittingWithoutImpactDoNotTrigger() {
        assertTrue(run(FallCandidateDetector()) {t->
            (if(t<2000) doubleArrayOf(0.0,9.81,0.0) else doubleArrayOf(9.81,0.0,0.0)) to
                (if(t in 1800..2100) doubleArrayOf(2.0,0.0,0.0) else doubleArrayOf(0.0,0.0,0.0))
        }.isEmpty())
    }
    @Test fun anAlreadyTiltedDroppedDeviceLacksUprightBeforeEvidence() {
        assertTrue(run(FallCandidateDetector()) {t->
            doubleArrayOf(if(t in 1900..1920) 30.0 else 9.81,0.0,0.0) to
                (if(t in 1800..2100) doubleArrayOf(2.0,0.0,0.0) else doubleArrayOf(0.0,0.0,0.0))
        }.isEmpty())
    }
    @Test fun diagnosticsDistinguishWeakImpactFromYawWithoutChangedPose() {
        val weak=FallCandidateDetector()
        run(weak) {t->doubleArrayOf(0.0,if(t%500==0L) 20.0 else 9.81,0.0) to doubleArrayOf(0.0,0.0,0.0)}
        assertEquals("impact_below_threshold",weak.status.reason)
        assertEquals(20.0,weak.status.rawPeakMs2,0.01)
        val yaw=FallCandidateDetector()
        run(yaw,end=3000) {t->doubleArrayOf(0.0,if(t in 1900..1920) 30.0 else 9.81,0.0) to doubleArrayOf(0.0,2.0,0.0)}
        assertEquals("angle_below_threshold",yaw.status.reason)
        assertEquals(0.0,yaw.status.changedDegrees,0.01)
    }

    @Test fun zeroGravityAndOverflowSamplesCannotCreateNonFiniteDiagnostics() {
        val d=FallCandidateDetector()
        assertTrue(run(d) {t->
            (if(t<2000) doubleArrayOf(0.0,9.81,0.0) else doubleArrayOf(0.0,0.0,0.0)) to doubleArrayOf(0.0,0.0,0.0)
        }.isEmpty())
        assertEquals("post_gravity_unavailable",d.status.reason)
        assertTrue(d.status.changedDegrees.isFinite())
        d.acceleration(6000,doubleArrayOf(Double.MAX_VALUE,Double.MAX_VALUE,0.0))
        d.rotation(6000,doubleArrayOf(Double.MAX_VALUE,0.0,0.0))
        assertNull(d.poll(6000,gravity,true))
        assertEquals("unavailable",d.status.reason)
    }

    @Test fun disabledStaleAndUncalibratedDataCannotFinishCandidate() {
        for(mode in 0..2) {
            val d=FallCandidateDetector()
            for(t in 1000L..2100 step 10) {val (a,g)=fall(t);d.acceleration(t,a);d.rotation(t,g);d.poll(t,gravity,true)}
            assertNull(d.poll(2200,if(mode==0) emptyList() else gravity,mode!=1))
            if(mode==2) assertNull(d.poll(3000,gravity,true))
        }
    }
    @Test fun anImuGapCannotBridgeTheImpactAndRecovery() {
        val d=FallCandidateDetector()
        for(t in 1000L..1930 step 10) {val (a,g)=fall(t);d.acceleration(t,a);d.rotation(t,g)}
        val found=mutableListOf<FallCandidate>()
        for(t in 2500L..5500 step 10) {val(a,g)=fall(t);d.acceleration(t,a);d.rotation(t,g);d.poll(t,gravity,true)?.let(found::add)}
        assertTrue(found.isEmpty())
    }
    @Test fun manuallyRaisedImpactThresholdChangesDetectionForTheSameRecordedSignal() {
        val detector=FallCandidateDetector()
        detector.configure(FallCandidateConfig(impactMetersPerSecond2=40.0))
        assertTrue(run(detector,signal=::fall).isEmpty())
        assertEquals(1,run(FallCandidateDetector(),signal=::fall).size)
    }
}
