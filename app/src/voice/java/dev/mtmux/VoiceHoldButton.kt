package dev.mtmux

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

@Composable internal fun VoiceHoldButton(
    state: VoiceHoldState, enabled: Boolean, modifier: Modifier,
    onPress: () -> Long?, onMove: (Long, Boolean) -> Unit, onRelease: (Long) -> Unit,
    onCancel: (Long) -> Unit, onAccessibleClick: () -> Unit
) {
    val press by rememberUpdatedState(onPress)
    val move by rememberUpdatedState(onMove)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)
    var windowPosition by remember { mutableStateOf(Offset.Zero) }
    val cancelDistance = with(LocalDensity.current) { 72.dp.toPx() }
    val canInteract = enabled && state.phase in listOf(VoicePhase.IDLE, VoicePhase.STARTING, VoicePhase.RECORDING)
    val recording = state.phase == VoicePhase.RECORDING
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    val description = stringResource(R.string.voice_hold_to_talk)
    val hint = stringResource(when {
        state.cancelArmed -> R.string.voice_release_cancel
        state.phase == VoicePhase.STARTING -> R.string.voice_starting
        recording && state.accessibilityHold -> R.string.voice_tap_stop
        recording -> R.string.voice_release_transcribe
        else -> R.string.voice_hold_hint
    })
    val background = when {
        !canInteract -> MaterialTheme.colorScheme.surfaceVariant
        state.cancelArmed -> if (dark) Color(0xFFFF797E) else Color(0xFFC7313E)
        recording -> if (dark) Color(0xFF58D99B) else Color(0xFF18835A)
        else -> MaterialTheme.colorScheme.primary
    }
    val foreground = if (!canInteract) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.38f)
        else if (recording || state.cancelArmed) { if (dark) Color(0xFF10241D) else Color.White }
        else MaterialTheme.colorScheme.onPrimary
    Surface(shape=CircleShape,color=background,contentColor=foreground,
        modifier=modifier.size(52.dp).testTag("voice-input")
            .onGloballyPositioned { windowPosition=it.positionInWindow() }
            .focusable(canInteract)
            .semantics(mergeDescendants=true) {
                role=Role.Button
                contentDescription=description
                stateDescription=hint
                if (!canInteract) disabled()
                onClick {
                    if (canInteract) { onAccessibleClick();true } else false
                }
            }.pointerInput(Unit) {
                awaitEachGesture {
                    val down=awaitFirstDown()
                    val id=press() ?: return@awaitEachGesture
                    down.consume()
                    // Use window coordinates: hiding the IME can move the button while held.
                    val origin=down.position+windowPosition
                    var lifted=false
                    try {
                        while (true) {
                            val event=awaitPointerEvent()
                            val change=event.changes.firstOrNull {it.id==down.id} ?: break
                            if (change.isConsumed || event.changes.any {it.id!=down.id && it.pressed}) break
                            move(id,origin.y-(change.position+windowPosition).y >= cancelDistance)
                            if (!change.pressed) {
                                change.consume();lifted=true;release(id);break
                            }
                            change.consume()
                        }
                    } finally {
                        if (!lifted) cancel(id)
                    }
                }
            }) {
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
            Icon(painterResource(R.drawable.ic_voice_input),contentDescription=null,modifier=Modifier.size(30.dp))
        }
    }
}

private object VoiceFeedbackPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,
        popupContentSize: IntSize) = IntOffset(
        ((windowSize.width-popupContentSize.width)/2).coerceAtLeast(0),
        ((windowSize.height-popupContentSize.height)/2).coerceAtLeast(0))
}

/** This nonfocusable overlay cannot steal the press or resize the terminal. */
@Composable internal fun VoiceRecordingFeedback(state: VoiceHoldState) {
    val phase=state.phase
    val startedAt=state.startedAt
    val remaining by produceState(VoiceHoldState.LIMIT_MILLIS,phase,startedAt) {
        if (phase==VoicePhase.RECORDING) {
            do {
                value=state.remainingMillis(SystemClock.elapsedRealtime())
                if (value==0L) break
                delay(100)
            } while (true)
        }
    }
    val seconds=((remaining+999)/1000).toInt()
    val scheme=MaterialTheme.colorScheme
    val dark=scheme.surface.luminance()<.5f
    val green=if(dark) Color(0xFF58D99B) else Color(0xFF18835A)
    val orange=if(dark) Color(0xFFFFBE67) else Color(0xFFC56A08)
    val red=if(dark) Color(0xFFFF797E) else Color(0xFFC7313E)
    val color=when {
        state.cancelArmed -> red
        phase==VoicePhase.STARTING -> scheme.onSurfaceVariant
        remaining>10_000 -> green
        remaining>5_000 -> lerp(green,orange,(10_000-remaining)/5_000f)
        else -> lerp(orange,red,(5_000-remaining)/5_000f)
    }
    val title=stringResource(when {
        state.cancelArmed -> R.string.voice_release_cancel
        phase==VoicePhase.STARTING -> R.string.voice_starting
        state.accessibilityHold -> R.string.voice_tap_stop
        else -> R.string.voice_release_transcribe
    })
    Popup(popupPositionProvider=VoiceFeedbackPosition,
        properties=PopupProperties(focusable=false,dismissOnBackPress=false,dismissOnClickOutside=false)) {
        Column(Modifier.width(260.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.size(196.dp).testTag(if(phase==VoicePhase.RECORDING) "voice-recording" else "voice-preparing"),
                contentAlignment=Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Brush.radialGradient(listOf(color.copy(alpha=.32f),color.copy(alpha=.14f),Color.Transparent)))
                    drawCircle(scheme.surface,radius=64.dp.toPx())
                    drawCircle(color.copy(alpha=.09f),radius=64.dp.toPx())
                    if (phase==VoicePhase.RECORDING) {
                        val diameter=140.dp.toPx()
                        drawArc(color,-90f,360f*remaining/VoiceHoldState.LIMIT_MILLIS,false,
                            Offset((size.width-diameter)/2,(size.height-diameter)/2),Size(diameter,diameter),
                            style=Stroke(3.dp.toPx()))
                    }
                }
                if (phase==VoicePhase.STARTING) {
                    CircularProgressIndicator(Modifier.size(140.dp),color=color,strokeWidth=3.dp)
                    Text(stringResource(R.string.voice_preparing_short),color=scheme.onSurface,style=MaterialTheme.typography.titleMedium)
                } else Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.voice_remaining_label),color=scheme.onSurfaceVariant,style=MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.voice_seconds,seconds),color=scheme.onSurface,
                        fontSize=36.sp,fontWeight=FontWeight.Medium,lineHeight=46.sp)
                }
            }
            Surface(shape=MaterialTheme.shapes.medium,color=scheme.surface.copy(alpha=.97f)) {
                Column(Modifier.padding(horizontal=18.dp,vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(title,color=color,style=MaterialTheme.typography.titleSmall,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                    if (!state.accessibilityHold) Text(stringResource(if(state.cancelArmed) R.string.voice_move_back else R.string.voice_slide_cancel),
                        color=scheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
