package io.github.danielphingston.metro

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.ui.interruptible
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LauncherScrollTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun anInterruptedAnimationDoesNotEndTheCollector() {
        val scroll = ScrollState(0)
        lateinit var scope: CoroutineScope
        val events = MutableSharedFlow<Int>(extraBufferCapacity = 8)
        var handled = 0
        rule.setContent {
            scope = rememberCoroutineScope()
            Column(Modifier.size(100.dp).verticalScroll(scroll)) { Box(Modifier.height(1000.dp)) }
        }
        rule.runOnIdle {
            scope.launch { events.collect { target -> interruptible { scroll.animateScrollTo(target) }; handled++ } }
        }
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { events.tryEmit(800) }
        rule.mainClock.advanceTimeBy(50)
        // The user grabs the content mid-animation.
        rule.runOnIdle { scope.launch { scroll.scrollTo(5) } }
        rule.mainClock.advanceTimeBy(50)
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { events.tryEmit(0) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(0, scroll.value)
            assertEquals(2, handled)
        }
    }

    @Test
    fun aRealCancellationStillPropagates() = runBlocking {
        var reached = false
        val job = launch {
            interruptible { awaitCancellation() }
            reached = true
        }
        yield()
        job.cancel()
        job.join()
        assertFalse(reached)
    }

    @Test
    fun aCancellationOfTheBlockAloneIsSwallowed() = runBlocking {
        var reached = false
        interruptible { throw CancellationException("interrupted") }
        reached = true
        assertEquals(true, reached)
    }
}
