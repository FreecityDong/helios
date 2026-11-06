package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class MotionTrackerTest {
    private fun sample(tracker: MotionTracker, at: Long, acceleration: DoubleArray, gyro: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0)) {
        tracker.addAcceleration(at, acceleration)
        tracker.addRotation(at, gyro)
        if (at % 100L == 0L) tracker.snapshot(at)
    }

    @Test fun calibrationAndStableStill() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        val result = tracker.snapshot(4_000)
        assertTrue(result.calibrated)
        assertEquals(MotionState.STILL, result.state)
        assertFalse(result.stale)
        assertEquals(9.81, result.gravity[2], 0.01)
    }

    @Test fun walkingShakeAndStaleAreDistinct() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        for (t in 4_010L..6_000L step 10L) sample(tracker, t,
            doubleArrayOf(if ((t / 100) % 2L == 0L) 1.2 else -1.2, 0.0, 9.81))
        assertEquals(MotionState.MOVING, tracker.snapshot(6_000).state)
        for (t in 6_010L..7_000L step 10L) sample(tracker, t,
            doubleArrayOf(if ((t / 100) % 2L == 0L) 6.5 else -6.5, 0.0, 9.81),
            doubleArrayOf(1.2, 0.0, 0.0))
        assertEquals(MotionState.SHAKING, tracker.snapshot(7_000).state)
        val stale = tracker.snapshot(8_200)
        assertEquals(MotionState.UNKNOWN, stale.state)
        assertTrue(stale.stale)
    }

    @Test fun movingCannotCalibrateAsWornStill() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..2_000L step 10L) sample(tracker, t,
            doubleArrayOf(if ((t / 100) % 2L == 0L) 4.0 else -4.0, 0.0, 9.81))
        assertFalse(tracker.snapshot(2_000).calibrated)
        assertEquals(MotionState.UNKNOWN, tracker.snapshot(2_000).state)
    }

    @Test fun briefLargeOrientationChangeKeepsCalibration() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        for (t in 4_010L..6_000L step 10L) sample(tracker, t, doubleArrayOf(9.81, 0.0, 0.0))
        val result = tracker.snapshot(6_000)
        assertTrue(result.calibrated)
        assertFalse(result.stale)
        assertEquals(MotionState.MOVING, result.state)
        for (t in 6_010L..9_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        assertTrue(tracker.snapshot(9_000).calibrated)
    }

    @Test fun modestSustainedPostureChangeDoesNotRequireRecalibration() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        for (t in 4_010L..13_000L step 10L) sample(tracker, t, doubleArrayOf(3.0, 0.0, 9.34))
        assertTrue(tracker.snapshot(13_000).calibrated)
        assertEquals(MotionState.STILL,tracker.snapshot(13_000).state)
    }

    @Test fun sustainedLargePostureChangeKeepsTheOriginalWornCalibration() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker,t,doubleArrayOf(0.0,0.0,9.81))
        val originalGravity=tracker.snapshot(4_000).gravity
        for (t in 4_010L..34_000L step 10L) {
            sample(tracker,t,doubleArrayOf(9.81,0.0,0.0))
            if(t%100==0L) assertTrue(tracker.snapshot(t).calibrated)
        }
        val bent=tracker.snapshot(34_000)
        assertEquals(MotionState.STILL,bent.state)
        assertTrue(bent.orientationDelta>6.0)
        assertEquals(originalGravity,bent.gravity)
        assertNull(RuntimeHealthPolicy.failure(DeviceState.STREAMING,listOf(10,10,5,5),false,bent.calibrated))
    }

    @Test fun slowlyBendingHoldingAndStandingUpNeverInvalidatesCalibration() {
        val tracker=MotionTracker();tracker.requestCalibration()
        for(t in 100L..4_000L step 10L)sample(tracker,t,doubleArrayOf(0.0,0.0,9.81))
        val maxAngle=Math.toRadians(70.0)
        for(t in 4_010L..62_000L step 10L) {
            val angle=when {
                t<16_000 -> maxAngle*(t-4_000)/12_000.0
                t<46_000 -> maxAngle
                t<58_000 -> maxAngle*(58_000-t)/12_000.0
                else -> 0.0
            }
            val rate=when {
                t<16_000 -> maxAngle/12
                t<46_000 -> 0.0
                t<58_000 -> -maxAngle/12
                else -> 0.0
            }
            sample(tracker,t,doubleArrayOf(9.81*kotlin.math.sin(angle),0.0,9.81*kotlin.math.cos(angle)),doubleArrayOf(0.0,rate,0.0))
            if(t%100==0L)assertTrue("calibration lost at $t",tracker.snapshot(t).calibrated)
        }
        assertEquals(MotionState.STILL,tracker.snapshot(62_000).state)
        assertEquals(9.81,tracker.snapshot(62_000).gravity[2],.001)
    }

    @Test fun explicitRecalibrationStillClearsTheOldReference() {
        val tracker=MotionTracker();tracker.requestCalibration()
        for(t in 100L..4_000L step 10L)sample(tracker,t,doubleArrayOf(0.0,0.0,9.81))
        assertTrue(tracker.snapshot(4_000).calibrated)
        tracker.requestCalibration()
        assertFalse(tracker.snapshot(4_000).calibrated)
        for(t in 4_010L..8_000L step 10L)sample(tracker,t,doubleArrayOf(9.81,0.0,0.0))
        assertTrue(tracker.snapshot(8_000).calibrated)
        assertEquals(9.81,tracker.snapshot(8_000).gravity[0],.001)
    }

    @Test fun speechDoesNotChangeMotionCalibration() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        for (t in 4_010L..6_000L step 10L) sample(tracker, t,
            doubleArrayOf(if ((t / 100) % 2L == 0L) 1.2 else -1.2, 0.0, 9.81))
        assertEquals(MotionState.MOVING, tracker.snapshot(6_000).state)
        // A user slows down or changes pitch during a two-second spoken warning.
        for (t in 6_010L..8_500L step 10L) sample(tracker, t, doubleArrayOf(2.5, 0.0, 9.49))
        val result = tracker.snapshot(8_500)
        assertTrue(result.calibrated)
        assertFalse(result.stale)
    }

    @Test fun rotatingSensorCannotCalibrate() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..2_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81), doubleArrayOf(0.0, 0.0, 0.6))
        assertFalse(tracker.snapshot(2_000).calibrated)
    }

    @Test fun coherentImageMotionCanPromoteImuStillButNeverRecoverStaleImu() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        assertEquals(MotionState.STILL, tracker.snapshot(4_000).state)
        for (t in 4_010L..6_000L step 10L) {
            sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
            if (t % 250L == 0L) tracker.addVisualEvidence(VisualMotionEvidence(
                VisualMotionKind.GLOBAL_SHIFT, t, 16, 15, 2.0, 2, 0))
        }
        assertEquals(MotionState.MOVING, tracker.snapshot(6_000).state)
        assertEquals("imu+rgb", tracker.snapshot(6_000).source)
        assertEquals(MotionState.UNKNOWN, tracker.snapshot(7_100).state)
        for (t in 7_110L..10_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        assertEquals(MotionState.STILL, tracker.snapshot(10_000).state)
    }

    @Test fun alternatingImageMotionDoesNotPromoteStill() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        for (t in 4_010L..6_000L step 10L) {
            sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
            if (t % 250L == 0L) {
                val dx = if ((t / 250L) % 2L == 0L) 2 else -2
                tracker.addVisualEvidence(VisualMotionEvidence(VisualMotionKind.GLOBAL_SHIFT,
                    t, 16, 15, 2.0, dx, 0))
            }
        }
        assertEquals(MotionState.STILL, tracker.snapshot(6_000).state)
    }

    @Test fun fastDeliberateTurnIsMovingNotShaking() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        for (t in 4_010L..6_000L step 10L) sample(tracker, t,
            doubleArrayOf(0.2, 0.0, 9.81), doubleArrayOf(0.0, 0.0, 2.5))
        val result = tracker.snapshot(6_000)
        assertEquals(MotionState.MOVING, result.state)
        assertEquals("none", result.shakeReason)
    }

    @Test fun fastTurnWithLargeAccelerationIsStillNotShaking() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        for (t in 4_010L..6_000L step 10L) sample(tracker, t,
            doubleArrayOf(if ((t / 100) % 2L == 0L) 7.0 else -7.0, 0.0, 9.81),
            doubleArrayOf(0.0, 0.0, 2.5))
        val result = tracker.snapshot(6_000)
        assertEquals(MotionState.MOVING, result.state)
        assertEquals(0.0, result.tiltRotationRate, 0.001)
        assertEquals("none", result.shakeReason)
    }

    @Test fun turnsUseCalibratedGravityRatherThanFixedCameraAxis() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, -9.81, 0.0))
        for (t in 4_010L..6_000L step 10L) sample(tracker, t,
            doubleArrayOf(if ((t / 100) % 2L == 0L) 7.0 else -7.0, -9.81, 0.0),
            doubleArrayOf(0.0, 2.5, 0.0))
        assertEquals(MotionState.MOVING, tracker.snapshot(6_000).state)
        assertEquals("none", tracker.snapshot(6_000).shakeReason)
    }

    @Test fun strongTranslationWithoutRotationIsNotFallCue() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        for (t in 4_010L..6_000L step 10L) sample(tracker, t,
            doubleArrayOf(if ((t / 100) % 2L == 0L) 5.0 else -5.0, 0.0, 9.81))
        assertEquals(MotionState.MOVING, tracker.snapshot(6_000).state)
    }

    @Test fun deliberateBendWithoutImpactIsMovingNotShaking() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        for (t in 4_010L..6_000L step 10L) sample(tracker, t,
            doubleArrayOf(0.2, 0.0, 9.81), doubleArrayOf(1.6, 0.0, 0.0))
        val result = tracker.snapshot(6_000)
        assertEquals(MotionState.MOVING, result.state)
        assertEquals("none", result.shakeReason)
    }

    @Test fun energeticWalkingLikeMotionStaysMoving() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        for (t in 4_010L..6_000L step 10L) sample(tracker, t,
            doubleArrayOf(if ((t / 100) % 2L == 0L) 5.5 else -5.5, 0.0, 9.81),
            doubleArrayOf(0.0, 0.0, 0.9))
        assertEquals(MotionState.MOVING, tracker.snapshot(6_000).state)
    }

    @Test fun briefImpactWithRotationIsCandidateOnly() {
        val tracker = MotionTracker()
        tracker.requestCalibration()
        for (t in 100L..4_000L step 10L) sample(tracker, t, doubleArrayOf(0.0, 0.0, 9.81))
        for (t in 4_010L..6_000L step 10L) sample(tracker, t,
            doubleArrayOf(if (t in 5_000L..5_090L) 9.0 else 0.1, 0.0, 9.81),
            doubleArrayOf(0.9, 0.0, 0.0))
        val result = tracker.snapshot(6_000)
        assertEquals(MotionState.SHAKING, result.state)
        assertEquals("impact_with_rotation", result.shakeReason)
    }
    @Test fun walkingThenStoppingAtADifferentPitchReturnsToStill() {
        val tracker=MotionTracker();tracker.requestCalibration()
        for(t in 100L..4_000L step 10L) sample(tracker,t,doubleArrayOf(0.0,0.0,9.81))
        for(t in 4_010L..6_000L step 10L) sample(tracker,t,
            doubleArrayOf(if((t/100)%2L==0L) 2.5 else -.5,0.0,9.7),doubleArrayOf(.4,0.0,0.0))
        assertEquals(MotionState.MOVING,tracker.snapshot(6_000).state)
        for(t in 6_010L..12_000L step 10L) sample(tracker,t,doubleArrayOf(1.8,0.0,9.643),doubleArrayOf(.01,0.0,0.0))
        val stopped=tracker.snapshot(12_000)
        assertTrue(stopped.calibrated);assertEquals(MotionState.STILL,stopped.state)
        assertTrue(stopped.dynamicAcceleration>1.5) // keep original gravity-relative safety diagnostic
    }

    @Test fun breathingAndSmallBodySwayAtChangedPitchRemainStill() {
        val tracker=MotionTracker();tracker.requestCalibration()
        for(t in 100L..4_000L step 10L) sample(tracker,t,doubleArrayOf(0.0,0.0,9.81))
        for(t in 4_010L..64_000L step 10L) {
            val breathing=kotlin.math.sin(t/4_000.0*2*Math.PI)
            sample(tracker,t,doubleArrayOf(1.8+.18*breathing,.05*breathing,9.643+.08*breathing),doubleArrayOf(.02,0.0,0.0))
            if(t>=10_000L && t%1_000L==0L) {
                val state=tracker.snapshot(t)
                assertEquals(MotionState.STILL,state.state)
                assertTrue(state.movementAcceleration<.4)
                assertTrue(state.calibrated)
            }
        }
    }

}
