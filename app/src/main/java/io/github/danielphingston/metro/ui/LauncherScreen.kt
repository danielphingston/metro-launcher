package io.github.danielphingston.metro.ui

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.R
import io.github.danielphingston.metro.data.BackgroundPhoto
import io.github.danielphingston.metro.ui.applist.AppListScreen
import io.github.danielphingston.metro.ui.applist.AppListState
import io.github.danielphingston.metro.ui.components.LocalMotion
import io.github.danielphingston.metro.ui.components.Motion
import io.github.danielphingston.metro.ui.search.LauncherCommands
import io.github.danielphingston.metro.ui.search.SearchOverlay
import io.github.danielphingston.metro.ui.search.SearchState
import io.github.danielphingston.metro.ui.search.pullToSearch
import io.github.danielphingston.metro.ui.start.AppWindow
import io.github.danielphingston.metro.ui.start.LaunchGuard
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
 * Starts activities from launcher UI: the visible tiles or rows animate away in the chosen
 * [Motion.launchStyle] (the tapped one last) and then the app opens, or during the animation
 * when [Motion.openDuringAnimation] is on.
 */
class AppLauncher(
    private val view: View,
    private val viewModel: LauncherViewModel,
    private val turnstile: TurnstileState,
    private val scope: CoroutineScope,
    private val motion: () -> Motion = { Motion() },
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {
    private val guard = LaunchGuard()

    /**
     * Screen-space source bounds and ActivityOptions for a launch from [boundsInRoot]: the system
     * "reveal from the tapped element" transition, or how [window] says the app appears.
     */
    fun transition(boundsInRoot: ComposeRect?, window: AppWindow = AppWindow.CLIP_REVEAL): Pair<Rect?, Bundle?> {
        val rect = boundsInRoot?.let {
            Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
        }
        val bounds = rect?.let {
            val origin = IntArray(2).also(view::getLocationOnScreen)
            Rect(it).apply { offset(origin[0], origin[1]) }
        }
        val context = view.context
        val options = when {
            window == AppWindow.FADE ->
                ActivityOptions.makeCustomAnimation(context, android.R.anim.fade_in, android.R.anim.fade_out)
            window == AppWindow.SLIDE_UP ->
                ActivityOptions.makeCustomAnimation(context, R.anim.launch_slide_in, android.R.anim.fade_out)
            window == AppWindow.ZOOM_IN ->
                ActivityOptions.makeCustomAnimation(context, R.anim.launch_zoom_in, android.R.anim.fade_out)
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
     * Plays the exit (at most about 350ms at normal speed, skipped when animations are off or the
     * style is none), then runs [start]; if the system refuses the launch the elements come
     * straight back. [start] gets screen-space source bounds and ActivityOptions and returns
     * whether the launch happened. A second launch within [Turnstile.DOUBLE_LAUNCH_MS] is ignored,
     * which also covers the cases with no exit to mark the launcher busy.
     */
    fun open(boundsInRoot: ComposeRect?, start: (Rect?, Bundle?) -> Boolean) {
        if (turnstile.busy || !guard.tryLaunch(uptimeMillis())) return
        val current = motion()
        scope.launch {
            var started = false
            fun go(swung: Boolean) {
                started = true
                // After an exit the tapped element is gone, so revealing from it would open from
                // empty space: the style's own window animation matches better. Without an exit
                // (animations off, or none) keep the system reveal.
                val window = if (swung) Turnstile.spec(current.launchStyle).appWindow else AppWindow.CLIP_REVEAL
                val (bounds, options) = transition(boundsInRoot, window)
                if (!start(bounds, options) && swung) turnstile.reset()
            }
            val swung = turnstile.exit(boundsInRoot, current.launchStyle, current.openDuringAnimation) { go(true) }
            if (!started) go(swung)
        }
    }
}

@Composable
fun LauncherScreen(viewModel: LauncherViewModel) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val startState = rememberStartState()
    val appListState = remember { AppListState() }
    val searchState = remember { SearchState() }
    val view = LocalView.current
    val context = LocalContext.current
    val motion = LocalMotion.current
    val currentMotion by rememberUpdatedState(motion)
    val turnstile = remember {
        TurnstileState(
            animationsEnabled = { Turnstile.animationsEnabled(context.contentResolver) },
            timeScale = { Turnstile.timeScale(currentMotion.speed, Turnstile.systemScale(context.contentResolver)) },
        )
    }

    val launcher = remember(view, viewModel, turnstile, scope) {
        AppLauncher(view, viewModel, turnstile, scope, motion = { currentMotion })
    }
    TurnstileReturn(turnstile, animate = motion.animateReturn)
    // What search results can ask of the launcher itself (edit mode, app info, uninstall...).
    val commands = remember(viewModel, startState) {
        object : LauncherCommands {
            override fun editStart() = startState.startEditing()
            override fun appInfo(app: AppInfo) = viewModel.openAppInfo(app)
            override fun uninstall(app: AppInfo) = viewModel.uninstall(app)
            override fun recordAssistantQuestion(question: String) = viewModel.recordAssistantQuestion(question)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.homePresses.collect { animate ->
            // Home on another page lands on start with nothing to swing back in to: restore at once.
            if (animate || pager.currentPage != 0) turnstile.reset()
            startState.exitEdit()
            startState.dismissOverlays()
            searchState.close()
            appListState.reset()
            if (animate) {
                // A touch interrupts these animations; that must not end this collector.
                launch { interruptible { pager.animateScrollToPage(0) } }
                interruptible { startState.scroll.animateScrollTo(0) }
            } else {
                interruptible { pager.scrollToPage(0) }
                interruptible { startState.scroll.scrollTo(0) }
            }
        }
    }
    // Home may not reach onNewIntent (see MainActivity); leaving the launcher closes search regardless.
    LaunchedEffect(Unit) {
        viewModel.launcherLeft.collect { searchState.close() }
    }
    LaunchedEffect(Unit) {
        viewModel.pinned.collect {
            appListState.reset()
            val before = startState.scroll.maxValue
            interruptible { pager.animateScrollToPage(0) }
            // The new tile is laid out after this event, which grows the scroll range.
            interruptible { startState.scroll.animateToGrownEnd(before) }
        }
    }
    // Leaving the launcher: write a tile edit still waiting out the save delay before the process can be killed.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.tileRepository.flush() }

    // A launcher never finishes on back: back only unwinds launcher UI state.
    BackHandler {
        when {
            // Search sits on top of everything.
            searchState.handleBack() -> Unit
            // The app list's own overlays, then the way back to start.
            pager.currentPage != 0 ->
                if (!appListState.handleBack()) scope.launch { pager.animateScrollToPage(0) }
            // On start: flyouts (grouping included) sit on top of everything, then edit mode, then the open folder.
            startState.dismissFlyouts() -> Unit
            startState.editing -> startState.exitEdit()
            startState.dismissOverlays() -> Unit
        }
    }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val image by viewModel.backdropImage.collectAsStateWithLifecycle()
    // Read in the draw phase only, so scrolling the start screen never recomposes.
    val backdrop = remember(settings.background, settings.backgroundPhoto, settings.transparencyFraction, image, startState) {
        StartBackdrop.of(settings, image) { startState.scroll.value * BackgroundPhoto.PARALLAX }
    }

    // Pulling the start screen down past its top and letting go opens search.
    val pullToSearch = Modifier.pullToSearch(
        scroll = startState.scroll,
        enabled = !startState.editing && !startState.flyoutOpen && !searchState.isOpen && pager.currentPage == 0,
        pullDistance = settings.pullDistance.dp,
        onPull = searchState::open,
    )

    CompositionLocalProvider(LocalStartBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().startBackdrop(backdrop, Metro.colors.background)) {
            CompositionLocalProvider(LocalTurnstile provides turnstile) {
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = !startState.editing && !startState.flyoutOpen && !appListState.overlayOpen &&
                        !searchState.isOpen,
                    beyondViewportPageCount = 1,
                    key = { it },
                ) { page ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .continuum(pager, page, enabled = motion.swingPages)
                            .then(if (page == 0) pullToSearch else Modifier),
                    ) {
                        when (page) {
                            0 -> StartScreen(
                                viewModel = viewModel,
                                state = startState,
                                isCurrentPage = pager.currentPage == 0,
                                launcher = launcher,
                                onShowApps = {
                                    // The pager is locked while editing; don't carry edit mode over to the list.
                                    startState.exitEdit()
                                    scope.launch { pager.animateScrollToPage(1) }
                                },
                                onSearch = searchState::open,
                            )
                            else -> AppListScreen(viewModel, appListState, launcher)
                        }
                    }
                }
            }
            // Outside LocalTurnstile's scope: its own field must not swing with the tiles.
            SearchOverlay(viewModel, searchState, launcher, commands)
        }
    }
}
