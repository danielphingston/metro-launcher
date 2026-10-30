package io.github.danielphingston.metro.ui.search

import io.github.danielphingston.metro.data.SmartFeature
import java.time.Instant
import java.time.ZoneId

/** Feature 1: the answer of a calculation, unit conversion or date question ("days till nov 3"), as the top row; tapping it copies the answer. It never takes Enter: a phone number or date also reads as a sum. */
internal object CalculatorProvider : SearchProvider {
    override val id = "calculator"
    override val feature = SmartFeature.CALCULATOR
    override val placement = Placement.TOP

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        val answer = Calculator.evaluate(query, ctx.locale) ?: UnitConverter.convert(query, ctx.locale)
            ?: DateAnswers.answer(query, Instant.ofEpochMilli(ctx.now).atZone(ZoneId.systemDefault()).toLocalDate(), ctx.locale)
            ?: return emptyList()
        return listOf(
            SearchItem(
                key = "answer",
                title = answer.text,
                subtitle = "${answer.input}  ·  tap to copy",
                icon = SearchGlyphs.equals,
                action = SearchActions.copy(answer.text),
                prominent = true,
                capturesEnter = false,
            ),
        )
    }
}
