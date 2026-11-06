package com.helios.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.withTimeoutOrNull

/** Short taps and cancelled gestures never perform a session action. */
@Composable fun HoldSessionButton(label:String,enabled:Boolean,caption:String="",onConfirm:()->Unit) {
    val action by rememberUpdatedState(onConfirm)
    val haptic=LocalHapticFeedback.current
    var pressed by remember(label,enabled) {mutableStateOf(false)}
    BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        val diameter=minOf(maxWidth-48.dp,300.dp)
        Box(Modifier.size(diameter).clip(CircleShape)
            .background(if(!enabled) Color(0xFF6F7774) else if(pressed) Color(0xFF123F32) else Color(0xFF205C4C))
            .semantics {
                role=Role.Button
                contentDescription="$label，长按半秒确认"
                if(enabled) onLongClick(label="确认$label") {action();true} else disabled()
            }
            .pointerInput(label,enabled) {
                if(enabled) detectTapGestures(onPress={
                    pressed=true
                    try {
                        val completed=withTimeoutOrNull(500L) {tryAwaitRelease();false} ?: true
                        if(completed) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            action()
                            tryAwaitRelease()
                        }
                    } finally {pressed=false}
                })
            },contentAlignment=Alignment.Center) {
            Column(Modifier.fillMaxWidth(0.82f),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(label,color=Color.White,style=MaterialTheme.typography.headlineMedium)
                if(caption.isNotBlank()) Text(caption,color=Color.White,style=MaterialTheme.typography.bodyLarge,
                    textAlign=TextAlign.Center,maxLines=4,overflow=TextOverflow.Ellipsis)
                Text(if(enabled) "长按0.5秒确认" else "正在处理",color=Color.White,style=MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
