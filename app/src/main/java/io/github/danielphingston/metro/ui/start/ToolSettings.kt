package io.github.danielphingston.metro.ui.start

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.Countdowns
import io.github.danielphingston.metro.data.Routines
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.ToolJson
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.MetroToggle
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import java.time.LocalDate
import java.time.ZoneId

/**
 * The customise sheet's section for a tool tile: a routine's name, schedule, today's doses and
 * picture; a countdown's name and date; how many dice. [onChange] is a settings edit (undoable
 * like other customising); [onData] corrects what was ticked, which is not an edit of the layout.
 */
@Composable
internal fun ToolSettings(
    spec: TileSpec,
    onChange: (TileContent.Tool) -> Unit,
    onData: ((TileContent.Tool) -> TileContent.Tool) -> Unit,
    onChooseIcon: () -> Unit,
    onClearIcon: () -> Unit,
) {
    when (val c = spec.content) {
        is TileContent.Routine -> RoutineSettings(c, spec.icon, onChange, onData, onChooseIcon, onClearIcon)
        is TileContent.Countdown -> CountdownSettings(c, onChange)
        is TileContent.Dice -> DiceSettings(c, onChange)
        else -> Unit
    }
}

@Composable
private fun RoutineSettings(
    r: TileContent.Routine,
    icon: String?,
    onChange: (TileContent.Tool) -> Unit,
    onData: ((TileContent.Tool) -> TileContent.Tool) -> Unit,
    onChooseIcon: () -> Unit,
    onClearIcon: () -> Unit,
) {
    val context = LocalContext.current
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val latest by rememberUpdatedState(r)
    Section("routine")
    NameField(r.name, "name, such as vitamins") { name -> onChange(latest.copy(name = name)) }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetroChoice("every day", r.daily, { onChange(r.copy(daily = true, times = r.times.ifEmpty { listOf(8 * 60) })) }, Modifier.weight(1f))
        MetroChoice("every few days", !r.daily, { onChange(r.copy(daily = false)) }, Modifier.weight(1f))
    }
    if (r.daily) {
        MetroText("Times", MetroType.body, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            r.times.forEach { t ->
                // The last time can't go: a daily routine has at least one.
                MetroChoice(
                    if (r.times.size > 1) "${Routines.timeLabel(t, is24)}  ✕" else Routines.timeLabel(t, is24), true,
                    { if (r.times.size > 1) onChange(r.copy(times = r.times - t)) },
                )
            }
        }
        if (r.times.size < Routines.MAX_TIMES) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Routines.PRESETS.filter { (_, t) -> t !in r.times }.forEach { (name, t) ->
                    MetroChoice("+ $name", false, { onChange(r.copy(times = Routines.addTime(r.times, t))) })
                }
                MetroChoice("+ other time…", false, {
                    TimePickerDialog(context, { _, h, m -> onChange(latest.copy(times = Routines.addTime(latest.times, h * 60 + m))) }, 9, 0, is24).show()
                })
            }
        }
        Hint("Up to ${Routines.MAX_TIMES} times a day. Each one is due from its time. A tap on the tile ticks the next; a double tap takes back today's last tick.")
    } else {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetroChoice("−", false, { onChange(r.copy(everyDays = (r.everyDays - 1).coerceAtLeast(1))) })
            MetroText(Routines.everyText(r.everyDays), MetroType.body, modifier = Modifier.weight(1f))
            MetroChoice("+", false, { onChange(r.copy(everyDays = (r.everyDays + 1).coerceAtMost(Routines.MAX_EVERY_DAYS))) })
        }
        Hint("The tile lights up in the accent colour when it's due and counts the days late after that.")
    }
    Section("today")
    val now = io.github.danielphingston.metro.ui.tiles.LocalToolClock.current()
    val zone = ZoneId.systemDefault()
    val done = Routines.doneToday(r, now, zone)
    if (r.daily) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            r.times.forEachIndexed { i, t ->
                MetroChoice(
                    (if (i < done) "✓ " else "") + Routines.timeLabel(t, is24), i < done,
                    { onData { c -> (c as? TileContent.Routine)?.let { Routines.setToday(it, if (i < done) i else i + 1, now, zone) } ?: c } },
                )
            }
        }
        Hint("Tap a time to tick it or take the tick back.")
    } else {
        MetroToggle("Done today", done > 0, { on -> onData { c -> (c as? TileContent.Routine)?.let { Routines.setToday(it, if (on) 1 else 0, now, zone) } ?: c } })
        MetroToggle("Done yesterday", Routines.doneYesterday(r, now, zone), { on -> onData { c -> (c as? TileContent.Routine)?.let { Routines.setYesterday(it, on, now, zone) } ?: c } })
    }
    Section("picture")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetroChoice("tick", icon == null, onClearIcon, Modifier.weight(1f))
        MetroChoice("emoji or icon…", icon != null, onChooseIcon, Modifier.weight(1f))
    }
}

@Composable
private fun CountdownSettings(c: TileContent.Countdown, onChange: (TileContent.Tool) -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(c)
    Section("countdown")
    NameField(c.name, "name, such as holiday") { name -> onChange(latest.copy(name = name)) }
    MetroText("Date", MetroType.body, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    MetroButton(Countdowns.dateLabel(c.epochDay) + " · change…", {
        val date = LocalDate.ofEpochDay(c.epochDay)
        DatePickerDialog(context, { _, y, m, d -> onChange(latest.copy(epochDay = LocalDate.of(y, m + 1, d).toEpochDay())) }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
            // A countdown is for days that matter now: ten years back, fifty ahead.
            val today = LocalDate.now()
            datePicker.minDate = today.minusYears(10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            datePicker.maxDate = today.plusYears(50).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    })
    Hint("Tap the tile to switch between days, weeks and the date. After the day it counts up.")
}

@Composable
private fun DiceSettings(d: TileContent.Dice, onChange: (TileContent.Tool) -> Unit) {
    Section("dice")
    MetroText("How many", MetroType.body, modifier = Modifier.padding(bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..3).forEach { n -> MetroChoice("$n", d.count == n, { onChange(d.copy(count = n)) }, Modifier.weight(1f)) }
    }
    MetroText("Sides", MetroType.body, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolJson.DICE_SIDES.forEach { n -> MetroChoice("d$n", d.sides == n, { onChange(d.copy(sides = n)) }, Modifier.weight(1f)) }
    }
    Hint("A small tile shows the first die; medium shows them all, wide and large add the total.")
}

/** A name typed in the sheet; saved once, when typing ends or the sheet closes, not per letter. */
@Composable
private fun NameField(initial: String, placeholder: String, onCommit: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    val commit by rememberUpdatedState { if (text.trim() != initial) onCommit(text.trim()) }
    DisposableEffect(Unit) { onDispose { commit() } }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val fg = Metro.colors.foreground
    BasicTextField(
        text,
        { text = it.take(io.github.danielphingston.metro.data.ToolLimits.NAME) },
        singleLine = true,
        textStyle = MetroType.body.copy(color = fg),
        cursorBrush = SolidColor(Metro.colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focus.clearFocus()
        }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            }
            .border(2.dp, if (focused) Metro.colors.accent else Metro.colors.subtle.copy(alpha = 0.6f))
            .padding(12.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth()) {
                if (text.isEmpty()) MetroText(placeholder, MetroType.body, color = Metro.colors.subtle, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    MetroText(title, MetroType.sectionTitle, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).semantics { heading() })
}

@Composable
private fun Hint(text: String) {
    MetroText(text, MetroType.caption, color = Metro.colors.subtle, modifier = Modifier.padding(top = 8.dp))
}
