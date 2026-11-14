package com.schaubeck.waddeln.sound

/**
 * Speech synthesis knows standard German only. Dialect words from the speech bubbles can be
 * respelled here so they come out right; extend the map when something sounds wrong.
 */
object Pronunciation {
    private val RESPELLED = mapOf(
        "Schadder" to "Schadda",
        "bissl" to "bissel",
    )

    private val words = Regex("""\p{L}+""")

    fun spoken(text: String): String = text.replace(words) { RESPELLED[it.value] ?: it.value }
}
