package io.github.danielphingston.metro.ui

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.BackgroundPhoto
import io.github.danielphingston.metro.ui.applist.AppListScreen
import io.github.danielphingston.metro.ui.applist.AppListState
import io.github.danielphingston.metro.ui.start.LocalTurnstile
import io.github.danielphingston.metro.ui.start.StartScreen
import io.github.danielphingston.metro.ui.start.Turnstile
import io.github.danielphingston.metro.ui.start.TurnstileReturn
import io.github.danielphingston.metro.ui.start.TurnstileState
import io.github.danielphingston.metro.ui.start.continuum
import io.github.danielphingston.metro.ui.start.rememberStartState
import io.github.danielphingston.metro.ui.theme.LocalStartBackdrop
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.StartBackdrop
import io.github.danielphingston.metro.ui.theme.startBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Starts activities from launcher UI: the visible tiles or rows swing away ([turnstile], the
 * tapped one last) and then the app opens.
 */
class AppLauncher(
    private val view: View,
    private val viewModel: LauncherViewModel,
    private val turnstile: TurnstileState,
    private val scope: CoroutineScope,
) {
    /**
     * Screen-space source bounds and ActivityOptions for a launch from [boundsInRoot]: the system
     * "reveal from the tapped element" transition, or a plain fade when [fade] is set.
     */
    fun transition(boundsInRoot: ComposeRect?, fade: Boolean = false): Pair<Rect?, Bundle?> {
        val rect = boundsInRoot?.let {
            Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
        }
        val bounds = rect?.let {
            val origin = IntArray(2).also(view::getLocationOnScreen)
            Rect(it).apply { offset(origin[0], origin[1]) }
        }
        val options = when {
            fade -> ActivityOptions.makeCustomAnimation(view.context, android.R.anim.fade_in, android.R.anim.fade_out)
            rect != null -> ActivityOptions.makeClipRevealAnimation(view, rect.left, rect.top, rect.width(), rect.height())
            else -> null
        }
        return bounds to options?.toBundle()
    }

    fun launch(app: AppInfo, boundsInRoot: ComposeRect?) =
        open(boundsInRoot) { bounds, options -> viewModel.launch(app, bounds, options) }

    /** Starts any intent (e.g. a tile's target app) the same way. */
    fun start(intent: Intent, boundsInRoot: ComposeRect?) = open(boundsInRoot) { bounds, options ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).sourceBounds = bounds
        runCatching { view.context.startActivity(intent, options) }.isSuccess
    }

    /**
     * Plays the turnstile exit (at most about 350ms, skipped when animations are off), then runs
     * [start]; if the system refuses the launch the elements come straight back. [start] gets
     * screen-space source bounds and ActivityOptions and returns whether the launch happened.
     */
    fun open(boundsInRoot: ComposeRect?, start: (Rect?, Bundle?) -> Boolean) {
        if (turnstile.busy) return
        scope.launch {
            val swung = turnstile.exit(boundsInRoot)
            // After a turnstile the tapped element is gone, so revealing from it would open from
            // empty space: a fade into the app matches the swing better. Without a turnstile
            // (animations off) keep the system reveal.
            val (bounds, options) = transition(boundsInRoot, fade = swung)
            if (!start(bounds, options) && swung) turnstile.reset()
        }
    }
}

@Composable
fun LauncherScreen(viewModel: LauncherViewModel) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val startState = rememberStartState()
    val appListState = remember { AppListState() }
    val view = LocalView.current
    val context = LocalContext.current
    val turnstile = remember { TurnstileState { Turnstile.animationsEnabled(context.contentResolver) } }

    val launcher = remember(view, viewModel, turnstile, scope) { AppLauncher(view, viewModel, turnstile, scope) }
    TurnstileReturn(turnstile)

    LaunchedEffect(Unit) {
        viewModel.homePresses.collect { animate ->
            // Home on another page lands on start with nothing to swing back in to: restore at once.
            if (animate || pager.currentPage != 0) turnstile.reset()
            startState.exitEdit()
            startState.dismissOverlays()
            appListState.reset()
            if (animate) {
                launch { pager.animateScrollToPage(0) }
                startState.scroll.animateScrollTo(0)
            } else {
                pager.scrollToPage(0)
                startState.scroll.scrollTo(0)
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.pinned.collect {
            appListState.reset()
            pager.animateScrollToPage(0)
            startState.scroll.animateScrollTo(startState.scroll.maxValue)
        }
    }

    // A launcher never finishes on back: back only unwinds launcher UI state.
    BackHandler {
        when {
            startState.editing -> startState.exitEdit()
            startState.dismissOverlays() -> Unit
            appListState.handleBack() -> Unit
            pager.currentPage != 0 -> scope.launch { pager.animateScrollToPage(0) }
        }
    }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val image by viewModel.backdropImage.collectAsStateWithLifecycle()
    // Read in the draw phase only, so scrolling the start screen never recomposes.
    val backdrop = remember(settings.background, settings.backgroundPhoto, settings.transparencyFraction, image, startState) {
        StartBackdrop.of(settings, image) { startState.scroll.value * BackgroundPhoto.PARALLAX }
    }

    CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
            CompositionLocalProvider(LocalTurnstile provides turnstile) {
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = !startState.editing,
                    beyondViewportPageCount = 1,
                    key = { it },
                ) { page ->
                    Box(Modifier.fillMaxSize().continuum(pager, page)) {
                        when (page) {
                            0 -> StartScreen(
                                viewModel = viewModel,
                                state = startState,
                                isCurrentPage = pager.currentPage == 0,
                                launcher = launcher,
                                onShowApps = { scope.launch { pager.animateScrollToPage(1) } },
                            )
                            else -> AppListScreen(viewModel, appListState, launcher)
                        }
                    }
                }
            }
        }
    }
}
