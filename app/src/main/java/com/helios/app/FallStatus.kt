package com.helios.app
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FallStatus(val state: FallState=FallState.IDLE,val monitoring: String="未开始",
    val responseSeconds: Int=0,val message: String="",val episode: Long=0)
object FallStatusStore {
    private val mutable=MutableStateFlow(FallStatus())
    val status=mutable.asStateFlow()
    fun update(value: FallStatus) {mutable.value=value}
}
