package io.github.danielphingston.metro.ui

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.platform.LocalView
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.ui.applist.AppListScreen
import io.github.danielphingston.metro.ui.applist.AppListState
import io.github.danielphingston.metro.ui.start.StartScreen
import io.github.danielphingston.metro.ui.start.rememberStartState
import io.github.danielphingston.metro.ui.theme.Metro
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Starts activities from launcher UI with the system "reveal from the tapped element" transition. */
class AppLauncher(private val view: View, private val viewModel: LauncherViewModel) {
    /** Screen-space source bounds and ActivityOptions for a launch from [boundsInRoot]. */
    fun transition(boundsInRoot: ComposeRect?): Pair<Rect?, Bundle?> {
        val rect = boundsInRoot?.let {
            Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
        } ?: return null to null
        val options = ActivityOptions.makeClipRevealAnimation(view, rect.left, rect.top, rect.width(), rect.height())
        val origin = IntArray(2).also(view::getLocationOnScreen)
        return Rect(rect).apply { offset(origin[0], origin[1]) } to options.toBundle()
    }

    fun launch(app: AppInfo, boundsInRoot: ComposeRect?) {
        val (bounds, options) = transition(boundsInRoot)
        viewModel.launch(app, bounds, options)
    }

    /** Starts any intent (e.g. a tile's target app) with the same transition. */
    fun start(intent: Intent, boundsInRoot: ComposeRect?) {
        val (bounds, options) = transition(boundsInRoot)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).sourceBounds = bounds
        runCatching { view.context.startActivity(intent, options) }
    }
}

@Composable
fun LauncherScreen(viewModel: LauncherViewModel) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val startState = rememberStartState()
    val appListState = remember { AppListState() }
    val view = LocalView.current

    val launcher = remember(view, viewModel) { AppLauncher(view, viewModel) }

    LaunchedEffect(Unit) {
        viewModel.homePresses.collect { animate ->
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

    Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !startState.editing,
            beyondViewportPageCount = 1,
            key = { it },
        ) { page ->
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
