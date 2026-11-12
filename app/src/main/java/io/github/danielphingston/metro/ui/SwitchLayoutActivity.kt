package io.github.danielphingston.metro.ui

import android.app.Activity
import android.os.Bundle
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.LayoutShortcuts
import kotlinx.coroutines.launch

/**
 * Invisible entry point for [LayoutShortcuts]: an app shortcut or a `metro://layout/...` link
 * lands here, the start layout is switched, and the activity is gone again without showing
 * anything. It does not bring the launcher forward, so a routine can switch the layout while
 * another app is in use and the new tiles are simply there at the next press of Home.
 *
 * Exported on purpose (automation apps must be able to start it); all it can do is switch between
 * layouts the user saved.
 */
class SwitchLayoutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MetroApplication
        val segment = intent?.data?.takeIf { it.scheme == LayoutShortcuts.SCHEME && it.host == LayoutShortcuts.HOST }?.lastPathSegment
        app.appScope.launch {
            app.layouts.awaitLoaded()
            when (val target = LayoutShortcuts.resolve(segment, app.layouts.layouts.value)) {
                is LayoutShortcuts.Target.Layout -> app.layouts.switchTo(target.id)
                LayoutShortcuts.Target.Next -> app.layouts.switchToNext()
                null -> Unit
            }
        }
        finish()
    }
}
