package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class StartupFlowTest {
    @Test fun voiceModelsAloneCannotMarkAssistanceReady() {
        val flow=StartupFlow();flow.start()
        assertEquals(StartupPhase.INITIALIZING,flow.observe(false,true,false,true).phase)
        assertEquals(StartupPhase.WAITING_DEVICE,flow.observe(true,false,false,true).phase)
        assertEquals(StartupPhase.CALIBRATING,flow.observe(true,true,true,true).phase)
    }
    @Test fun calibrationIsRequestedOnlyAfterGuidanceAndNotResetByPolling() {
        val flow=StartupFlow();flow.start()
        val token=flow.beginCalibrationPrompt()!!
        assertNull(flow.beginCalibrationPrompt())
        assertFalse(flow.calibrationRequested)
        assertTrue(flow.acceptCalibrationPrompt(token))
        repeat(100) {assertEquals(StartupPhase.CALIBRATING,flow.observe(true,true,false,true).phase);assertNull(flow.beginCalibrationPrompt())}
        assertEquals(StartupPhase.CALIBRATING,flow.observe(true,true,true,false).phase)
        assertEquals(StartupPhase.READY,flow.observe(true,true,true,true).phase)
        assertEquals(StartupPhase.READY,flow.observe(true,true,true,false).phase)
    }
    @Test fun eachNewStartupRejectsOldCalibrationAndPromptCallbacks() {
        val flow=StartupFlow();flow.start();val old=flow.beginCalibrationPrompt()!!
        flow.stop();flow.start()
        assertFalse(flow.acceptCalibrationPrompt(old))
        assertEquals(StartupPhase.CALIBRATING,flow.observe(true,true,true,true).phase)
        assertTrue(flow.acceptCalibrationPrompt(flow.beginCalibrationPrompt()!!))
        assertEquals(StartupPhase.READY,flow.observe(true,true,true,true).phase)
    }
    @Test fun disconnectInvalidatesTheOutstandingSpokenGuidance() {
        val flow=StartupFlow();flow.start();val token=flow.beginCalibrationPrompt()!!
        flow.observe(true,false,false,false)
        assertFalse(flow.acceptCalibrationPrompt(token))
        assertNotNull(flow.beginCalibrationPrompt())
    }
    @Test fun lostCalibrationRequiresANewStableWindowWithoutPromptLoops() {
        val flow=StartupFlow();flow.start();flow.acceptCalibrationPrompt(flow.beginCalibrationPrompt()!!)
        assertEquals(StartupPhase.READY,flow.observe(true,true,true,true).phase)
        assertEquals(StartupPhase.CALIBRATING,flow.observe(true,true,false,false).phase)
        flow.acceptCalibrationPrompt(flow.beginCalibrationPrompt()!!)
        repeat(5) {flow.observe(true,true,false,false);assertNull(flow.beginCalibrationPrompt())}
        assertEquals(StartupPhase.CALIBRATING,flow.observe(true,true,true,false).phase)
        assertEquals(StartupPhase.READY,flow.observe(true,true,true,true).phase)
    }
    @Test fun manualRecalibrationAndStopInvalidateOldTokens() {
        val flow=StartupFlow();flow.start();val token=flow.beginCalibrationPrompt()!!
        flow.retryCalibration();assertFalse(flow.acceptCalibrationPrompt(token))
        val retry=flow.beginCalibrationPrompt()!!;flow.stop()
        assertFalse(flow.acceptCalibrationPrompt(retry))
        assertEquals(StartupPhase.OFF,flow.observe(true,true,true,true).phase)
    }
    @Test fun readinessFeedbackCannotActivateAfterDisconnectOrStop() {
        val flow=StartupFlow();flow.start();flow.acceptCalibrationPrompt(flow.beginCalibrationPrompt()!!)
        flow.observe(true,true,true,true)
        val token=flow.activationToken()!!;assertTrue(flow.mayActivate(token))
        flow.observe(true,false,false,false);assertFalse(flow.mayActivate(token))
        flow.acceptCalibrationPrompt(flow.beginCalibrationPrompt()!!);flow.observe(true,true,true,true)
        val next=flow.activationToken()!!;assertTrue(flow.mayActivate(next))
        flow.stop();assertFalse(flow.mayActivate(next))
    }
    @Test fun manualCalibrationInvalidatesPendingReadinessFeedback() {
        val flow=StartupFlow();flow.start();flow.acceptCalibrationPrompt(flow.beginCalibrationPrompt()!!)
        flow.observe(true,true,true,true);val token=flow.activationToken()!!
        flow.retryCalibration();assertNull(flow.activationToken());assertFalse(flow.mayActivate(token))
    }
    @Test fun failedDeviceCheckIsLatchedUntilANewExplicitStartup() {
        val flow=StartupFlow();flow.start()
        assertEquals(StartupPhase.ERROR,flow.observe(true,true,true,true,error="目标检测失败").phase)
        repeat(20) {assertEquals(StartupPhase.ERROR,flow.observe(true,true,true,true).phase)}
        assertNull(flow.activationToken());assertNull(flow.beginCalibrationPrompt())
        flow.start()
        assertEquals(StartupPhase.CALIBRATING,flow.observe(true,true,true,true).phase)
    }
    @Test fun aFailedCheckInvalidatesAnAlreadyPreparedActivation() {
        val flow=StartupFlow();flow.start();flow.acceptCalibrationPrompt(flow.beginCalibrationPrompt()!!)
        flow.observe(true,true,true,true);val token=flow.activationToken()!!
        flow.observe(true,true,true,true,error="设备初始化异常")
        assertFalse(flow.mayActivate(token));assertNull(flow.activationToken())
    }
    @Test fun voiceReadinessLossAlsoInvalidatesTheActivationCallback() {
        val flow=StartupFlow();flow.start();flow.acceptCalibrationPrompt(flow.beginCalibrationPrompt()!!)
        flow.observe(true,true,true,true);val token=flow.activationToken()!!
        assertEquals(StartupPhase.INITIALIZING,flow.observe(false,true,true,true).phase)
        assertFalse(flow.mayActivate(token))
    }
    @Test fun remindersStartAfterGuidanceAndRepeatWithoutRestartingCalibration() {
        val flow=StartupFlow();flow.start();val token=flow.beginCalibrationPrompt()!!
        assertFalse(flow.takeReminder(100_000,false))
        flow.acceptCalibrationPrompt(token,1_000)
        assertFalse(flow.takeReminder(10_999,false))
        assertFalse(flow.takeReminder(11_000,true))
        assertTrue(flow.takeReminder(11_000,false))
        assertFalse(flow.takeReminder(25_999,false))
        assertTrue(flow.takeReminder(26_000,false))
        assertNull(flow.beginCalibrationPrompt())
        flow.observe(true,true,true,true)
        assertFalse(flow.takeReminder(100_000,false))
    }
    @Test fun stoppedAndFailedStartupCannotContinueReminderLoops() {
        val flow=StartupFlow();flow.start();flow.acceptCalibrationPrompt(flow.beginCalibrationPrompt()!!,0)
        flow.stop();assertFalse(flow.takeReminder(20_000,false))
        flow.start();flow.acceptCalibrationPrompt(flow.beginCalibrationPrompt()!!,0)
        flow.observe(true,true,false,true,error="检查失败")
        assertFalse(flow.takeReminder(20_000,false))
    }
}
