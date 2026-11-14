package com.schaubeck.waddeln.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {

    @Test
    fun oneDecimalUsesAGermanCommaLikeStringFormat() {
        assertEquals("0,0", oneDecimal(0.0))
        assertEquals("2,5", oneDecimal(2.5))
        assertEquals("1,3", oneDecimal(4.0 / 3))
        assertEquals("1,7", oneDecimal(5.0 / 3))
        assertEquals("12,0", oneDecimal(11.96))
        assertEquals("-0,3", oneDecimal(-0.26))
        assertEquals("0,0", oneDecimal(-0.01))
    }

    @Test
    fun placeholdersAreFilledByPosition() {
        assertEquals("Anna sagt Vier – Eike bleibt.", Strings.format("%1\$s sagt %3\$s – %2\$s bleibt.", arrayOf("Anna", "Eike", "Vier")))
        assertEquals("Spielwert jetzt 4.", Strings.format("Spielwert jetzt %1\$d.", arrayOf(4)))
        assertEquals("Ohne %1\$s", Strings.format("Ohne %1\$s", emptyArray()))
    }
}
