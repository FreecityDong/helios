package com.helios.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.acos
import kotlin.math.abs

class GyroBiasTest {
    @Test fun wornCalibrationEstimatesBiasWithoutErasingRawMotionMeasurements() {
        val tracker = MotionTracker(); tracker.requestCalibration()
        for (t in 100L..4_000L step 10) {
            tracker.addAcceleration(t, doubleArrayOf(0.0, 0.0, 9.81))
            tracker.addRotation(t, doubleArrayOf(0.002, -0.003, 0.004))
            if (t % 100L == 0L) tracker.snapshot(t)
        }
        val snapshot = tracker.snapshot(4_000)
        assertTrue(snapshot.calibrated)
        assertArrayEquals(doubleArrayOf(0.002, -0.003, 0.004), snapshot.gyroBias.toDoubleArray(), 1e-9)
        assertTrue(snapshot.rotationRate > 0.005)
        assertEquals(MotionState.STILL, snapshot.state)
    }
    @Test fun aSmallUncorrectedBiasCanReproduceAFalseTurnWhileStill() {
        val pose = GyroOrientation(); val gate = SceneMotionGate()
        val still = MotionSnapshot(state = MotionState.STILL, stale = false, calibrated = true, rotationRate = 0.0054)
        var changed = false
        for (i in 0..6_000) {
            pose.update(1_000_000L + i * 5_000L, 0.0, 0.0054, 0.0)
            if (i % 20 == 0) { gate.update(i * 5L, still, pose.snapshot()); changed = changed || gate.viewChanged }
        }
        assertTrue(changed)
    }
    @Test fun threeMinutesOfCalibratedStillnessTriggersOnlyOneAutomaticDescription() {
        val pose = GyroOrientation(); assertTrue(pose.calibrateStationaryBias(listOf(0.0, 0.0054, 0.0)))
        val gate = SceneMotionGate(); val flow = SceneFlow()
        val still = MotionSnapshot(state = MotionState.STILL, stale = false, calibrated = true, rotationRate = 0.0054)
        var requests = 0
        for (i in 0..36_000) {
            pose.update(1_000_000L + i * 5_000L, 0.0, 0.0054, 0.0)
            if (i % 20 == 0) {
                val now = i * 5L
                val ready = gate.update(now, still, pose.snapshot())
                val auto = flow.observe(now, ready, false, gate.viewChanged)
                assertFalse(gate.viewChanged)
                if (auto) {
                    val ticket = flow.begin(now, false)!!; requests++
                    flow.finish(ticket.id, now); flow.complete(ticket.id)
                }
            }
        }
        assertEquals(1, requests)
    }
    @Test fun correctingBiasDoesNotHideARealSlowTurn() {
        val pose = GyroOrientation(); pose.calibrateStationaryBias(listOf(0.0, 0.0054, 0.0))
        val gate = SceneMotionGate()
        val still = MotionSnapshot(state = MotionState.STILL, stale = false, calibrated = true, rotationRate = 0.1554)
        var changed = false
        for (i in 0..200) {
            pose.update(1_000_000L + i * 5_000L, 0.0, 0.1554, 0.0)
            if (i % 20 == 0) { gate.update(i * 5L, still, pose.snapshot()); changed = changed || gate.viewChanged }
        }
        assertTrue(changed)
        assertEquals(0.15, 2 * acos(abs(pose.snapshot()[0])), 1e-8)
    }
    @Test fun attitudeZeroingPreservesBiasButANewCameraSessionCanClearIt() {
        val pose = GyroOrientation(); pose.calibrateStationaryBias(listOf(0.0, 0.0054, 0.0))
        pose.reset()
        for (i in 0..200) pose.update(1_000_000L + i * 5_000L, 0.0, 0.0054, 0.0)
        assertArrayEquals(doubleArrayOf(1.0, 0.0, 0.0, 0.0), pose.snapshot(), 1e-10)
        pose.reset(clearBias = true)
        for (i in 0..200) pose.update(1_000_000L + i * 5_000L, 0.0, 0.0054, 0.0)
        assertTrue(abs(pose.snapshot()[2]) > 0.001)
    }
    @Test fun invalidBiasNeverChangesTheIntegrator() {
        val pose = GyroOrientation()
        assertFalse(pose.calibrateStationaryBias(listOf(Double.NaN, 0.0, 0.0)))
        assertFalse(pose.calibrateStationaryBias(listOf(0.0, 0.2, 0.0)))
        assertFalse(pose.calibrateStationaryBias(listOf(0.0)))
        for (i in 0..200) pose.update(1_000_000L + i * 5_000L, 0.0, 0.15, 0.0)
        assertEquals(0.15, 2 * acos(abs(pose.snapshot()[0])), 1e-8)
    }
    @Test fun mismatchedCalibrationBiasDoesNotCutOffTheFirstStationaryDescription() {
        val pose=GyroOrientation()
        assertTrue(pose.calibrateStationaryBias(listOf(-0.006872,-0.037013,0.007961)))
        val gate=SceneMotionGate();val flow=SceneFlow()
        val still=MotionSnapshot(state=MotionState.STILL,stale=false,calibrated=true,rotationRate=0.0059)
        var count=0;var speakingAt:Long?=null;var ticketId:Long?=null
        for(i in 0..12_000) {
            pose.update(1_000_000L+i*5_000L,0.0,-0.0059,0.0)
            if(i%20==0) {
                val now=i*5L
                val ready=gate.update(now,still,pose.snapshot())
                assertFalse("static pose drift at $now",gate.viewChanged)
                val automatic=flow.observe(now,ready,false,gate.viewChanged)
                if(automatic) {
                    val ticket=flow.begin(now,false)!!;count++;ticketId=ticket.id
                    flow.finish(ticket.id,now);assertTrue(flow.admitSpeech(ticket.id,now));speakingAt=now
                }
                if(speakingAt!=null && now-speakingAt!!<15_000) assertTrue(flow.speaking)
                if(speakingAt!=null && now-speakingAt!!>=15_000) {flow.complete(ticketId!!);speakingAt=null}
            }
        }
        assertEquals(1,count)
    }
    @Test fun rawEvidenceStillAllowsARealVerySlowTurnToInvalidateTheView() {
        val pose=GyroOrientation();val gate=SceneMotionGate()
        val still=MotionSnapshot(state=MotionState.STILL,stale=false,calibrated=true,rotationRate=0.03)
        var changed=false
        for(i in 0..1_000) {
            pose.update(1_000_000L+i*5_000L,0.0,0.03,0.0)
            if(i%20==0) {gate.update(i*5L,still,pose.snapshot());changed=changed || gate.viewChanged}
        }
        assertTrue(changed)
    }
}
