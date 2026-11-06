package com.helios.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TaskState { IDLE, RUNNING, PAUSED, CANCELLED }
enum class FallState { IDLE, CANDIDATE, CONFIRMING, HELPING, RECOVERING }
enum class DeviceState { DISCONNECTED, CONNECTING, STREAMING, STALE, ERROR }

data class OperationalState(
    val motion: MotionSnapshot = MotionSnapshot(),
    val task: TaskState = TaskState.IDLE,
    val fall: FallState = FallState.IDLE,
    val device: DeviceState = DeviceState.DISCONNECTED,
    val sequence: Long = 0,
)

/** Independent domains; their transitions are serialized without tying task/fall to camera lifecycle. */
object OperationalStateStore {
    private val mutable = MutableStateFlow(OperationalState())
    val status = mutable.asStateFlow()

    @Synchronized fun update(
        motion: MotionSnapshot = mutable.value.motion,
        task: TaskState = mutable.value.task,
        fall: FallState = mutable.value.fall,
        device: DeviceState = mutable.value.device,
    ) {
        mutable.value = OperationalState(motion, task, fall, device, mutable.value.sequence + 1)
    }
}
