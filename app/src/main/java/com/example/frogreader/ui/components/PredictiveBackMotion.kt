package com.example.frogreader.ui.components

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

private const val PredictiveBackMillis = 220
private const val PredictiveBackScale = 0.96f
private const val PredictiveBackAlpha = 0f

internal fun predictiveBackEnter(): EnterTransition =
    fadeIn(tween(PredictiveBackMillis, easing = LinearEasing))

internal fun predictiveBackExit(): ExitTransition =
    scaleOut(tween(PredictiveBackMillis, easing = LinearEasing), targetScale = PredictiveBackScale) +
        fadeOut(tween(PredictiveBackMillis, easing = LinearEasing), targetAlpha = PredictiveBackAlpha)

/** Local back handlers mirror the outgoing destination's gesture motion. */
@Composable
internal fun rememberPredictiveBackMotion(enabled: Boolean = true, onBack: () -> Unit): Modifier {
    var progress by remember { mutableFloatStateOf(0f) }
    var returning by remember { mutableStateOf(false) }
    val action by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled) { events ->
        try {
            events.collect { event ->
                returning = false
                progress = event.progress.coerceIn(0f, 1f)
            }
            action()
        } finally {
            returning = true
            progress = 0f
        }
    }
    val motion by animateFloatAsState(
        targetValue = progress,
        animationSpec = if (returning) tween(durationMillis = PredictiveBackMillis) else snap(),
        label = "local predictive back",
    )
    if (!enabled && motion == 0f) return Modifier
    return Modifier.graphicsLayer {
        val scale = 1f + (PredictiveBackScale - 1f) * motion
        scaleX = scale
        scaleY = scale
        alpha = 1f + (PredictiveBackAlpha - 1f) * motion
    }
}

@Composable
internal fun AppSubpageNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left) + fadeIn() },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left) + fadeOut() },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right) + fadeIn() },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right) + fadeOut() },
        predictivePopEnterTransition = { _ -> predictiveBackEnter() },
        predictivePopExitTransition = { _ -> predictiveBackExit() },
        builder = builder,
    )
}
