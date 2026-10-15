package com.example.voiceassistant.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationParserTest {

    @Test
    fun `digit durations are parsed with their unit`() {
        assertEquals(600, DurationParser.parseSeconds("set a timer for 10 minutes"))
        assertEquals(45, DurationParser.parseSeconds("timer for 45 seconds"))
        assertEquals(2 * 3600, DurationParser.parseSeconds("timer for 2 hours"))
        assertEquals(90, DurationParser.parseSeconds("90 sec"))
    }

    @Test
    fun `spoken numbers are parsed`() {
        assertEquals(300, DurationParser.parseSeconds("set a timer for five minutes"))
        assertEquals(1800, DurationParser.parseSeconds("start a countdown for thirty minutes"))
        assertEquals(20, DurationParser.parseSeconds("timer for twenty seconds"))
    }

    @Test
    fun `half an hour is understood`() {
        assertEquals(1800, DurationParser.parseSeconds("set a timer for half an hour"))
        assertEquals(3600, DurationParser.parseSeconds("set a timer for an hour"))
    }

    @Test
    fun `a bare number means minutes`() {
        // "set a timer for ten" is what people actually say.
        assertEquals(600, DurationParser.parseSeconds("set a timer for ten"))
    }

    @Test
    fun `missing amounts return null so the assistant can ask`() {
        assertNull(DurationParser.parseSeconds("start a timer"))
        assertNull(DurationParser.parseSeconds("set a timer please"))
    }

    @Test
    fun `durations are described for spoken confirmations`() {
        assertEquals("10 minutes", DurationParser.describe(600))
        assertEquals("1 minute", DurationParser.describe(60))
        assertEquals("1 hour and 30 minutes", DurationParser.describe(5400))
        assertEquals("1 hour", DurationParser.describe(3600))
        assertEquals("45 seconds", DurationParser.describe(45))
        assertEquals("no time", DurationParser.describe(0))
    }
}
