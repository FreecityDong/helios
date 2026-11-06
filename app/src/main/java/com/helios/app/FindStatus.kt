package com.helios.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object FindStatusStore {
    private val mutable=MutableStateFlow("找物未开始，可唤醒后说找手机或找钥匙")
    val status=mutable.asStateFlow()
    fun update(message: String) { mutable.value=message }
}
