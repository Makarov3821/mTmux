package dev.mtmux

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Material nested scrolling owns the threshold and cancellation; only the indicator is custom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun HomePullRefresh(refreshing: Boolean, enabled: Boolean, onRefresh: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val state = rememberPullToRefreshState()
    val label = stringResource(when {
        refreshing -> R.string.home_pull_refreshing
        state.distanceFraction >= 1f -> R.string.home_pull_release
        else -> R.string.home_pull_hint
    })
    // Disabled modal flows never install a gesture handler on the underlying home screen.
    Box(modifier.clipToBounds().then(if(enabled) Modifier.pullToRefresh(refreshing, state, onRefresh=onRefresh) else Modifier)
        .testTag("home-pull-refresh").semantics {
            stateDescription=label
            if(enabled) customActions=listOf(CustomAccessibilityAction(label) { if(!refreshing) onRefresh(); true })
        }) {
        content()
        if(enabled && (refreshing || state.distanceFraction > 0f || state.isAnimating)) {
            Box(Modifier.align(Alignment.TopCenter).graphicsLayer {
                translationY=state.distanceFraction.coerceIn(0f,1.5f)*64.dp.toPx()-48.dp.toPx()
            }.padding(top=8.dp)
                .testTag("home-pull-indicator"),contentAlignment=Alignment.Center) {
                Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surface,shadowElevation=4.dp) {
                    Box(Modifier.size(40.dp),contentAlignment=Alignment.Center) {
                        Crossfade(refreshing,animationSpec=tween(120),label="refresh-state") {active ->
                        if(active) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,
                            color=MaterialTheme.colorScheme.primary)
                        else AppIcon(UiSymbol.REFRESH,modifier=Modifier.size(20.dp).graphicsLayer {
                            val fraction=state.distanceFraction.coerceIn(0f,1f)
                            rotationZ=fraction*270f;alpha=fraction;scaleX=.7f+.3f*fraction;scaleY=scaleX
                        },tint=MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
