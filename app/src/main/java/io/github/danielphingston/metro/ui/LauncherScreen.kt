package io.github.danielphingston.metro.ui

import android.app.ActivityOptions
import android.graphics.Rect
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

/** Launches an app with the system "reveal from the tapped element" transition. */
fun interface AppLauncher {
    fun launch(app: AppInfo, boundsInRoot: ComposeRect?)
}

@Composable
fun LauncherScreen(viewModel: LauncherViewModel) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val startState = rememberStartState()
    val appListState = remember { AppListState() }
    val view = LocalView.current

    val launcher = remember(view, viewModel) {
        AppLauncher { app, bounds ->
            val rect = bounds?.let {
                Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())
            }
            val options = rect?.let {
                ActivityOptions.makeClipRevealAnimation(view, it.left, it.top, it.width(), it.height()).toBundle()
            }
            val screenRect = rect?.let {
                val origin = IntArray(2).also(view::getLocationOnScreen)
                Rect(it).apply { offset(origin[0], origin[1]) }
            }
            viewModel.launch(app, screenRect, options)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.homePresses.collect { animate ->
            startState.exitEdit()
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
