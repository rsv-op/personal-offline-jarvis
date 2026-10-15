package com.example.voiceassistant.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterTest {

    private val router = IntentRouter()
    private val normalizer = TextNormalizer()

    private fun route(utterance: String) = router.route(normalizer.normalize(utterance))

    @Test
    fun `flashlight polarity is decided by explicit words`() {
        assertEquals(true, router.polarity("turn on the flashlight"))
        assertEquals(false, router.polarity("turn off the flashlight"))
        assertEquals(false, router.polarity("kill the torch"))
        assertEquals(true, router.polarity("switch the torch on"))
    }

    @Test
    fun `ambiguous phrases are left to the brain`() {
        assertNull(router.polarity("flashlight is not needed anymore"))
        assertNull(router.polarity("flashlight"))
        assertEquals(AppIntent.UnknownIntent, route("flashlight").intent)
    }

    @Test
    fun `alarm times are extracted from speech`() {
        assertEquals("06:30 am", router.extractAlarmTime(normalizer.normalize("wake me up at six thirty am")))
        assertEquals("07:00", router.extractAlarmTime(normalizer.normalize("set alarm for seven")))
        assertEquals("23:15", router.extractAlarmTime(normalizer.normalize("alarm at 23 15")))
        assertNull(router.extractAlarmTime(normalizer.normalize("set an alarm")))
    }

    @Test
    fun `impossible clock times are rejected so the assistant can ask again`() {
        // Minutes only run to 59: these are not times, they are garbled speech.
        assertNull(router.extractAlarmTime(normalizer.normalize("alarm at 12 99")))
        assertNull(router.extractAlarmTime(normalizer.normalize("alarm at 12:60")))
        // No 24-hour clock has 24 or 25 o'clock.
        assertNull(router.extractAlarmTime(normalizer.normalize("alarm at 25 30")))
        // A 12-hour phrase cannot name hour 13, and "0 am" is not a clock time either.
        assertNull(router.extractAlarmTime(normalizer.normalize("alarm at 13 pm")))
        assertNull(router.extractAlarmTime(normalizer.normalize("alarm at 0 am")))

        // Boundaries stay valid, and the request still carries its time.
        assertEquals("23:59", router.extractAlarmTime(normalizer.normalize("alarm at 23 59")))
        assertEquals("00:00", router.extractAlarmTime(normalizer.normalize("alarm at 00 00")))
        assertEquals("12:00 pm", router.extractAlarmTime(normalizer.normalize("alarm at 12 pm")))
        assertEquals(AppIntent.SetAlarm("23:59"), route("set an alarm for 23 59").intent)
        // An impossible time degrades to "no time", which triggers the follow-up question.
        assertEquals(AppIntent.SetAlarm(null), route("set an alarm for 12 99").intent)
    }

    @Test
    fun `timer routes carry a duration`() {
        assertEquals(AppIntent.SetTimer(600), route("set a timer for ten minutes").intent)
        assertEquals(AppIntent.SetTimer(null), route("start a countdown").intent)
    }

    @Test
    fun `status requests are routed without a device change`() {
        val evaluation = route("how much battery is left")
        assertEquals(AppIntent.StatusReport, evaluation.intent)
        assertFalse(evaluation.intent.changesDeviceState)
    }

    @Test
    fun `delete requests name the file and are high sensitivity`() {
        val evaluation = route("delete my notes file")
        assertTrue(evaluation.intent is AppIntent.DeleteFile)
        assertEquals("notes", (evaluation.intent as AppIntent.DeleteFile).fileName)
        assertEquals(IntentSensitivity.HIGH, evaluation.sensitivity)
    }

    @Test
    fun `device changing intents are marked as such`() {
        assertTrue(AppIntent.ToggleFlashlight(true).changesDeviceState)
        assertTrue(AppIntent.SetAlarm("07:00").changesDeviceState)
        assertTrue(AppIntent.SetTimer(60).changesDeviceState)
        assertFalse(AppIntent.StatusReport.changesDeviceState)
        assertFalse(AppIntent.UnknownIntent.changesDeviceState)
    }

    @Test
    fun `unrelated speech is not routed`() {
        assertEquals(AppIntent.UnknownIntent, route("what is the capital of france").intent)
        assertEquals(AppIntent.UnknownIntent, route("tell me a joke").intent)
    }
}
