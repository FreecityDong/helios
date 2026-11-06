package com.helios.app

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SceneMotionGateTest {
    private fun still(rate: Double = 0.0) = MotionSnapshot(state = MotionState.STILL,
        calibrated = true, stale = false, rotationRate = rate)
    private fun yaw(angle: Double) = doubleArrayOf(cos(angle / 2), 0.0, sin(angle / 2), 0.0)

    @Test fun briefSmallBodySwayDoesNotCancelDescription() {
        val gate = SceneMotionGate()
        assertTrue(gate.update(0, still(), yaw(0.0)))
        for (now in 100L..3_000L step 100) {
            val angle = if (now % 400 < 200) 0.04 else -0.04
            assertTrue(gate.update(now, still(0.3), yaw(angle)))
            assertFalse(gate.viewChanged)
        }
    }
    @Test fun aSlowInPlaceTurnStillInvalidatesTheOldScene() {
        val gate = SceneMotionGate()
        gate.update(0, still(0.15), yaw(0.0))
        assertTrue(gate.update(100, still(0.15), yaw(0.05)))
        assertFalse(gate.update(200, still(0.15), yaw(0.14)))
        assertTrue(gate.viewChanged)
        assertFalse(gate.update(300, still(0.15), yaw(0.16)))
        assertFalse(gate.viewChanged)
    }
    @Test fun stillMovementStateDoesNotHideAFastTurn() {
        val gate = SceneMotionGate()
        gate.update(0, still(), yaw(0.0))
        assertFalse(gate.update(100, still(0.7), yaw(0.07)))
        assertTrue(gate.viewChanged)
    }
    @Test fun realWalkingShakingAndMissingCalibrationDoNotAllowSceneTasks() {
        val gate = SceneMotionGate()
        gate.update(0, still())
        assertFalse(gate.update(100, still().copy(state = MotionState.MOVING)))
        assertFalse(gate.update(200, still().copy(state = MotionState.SHAKING)))
        assertFalse(gate.update(300, still().copy(calibrated = false)))
        assertFalse(gate.update(400, still().copy(stale = true)))
    }
    @Test fun quaternionSignChangesAreNotViewChanges() {
        val gate = SceneMotionGate()
        gate.update(0, still(), yaw(0.0))
        assertTrue(gate.update(100, still(), doubleArrayOf(-1.0, 0.0, 0.0, 0.0)))
        assertFalse(gate.viewChanged)
    }
    @Test fun afterTurningTheNewHeadingMustBecomeQuietBeforeItCanBeDescribed() {
        val gate = SceneMotionGate()
        gate.update(0, still(), yaw(0.0))
        assertFalse(gate.update(100, still(0.2), yaw(0.2)))
        assertFalse(gate.update(200, still(), yaw(0.2)))
        assertFalse(gate.update(600, still(), yaw(0.2)))
        assertTrue(gate.update(700, still(), yaw(0.2)))
    }
    @Test fun aConfirmedTurnRearmsOneFreshAutomaticSceneAndRejectsTheOldResponse() {
        val gate = SceneMotionGate(); val flow = SceneFlow()
        gate.update(0, still(), yaw(0.0))
        flow.observe(0, true, false); flow.observe(1_500, true, false)
        val old = flow.begin(1_500, false)!!
        val ready = gate.update(1_600, still(0.2), yaw(0.2))
        flow.observe(1_600, ready, false, gate.viewChanged)
        assertFalse(flow.finish(old.id, 1_700))
        flow.observe(2_000, true, false)
        assertTrue(flow.observe(3_500, true, false))
        val next = flow.begin(3_500, false)!!
        assertNotEquals(old.id, next.id)
        flow.finish(next.id, 4_000)
        assertFalse(flow.observe(4_500, true, false))
    }
    @Test fun withoutAPoseThereIsStillBoundedRateBasedTurnDetection() {
        val gate = SceneMotionGate()
        gate.update(0, still(0.2))
        var detected = false
        for (now in 100L..1_500L step 100) {
            if (!gate.update(now, still(0.2))) detected = true
        }
        assertTrue(detected)
    }
}
