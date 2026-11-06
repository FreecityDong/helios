package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class StartupCheckPolicyTest {
    @Test fun missingCameraIsAFailedCheckNotAnEndlessPreparingSession() {
        assertNotNull(StartupCheckPolicy.failure(false,DeviceState.DISCONNECTED,false,false,true,100))
    }
    @Test fun connectedHardwareGetsABoundedInitializationWindow() {
        assertNull(StartupCheckPolicy.failure(true,DeviceState.CONNECTING,false,false,true,14_999))
        assertNotNull(StartupCheckPolicy.failure(true,DeviceState.CONNECTING,false,false,true,15_000))
    }
    @Test fun faultsAndTimedOutVoiceCannotPassWhileHealthyInitializationCan() {
        assertNotNull(StartupCheckPolicy.failure(true,DeviceState.ERROR,false,false,true,500))
        assertNotNull(StartupCheckPolicy.failure(true,DeviceState.STREAMING,true,true,true,500))
        assertNotNull(StartupCheckPolicy.failure(true,DeviceState.STREAMING,false,true,false,15_000))
        assertNull(StartupCheckPolicy.failure(true,DeviceState.STREAMING,false,true,true,500))
    }
}
