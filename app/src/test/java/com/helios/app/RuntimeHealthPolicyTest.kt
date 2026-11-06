package com.helios.app

import org.junit.Assert.*
import org.junit.Test

class RuntimeHealthPolicyTest {
    private fun reason(device:DeviceState=DeviceState.STREAMING,ages:List<Long> = listOf(20,20,5,5),failed:Boolean=false,calibrated:Boolean=true,mic:Boolean=true)=
        RuntimeHealthPolicy.failure(device,ages,failed,calibrated,mic)
    @Test fun disconnectAndDeviceFailuresRequireExitImmediately() {
        assertNotNull(reason(DeviceState.DISCONNECTED))
        assertNotNull(reason(DeviceState.ERROR))
        assertNotNull(reason(failed=true))
        assertNotNull(reason(calibrated=false))
        assertNotNull(reason(mic=false))
    }
    @Test fun ordinaryArrivalJitterDoesNotStopTheSessionButEachStalledStreamDoes() {
        assertNull(reason(ages=listOf(1999,1500,100,100)))
        repeat(4) {i->assertNotNull(reason(ages=List(4) {if(it==i) 2000L else 20L}))}
        assertNull(reason())
    }
    @Test fun missingAndImpossibleSamplesAreNotHealthy() {
        assertNotNull(reason(ages=listOf(20,20)))
        assertNotNull(reason(ages=listOf(20,20,-1,20)))
        assertNotNull(reason(ages=listOf(Long.MAX_VALUE,20,20,20)))
    }
}
