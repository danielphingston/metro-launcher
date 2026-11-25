package io.github.danielphingston.metro.ui.onboarding

import android.app.role.RoleManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.live.NotificationAccess
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.settings.AccentFromBackgroundToggle
import io.github.danielphingston.metro.ui.settings.BackgroundChoice
import io.github.danielphingston.metro.ui.settings.TileTransparencySlider
import io.github.danielphingston.metro.ui.settings.AccentGrid
import io.github.danielphingston.metro.ui.settings.NOTIFICATION_DISCLOSURE
import io.github.danielphingston.metro.ui.settings.RestoreFromBackupButton
import io.github.danielphingston.metro.ui.settings.rememberOnResume
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch

/**
 * The first-run setup: a full-screen overlay above the launcher, one step per page. Back goes to
 * the previous step and never leaves it; every step can be skipped and the last is "done", and both
 * end through [onFinish]: with hints on start after "done", without after skipping or restoring a
 * backup. It is not a separate activity, so Home leaves it where it is.
 */
@Composable
internal fun OnboardingScreen(settings: LauncherSettings, onFinish: (withHints: Boolean) -> Unit) {
    val colors = Metro.colors
    val app = LocalContext.current.applicationContext as MetroApplication
    // The user's own tiles, for the grid step's preview.
    val tiles by app.tiles.tiles.collectAsState()
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = OnboardingStep.entries[stepIndex.coerceIn(0, OnboardingStep.entries.lastIndex)]
    val scroll = rememberScrollState()

    fun go(target: OnboardingStep?) {
        if (target != null) stepIndex = target.ordinal
    }
    // Back only unwinds the setup; on the first step it is swallowed rather than leaving it by accident.
    BackHandler { go(step.previous) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // Swallow touches so nothing reaches the start screen underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics { paneTitle = "setup" }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Column(Modifier.weight(1f).verticalScroll(scroll)) {
            MetroText("METRO LAUNCHER  ${step.ordinal + 1}/${OnboardingStep.entries.size}", MetroType.appName, color = colors.subtle)
            MetroText(
                titleOf(step),
                MetroType.pageTitle,
                maxLines = 1,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(20.dp))
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep(onRestored = { onFinish(false) })
                OnboardingStep.LIVE_TILES -> LiveTilesStep()
                OnboardingStep.LOOK -> LookStep(settings)
                OnboardingStep.BACKGROUND -> BackgroundStep(settings, app.settings)
                OnboardingStep.GRID -> StartGridStep(settings, app.settings, tiles.orEmpty())
                OnboardingStep.DONE -> DoneStep()
            }
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step.isLast) {
                MetroButton("done", onClick = { onFinish(true) }, modifier = Modifier.weight(1f))
            } else {
                MetroButton("skip", onClick = { onFinish(false) }, modifier = Modifier.weight(1f))
                MetroButton("next", onClick = { go(step.next) }, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun titleOf(step: OnboardingStep) = when (step) {
    OnboardingStep.WELCOME -> "welcome"
    OnboardingStep.LIVE_TILES -> "live tiles"
    OnboardingStep.LOOK -> "your look"
    OnboardingStep.BACKGROUND -> "background"
    OnboardingStep.GRID -> "your start"
    OnboardingStep.DONE -> "all set"
}

@Composable
private fun WelcomeStep(onRestored: () -> Unit) {
    val context = LocalContext.current
    val colors = Metro.colors
    val roleManager = remember { context.getSystemService(RoleManager::class.java) }
    val isDefaultHome = rememberOnResume { roleManager?.isRoleHeld(RoleManager.ROLE_HOME) == true }
    val homeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    MetroText(
        "Metro puts your apps on a start screen of tiles that come alive with your notifications, " +
            "in the style of Windows Phone.",
        MetroType.body,
    )
    Spacer(Modifier.height(12.dp))
    MetroText("A few quick choices and you are ready. You can change all of them later in settings.", MetroType.caption, color = colors.subtle)
    if (roleManager != null && !isDefaultHome) {
        Spacer(Modifier.height(24.dp))
        MetroText(
            "Make Metro your home screen so the home button opens your start screen.",
            MetroType.caption,
            color = colors.subtle,
        )
        Spacer(Modifier.height(10.dp))
        MetroButton("set as default home app", onClick = {
            homeRequest.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
        })
    }
    Spacer(Modifier.height(24.dp))
    MetroText("Moving from another phone? Bring your tiles and settings back from a backup.", MetroType.caption, color = colors.subtle)
    Spacer(Modifier.height(10.dp))
    // A restored backup is the user's own setup: nothing is left to ask.
    RestoreFromBackupButton(onRestored = onRestored)
}

@Composable
private fun LiveTilesStep() {
    val context = LocalContext.current
    val colors = Metro.colors
    // Re-read on resume: the permission is given in system settings, and the user comes back here.
    val granted = rememberOnResume { NotificationAccess.isGranted(context) }

    MetroText(NOTIFICATION_DISCLOSURE, MetroType.caption, color = colors.subtle)
    Spacer(Modifier.height(12.dp))
    if (granted) {
        MetroText("✓ Notification access is on", MetroType.body, color = colors.accent)
    } else {
        MetroButton("turn on notification access", onClick = {
            context.startActivity(NotificationAccess.settingsIntent(context))
        })
        Spacer(Modifier.height(10.dp))
        MetroText("You can skip this and turn it on later in settings, tiles.", MetroType.caption, color = colors.subtle)
    }
}

@Composable
private fun LookStep(settings: LauncherSettings) {
    val colors = Metro.colors
    val scope = rememberCoroutineScope()
    val repository = (LocalContext.current.applicationContext as MetroApplication).settings

    MetroText("theme", MetroType.sectionTitle, modifier = Modifier.padding(bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            MetroChoice(
                mode.name.lowercase(),
                settings.theme == mode,
                { scope.launch { repository.setTheme(mode) } },
                Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    MetroText("accent colour", MetroType.sectionTitle, modifier = Modifier.padding(bottom = 8.dp))
    AccentGrid(settings.accent) { scope.launch { repository.setAccent(it) } }
    Spacer(Modifier.height(10.dp))
    MetroText("The change shows straight away.", MetroType.caption, color = colors.subtle)
}

@Composable
internal fun BackgroundStep(settings: LauncherSettings, repository: SettingsRepository) {
    MetroText(
        "Plain, one of your photos, or your phone's wallpaper.",
        MetroType.caption,
        color = Metro.colors.subtle,
    )
    Spacer(Modifier.height(16.dp))
    BackgroundChoice(settings, repository)
    AccentFromBackgroundToggle(settings, repository)
    Spacer(Modifier.height(16.dp))
    TileTransparencySlider(settings, repository)
}

@Composable
private fun DoneStep() {
    val colors = Metro.colors
    MetroText("Your start screen is ready. Short tips will show on it as you go.", MetroType.body, color = colors.foreground)
    Spacer(Modifier.height(12.dp))
    MetroText("You can run this setup again from settings, about.", MetroType.caption, color = colors.subtle)
}
