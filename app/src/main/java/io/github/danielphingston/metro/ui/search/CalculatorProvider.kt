package io.github.danielphingston.metro.ui.search

import io.github.danielphingston.metro.data.SmartFeature

/** Feature 1: the answer of a calculation or unit conversion, as the top row; tapping it copies the answer. It never takes Enter: a phone number or date also reads as a sum. */
internal object CalculatorProvider : SearchProvider {
    override val id = "calculator"
    override val feature = SmartFeature.CALCULATOR
    override val placement = Placement.TOP

    override suspend fun results(query: String, ctx: SearchContext): List<SearchItem> {
        val answer = Calculator.evaluate(query, ctx.locale) ?: UnitConverter.convert(query, ctx.locale) ?: return emptyList()
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
