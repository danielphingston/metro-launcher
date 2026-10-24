package io.github.danielphingston.metro.ui.tiles

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.live.weather.DeviceLocation
import io.github.danielphingston.metro.live.weather.StoredWeather
import io.github.danielphingston.metro.live.weather.WeatherApi
import io.github.danielphingston.metro.live.weather.WeatherPlace
import io.github.danielphingston.metro.live.weather.WeatherRefresh
import io.github.danielphingston.metro.live.weather.WeatherStore
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.settings.Section
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

/** Section in Metro settings (location, units). */
@Composable
fun WeatherSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Metro.colors
    val stored by remember { WeatherStore.stored(context) }.collectAsStateWithLifecycle(StoredWeather())
    var query by remember { mutableStateOf("") }
    var searchId by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<WeatherPlace>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(searchId) {
        if (searchId == 0) return@LaunchedEffect
        results = emptyList()
        status = "Searching..."
        val found = withContext(Dispatchers.IO) {
            try {
                WeatherApi.search(query.trim())
            } catch (e: IOException) {
                null
            } catch (e: JSONException) {
                null
            }
        }
        results = found.orEmpty()
        status = when {
            found == null -> "Couldn't search. Check your connection."
            found.isEmpty() -> "No places found"
            else -> null
        }
    }

    fun choose(place: WeatherPlace) {
        query = ""
        results = emptyList()
        status = null
        scope.launch {
            WeatherStore.setPlace(context, place)
            WeatherRefresh.onSettingsChanged(context)
        }
    }

    fun locate() {
        status = "Finding your location..."
        scope.launch {
            val fix = DeviceLocation.current(context)
            if (fix == null) {
                status = "Couldn't get your location. Check that location is turned on."
            } else {
                choose(WeatherPlace(WeatherPlace.MY_LOCATION, "", fix.latitude, fix.longitude))
            }
        }
    }

    val locationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else status = "Location permission was denied."
    }

    Section("weather")
    val place = stored.place
    MetroText(place?.name ?: "No location set", MetroType.body)
    if (place != null && place.detail.isNotEmpty()) MetroText(place.detail, MetroType.caption, color = colors.subtle)
    Spacer(Modifier.height(10.dp))

    CityField(query, { query = it }, onSearch = { if (query.isNotBlank()) searchId++ })
    results.forEach { result ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { choose(result) }
                .padding(vertical = 8.dp),
        ) {
            MetroText(result.name, MetroType.body)
            if (result.detail.isNotEmpty()) MetroText(result.detail, MetroType.caption, color = colors.subtle)
        }
    }
    status?.let { MetroText(it, MetroType.caption, color = colors.subtle, modifier = Modifier.padding(top = 8.dp)) }
    Spacer(Modifier.height(10.dp))

    MetroButton("use my location", onClick = {
        if (DeviceLocation.hasPermission(context)) locate() else locationRequest.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    })
    Spacer(Modifier.height(16.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("°C" to false, "°F" to true).forEach { (label, fahrenheit) ->
            UnitChoice(label, stored.fahrenheit == fahrenheit) {
                scope.launch {
                    WeatherStore.setFahrenheit(context, fahrenheit)
                    if (stored.place != null) WeatherRefresh.onSettingsChanged(context)
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    MetroText(
        "Updates about once an hour while a weather tile is on your start screen.",
        MetroType.caption,
        color = colors.subtle,
    )
    // Open-Meteo's CC BY 4.0 licence requires this attribution.
    MetroText(
        "Weather data by Open-Meteo.com",
        MetroType.caption,
        color = colors.subtle,
        modifier = Modifier
            .clickable(role = Role.Button) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://open-meteo.com/"))) }
            }
            .padding(top = 4.dp),
    )
}

@Composable
private fun CityField(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    val colors = Metro.colors
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MetroType.body.copy(color = colors.foreground),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(2.dp, if (query.isEmpty()) colors.subtle else colors.accent)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (query.isEmpty()) MetroText("search for a city", MetroType.body, color = colors.subtle)
                field()
            }
        },
    )
}

@Composable
private fun UnitChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Metro.colors
    Box(
        Modifier
            .metroTilt()
            .border(2.dp, if (selected) colors.accent else colors.foreground)
            .background(if (selected) colors.accent else colors.background)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        MetroText(label, MetroType.body, color = if (selected) colors.onAccent else colors.foreground)
    }
}
