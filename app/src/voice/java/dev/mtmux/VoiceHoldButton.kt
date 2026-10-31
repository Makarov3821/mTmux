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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration

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
        state.cancelArmed -> StatusColors.attention()
        else -> MaterialTheme.colorScheme.primary
    }
    val foreground = if (!canInteract) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.38f)
        else if (state.cancelArmed) { if (dark) Color(0xFF2A0E16) else Color.White }
        else MaterialTheme.colorScheme.onPrimary
    Surface(shape=CircleShape,color=background,contentColor=foreground,shadowElevation=if(canInteract) 6.dp else 0.dp,
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

private object FullWindowPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,
        popupContentSize: IntSize) = IntOffset(0,(windowSize.height-popupContentSize.height).coerceAtLeast(0))
}

/** Semantic voice colors: accent while talking, amber/red near the limit, red to cancel. */
@Composable internal fun voiceTint(armed: Boolean, phase: VoicePhase, remaining: Long): Color {
    val accent=MaterialTheme.colorScheme.primary
    val warn=StatusColors.running(); val danger=StatusColors.attention()
    return when {
        armed -> danger
        phase==VoicePhase.STARTING -> MaterialTheme.colorScheme.surfaceVariant
        remaining>10_000 -> accent
        remaining>5_000 -> lerp(accent,warn,((10_000-remaining)/2_000f).coerceIn(0f,1f))
        else -> lerp(warn,danger,((5_000-remaining)/5_000f).coerceIn(0f,1f))
    }
}

/**
 * Hold-to-talk overlay in the lower part of the screen (WeChat-like): shade, level bubble and a
 * raised press arc. Not focusable and not touchable, so the original press keeps its pointer
 * stream and the terminal never resizes.
 */
@Composable internal fun VoiceRecordingFeedback(state: VoiceHoldState, level: () -> Float = {0f}, visible: Boolean = true) {
    // Enter: shade fades in, arc rises, bubble settles. Exit keeps the last look while it fades out.
    val shown=remember {MutableTransitionState(false)}
    shown.targetState=visible
    if (!shown.currentState && !shown.targetState) return
    val transition=rememberTransition(shown,label="voice-overlay")
    val progress by transition.animateFloat(transitionSpec={
        if (false isTransitioningTo true) tween(220,easing=FastOutSlowInEasing) else tween(160,easing=LinearOutSlowInEasing)
    },label="voice-progress") { if (it) 1f else 0f }
    val frozenArmed=remember {mutableStateOf(false)}
    val frozenPhase=remember {mutableStateOf(VoicePhase.STARTING)}
    if (visible) {frozenArmed.value=state.cancelArmed;frozenPhase.value=state.phase}
    val armed=frozenArmed.value
    val phase=frozenPhase.value
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
    val bars=remember {mutableStateListOf<Float>().apply {repeat(18) {add(.08f)}}}
    LaunchedEffect(phase) {
        while (phase==VoicePhase.RECORDING) {
            bars.removeAt(0); bars.add((level()*2.4f).coerceIn(.08f,1f))
            delay(70)
        }
    }
    val seconds=((remaining+999)/1000).toInt()
    val elapsed=((VoiceHoldState.LIMIT_MILLIS-remaining)/1000).toInt()
    val scheme=MaterialTheme.colorScheme
    val tint=voiceTint(armed,phase,remaining)
    val onTint=if(phase==VoicePhase.STARTING) scheme.onSurface else if (tint.luminance()>.45f) Color(0xFF121420) else Color.White
    val bubbleColor by animateColorAsState(tint,tween(180),label="voice-bubble")
    val arcLift by animateDpAsState(if(armed) 0.dp else 18.dp,tween(160),label="voice-arc")
    val title=stringResource(when {
        armed -> R.string.voice_release_cancel
        phase==VoicePhase.STARTING -> R.string.voice_starting
        state.accessibilityHold -> R.string.voice_tap_stop
        else -> R.string.voice_release_transcribe
    })
    val height=(LocalConfiguration.current.screenHeightDp*.62f).dp
    val shade=scheme.background
    Popup(popupPositionProvider=FullWindowPosition,
        properties=PopupProperties(flags=android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            dismissOnBackPress=false,dismissOnClickOutside=false,usePlatformDefaultWidth=false)) {
        val density=LocalDensity.current
        Box(Modifier.fillMaxWidth().height(height).graphicsLayer {alpha=progress}.background(Brush.verticalGradient(0f to Color.Transparent,.24f to shade.copy(alpha=.92f),.42f to shade.copy(alpha=.98f),1f to shade.copy(alpha=.98f)))
            .testTag(if(!visible) "voice-exiting" else if(phase==VoicePhase.RECORDING) "voice-recording" else "voice-preparing")) {
            // Bubble with live level and remaining time.
            Column(Modifier.align(Alignment.TopCenter).padding(top=height*.28f).graphicsLayer {
                val k=.85f+.15f*progress;scaleX=k;scaleY=k;translationY=(1f-progress)*with(density) {40.dp.toPx()};transformOrigin=TransformOrigin(.5f,1f)
            },horizontalAlignment=Alignment.CenterHorizontally) {
                Surface(shape=RoundedCornerShape(22.dp),color=bubbleColor,contentColor=onTint,shadowElevation=8.dp,modifier=Modifier.widthIn(min=160.dp)) {
                    Column(Modifier.padding(horizontal=20.dp,vertical=14.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        when {
                            phase==VoicePhase.STARTING -> Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp); Text(stringResource(R.string.voice_preparing_short),style=MaterialTheme.typography.titleSmall)
                            }
                            else -> {
                                if (!armed && remaining<=10_000) Text("$seconds",fontSize=30.sp,fontWeight=FontWeight.Bold,lineHeight=34.sp)
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    if (armed) AppIcon(UiSymbol.CLOSE,modifier=Modifier.size(22.dp),tint=onTint)
                                    Canvas(Modifier.width(150.dp).height(34.dp)) {
                                        val w=size.width/bars.size
                                        bars.forEachIndexed { i,v ->
                                            val h=(size.height*v).coerceAtLeast(3.dp.toPx())
                                            drawRoundRect(onTint.copy(alpha=if(armed) .55f else 1f),Offset(i*w+w*.3f,(size.height-h)/2),Size(w*.4f,h),
                                                androidx.compose.ui.geometry.CornerRadius(w*.2f))
                                        }
                                    }
                                }
                                Text(if(armed) stringResource(R.string.voice_discard_hint,"%d:%02d".format(elapsed/60,elapsed%60))
                                    else if (remaining<=10_000) stringResource(R.string.voice_auto_stop)
                                    else stringResource(R.string.voice_bubble_time,"%d:%02d".format(elapsed/60,elapsed%60),seconds),
                                    style=MaterialTheme.typography.labelMedium,color=onTint.copy(alpha=.78f),modifier=Modifier.padding(top=4.dp))
                            }
                        }
                    }
                }
                // Bubble tail pointing at the press area.
                Canvas(Modifier.size(16.dp,8.dp)) {
                    drawPath(androidx.compose.ui.graphics.Path().apply {moveTo(0f,0f);lineTo(size.width,0f);lineTo(size.width/2,size.height);close()},bubbleColor)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom=150.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                if (!state.accessibilityHold && !armed) Text(stringResource(R.string.voice_slide_cancel_short),color=scheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                Text(title,color=if(armed) tint else scheme.onSurface,style=MaterialTheme.typography.titleSmall,
                    modifier=Modifier.padding(top=6.dp).semantics {liveRegion=LiveRegionMode.Polite})
            }
            // Raised press arc; sinks and dims while cancel is armed.
            val arcTop=if(armed) scheme.surface else lerp(scheme.surfaceVariant,tint,.18f)
            Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(130.dp+arcLift).graphicsLayer {translationY=(1f-progress)*size.height}) {
                val w=size.width*1.6f
                drawOval(Brush.verticalGradient(listOf(arcTop,scheme.surface),startY=0f,endY=size.height),Offset((size.width-w)/2,0f),Size(w,size.height*2.4f))
                drawOval(if(armed) tint.copy(alpha=.35f) else tint.copy(alpha=.30f),Offset((size.width-w)/2,0f),Size(w,size.height*2.4f),style=Stroke(1.5.dp.toPx()))
            }
            Icon(painterResource(R.drawable.ic_voice_input),null,Modifier.align(Alignment.BottomCenter).padding(bottom=44.dp).size(30.dp)
                .graphicsLayer {translationY=(1f-progress)*with(density) {150.dp.toPx()}},
                tint=if(armed) scheme.onSurfaceVariant else tint.takeIf {phase==VoicePhase.RECORDING} ?: scheme.onSurfaceVariant)
        }
    }
}

/** Short self-dismissing hint above the voice button for recoverable errors. */
@Composable internal fun VoiceErrorHint(text: String, onDone: () -> Unit) {
    LaunchedEffect(text) { delay(2800); onDone() }
    Popup(popupPositionProvider=FullWindowPosition,properties=PopupProperties(focusable=false,dismissOnBackPress=true,dismissOnClickOutside=true),onDismissRequest=onDone) {
        Box(Modifier.fillMaxWidth().padding(start=40.dp,end=40.dp,bottom=150.dp),contentAlignment=Alignment.Center) {
            FloatingCard(shape=RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(horizontal=14.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    AppIcon(UiSymbol.MIC,modifier=Modifier.size(18.dp),tint=StatusColors.running())
                    Text(text,Modifier.testTag("voice-error"),style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
