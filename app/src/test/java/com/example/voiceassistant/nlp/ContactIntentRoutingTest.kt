package com.example.voiceassistant.nlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the contact-lookup routing.
 *
 * The contact tool is read-only, so it must NOT require confirmation. That is
 * asserted here because a regression would silently start asking the user to
 * confirm a lookup — or, worse, stop asking to confirm something that does
 * change the device.
 */
class ContactIntentRoutingTest {

    private val router = IntentRouter()

    @Test
    fun `a possessive phone number request resolves the name`() {
        // The router receives text that has already been lower-cased by the
        // normalizer, so the extracted name is lower-case too.
        val evaluation = router.route("what is priya's number")
        val intent = evaluation.intent
        assertTrue("expected a contact lookup, got $intent", intent is AppIntent.LookUpContact)
        assertEquals("priya", (intent as AppIntent.LookUpContact).name)
    }

    @Test
    fun `a call request resolves the name`() {
        val evaluation = router.route("call Priya")
        val intent = evaluation.intent
        assertTrue("expected a contact lookup, got $intent", intent is AppIntent.LookUpContact)
        assertEquals("Priya", (intent as AppIntent.LookUpContact).name)
    }

    @Test
    fun `a lookup with a called clause resolves the name`() {
        val evaluation = router.route("look up a contact called John Smith")
        val intent = evaluation.intent
        assertTrue("expected a contact lookup, got $intent", intent is AppIntent.LookUpContact)
        assertEquals("John Smith", (intent as AppIntent.LookUpContact).name)
    }

    @Test
    fun `a number for phrase resolves the name`() {
        val evaluation = router.route("what is the number for Priya")
        val intent = evaluation.intent
        assertTrue("expected a contact lookup, got $intent", intent is AppIntent.LookUpContact)
        assertEquals("Priya", (intent as AppIntent.LookUpContact).name)
    }

    @Test
    fun `a contact lookup does not change device state`() {
        // Read-only: reading a contact never modifies anything, so it must not
        // go through the confirmation gate.
        assertFalse(AppIntent.LookUpContact("Priya").changesDeviceState)
    }

    @Test
    fun `device-changing intents still require confirmation`() {
        // The safety invariant must survive the addition of the contacts tool.
        assertTrue(AppIntent.ToggleFlashlight(true).changesDeviceState)
        assertTrue(AppIntent.SetAlarm("07:00").changesDeviceState)
        assertTrue(AppIntent.SetTimer(600).changesDeviceState)
        assertTrue(AppIntent.DeleteFile("notes").changesDeviceState)
        assertFalse(AppIntent.StatusReport.changesDeviceState)
    }

    @Test
    fun `a request without a name is not routed to the contacts tool`() {
        val name = router.extractContactName("call my contact")
        assertEquals(null, name)
    }
}
