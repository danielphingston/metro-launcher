package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.weather.DayForecast
import io.github.danielphingston.metro.live.weather.Forecast
import io.github.danielphingston.metro.live.weather.WeatherParser
import io.github.danielphingston.metro.live.weather.WeatherLocation
import io.github.danielphingston.metro.live.weather.WeatherPlace
import io.github.danielphingston.metro.live.weather.WeatherRefresh
import io.github.danielphingston.metro.live.weather.WeatherStore
import io.github.danielphingston.metro.live.weather.StoredWeather
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.settings.SettingsActivity
import io.github.danielphingston.metro.ui.start.FlipCard
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import java.time.format.TextStyle
import java.util.Locale

object WeatherTiles {
    private const val SAMSUNG_WEATHER = "com.sec.android.daemonapp"

    /**
     * What tapping the weather tile opens: Metro settings to pick a location, else Samsung
     * Weather if installed, else a web forecast for the place.
     */
    fun intent(context: Context): Intent? {
        // The tile keeps this up to date while it is shown, so a tap never waits for DataStore.
        val place = WeatherStore.latest?.place
            ?: return Intent(context, SettingsActivity::class.java)
        context.packageManager.getLaunchIntentForPackage(SAMSUNG_WEATHER)?.let { return it }
        // A plain "weather" search uses the searcher's own location.
        val query = if (place.isDeviceLocation) "weather" else "weather ${place.name}"
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
    }
}

/**
 * Current conditions, flipping to the high/low or the next days. [flipped] toggles every few
 * seconds while the tile is on screen. Refreshes a forecast older than an hour when shown.
 */
@Composable
fun WeatherTile(tile: ResolvedTile, cellSize: Dp, flipped: Boolean) {
    val context = LocalContext.current
    val m = rememberTileMetrics(cellSize)
    val stored by remember { WeatherStore.stored(context) }.collectAsStateWithLifecycle<StoredWeather?>(null)
    val forecast = remember(stored?.forecastJson) {
        stored?.forecastJson?.let { runCatching { WeatherParser.forecast(it) }.getOrNull() }
    }

    val latest by rememberUpdatedState(stored)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(stored?.place) {
        if (stored?.place == null) return@LaunchedEffect
        WeatherRefresh.schedulePeriodic(context)
        // Each time the launcher comes back to the front, top up an old forecast.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (latest?.let(WeatherRefresh::isStale) == true) WeatherRefresh.refreshNow(context)
            // The device location moves. This stops with the screen; the refresh is retried next time.
            WeatherLocation.refreshIfDue(context)
        }
    }

    val size = tile.spec.size
    val place = stored?.place
    FlipCard(
        showBack = flipped && size != TileSize.SMALL && forecast != null,
        front = {
            if (place == null) NotSetFace(size, tile.label, m) else WeatherFront(size, place, forecast, tile.label, m)
        },
        back = { if (forecast != null) WeatherBack(size, forecast, tile.label, m) },
    )
}

private fun degrees(value: Int?) = if (value == null) "--" else "$value°"

@Composable
private fun NotSetFace(size: TileSize, label: String, m: TileMetrics) {
    val white = Metro.colors.onAccent
    Box(Modifier.fillMaxSize()) {
        if (size == TileSize.SMALL) {
            MetroText(
                "set\nlocation",
                MetroType.style(m.text(12f)).copy(textAlign = TextAlign.Center),
                color = white,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        MetroText(
            "set location",
            MetroType.style(m.text(24f), FontWeight.Light),
            color = white,
            maxLines = 1,
            modifier = Modifier.padding(start = m.dp(8f), top = m.dp(6f)),
        )
        TileLabel(label, m, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun WeatherFront(size: TileSize, place: WeatherPlace, forecast: Forecast?, label: String, m: TileMetrics) {
    val white = Metro.colors.onAccent
    Box(Modifier.fillMaxSize()) {
        if (size == TileSize.SMALL) {
            MetroText(
                degrees(forecast?.temperature),
                MetroType.style(m.text(28f), FontWeight.Light),
                color = white,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        // Wide and large tiles show the same face, just bigger.
        val scale = when (size) {
            TileSize.LARGE -> 1.5f
            TileSize.MEDIUM -> 0.75f
            else -> 1f
        }
        Row(
            Modifier.padding(start = m.dp(8f), top = m.dp(6f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (forecast != null) {
                WeatherGlyph(forecast.condition, forecast.isDay, m.dp(72f * scale))
                Spacer(Modifier.width(m.dp(8f * scale)))
            }
            MetroText(
                degrees(forecast?.temperature),
                MetroType.style(m.text(64f * scale), FontWeight.Light),
                color = white,
                maxLines = 1,
            )
        }
        val today = forecast?.today
        if (size != TileSize.MEDIUM && forecast != null && today != null) {
            val detail = scale.coerceAtMost(1.2f)
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = m.dp(12f), end = m.dp(10f)),
                horizontalAlignment = Alignment.End,
            ) {
                MetroText(forecast.condition.label, MetroType.style(m.text(17f * detail)), color = white, maxLines = 1)
                MetroText("high ${degrees(today.high)}", MetroType.style(m.text(15f * detail)), color = white)
                MetroText("low ${degrees(today.low)}", MetroType.style(m.text(15f * detail)), color = white)
            }
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()) {
            MetroText(
                place.name,
                MetroType.style(m.text(17f)),
                color = white,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = m.dp(7f)),
            )
            TileLabel(label, m)
        }
    }
}

@Composable
private fun WeatherBack(size: TileSize, forecast: Forecast, label: String, m: TileMetrics) {
    val white = Metro.colors.onAccent
    Box(Modifier.fillMaxSize()) {
        val today = forecast.today
        if (size == TileSize.MEDIUM) {
            Column(Modifier.padding(start = m.dp(8f), top = m.dp(8f), end = m.dp(8f))) {
                MetroText(forecast.condition.label, MetroType.style(m.text(24f), FontWeight.Light), color = white, maxLines = 2)
                if (today != null) {
                    MetroText("high ${degrees(today.high)}", MetroType.style(m.text(17f)), color = white)
                    MetroText("low ${degrees(today.low)}", MetroType.style(m.text(17f)), color = white)
                }
            }
        } else {
            val scale = if (size == TileSize.LARGE) 1.4f else 1f
            Row(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = m.dp(8f)),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                forecast.days.drop(1).take(3).forEach { day ->
                    DayColumn(day, scale, Modifier.weight(1f), m)
                }
            }
        }
        TileLabel(label, m, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun DayColumn(day: DayForecast, scale: Float, modifier: Modifier, m: TileMetrics) {
    val white = Metro.colors.onAccent
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.dp(4f))) {
        MetroText(
            day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).lowercase(Locale.getDefault()),
            MetroType.style(m.text(15f * scale)),
            color = white,
            maxLines = 1,
        )
        WeatherGlyph(day.condition, isDay = true, size = m.dp(44f * scale))
        Row {
            MetroText(degrees(day.high), MetroType.style(m.text(15f * scale)), color = white, maxLines = 1)
            Spacer(Modifier.width(m.dp(6f)))
            MetroText(degrees(day.low), MetroType.style(m.text(15f * scale)), color = white.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}
