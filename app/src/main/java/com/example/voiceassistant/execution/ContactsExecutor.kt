package com.example.voiceassistant.execution

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.voiceassistant.nlp.AppIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Looks up a contact by name and reports their phone number.
 *
 * This is a **read-only** tool: it never writes to the contacts provider and
 * never changes any device state, which is why it does not go through the
 * confirmation gate. Reading a contact does expose personal data, so it is
 * gated on the user having granted READ_CONTACTS — without that permission
 * the tool refuses rather than silently returning nothing.
 */
class ContactsExecutor : IntentExecutor<AppIntent.LookUpContact> {
    override suspend fun execute(context: Context, intent: AppIntent.LookUpContact): String =
        withContext(Dispatchers.IO) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException(
                    "The contacts permission is required to look up a contact"
                )
            }

            val name = intent.name.trim()
            if (name.isEmpty()) throw IllegalArgumentException("A contact name is required")

            val matches = findContacts(context, name)
            when (matches.size) {
                0 -> "I could not find a contact called $name."
                1 -> {
                    val contact = matches.first()
                    val numbers = contact.numbers
                    when {
                        numbers.isEmpty() -> "I found ${contact.displayName}, but there is no phone number saved."
                        numbers.size == 1 -> "${contact.displayName}: ${numbers.first()}."
                        else -> "${contact.displayName} has ${numbers.size} numbers: " +
                            numbers.joinToString(", ") + "."
                    }
                }
                else -> {
                    val names = matches.joinToString(", ") { it.displayName }
                    "I found $names. Which one did you mean?"
                }
            }
        }

    /** A contact and the phone numbers stored for them. */
    data class ContactMatch(
        val displayName: String,
        val numbers: List<String>
    )

    /**
     * Finds contacts whose display name matches [name].
     *
     * The match is case-insensitive and also tries a "contains" match, because
     * people say "call Priya" when the contact is saved as "Priya Sharma".
     */
    private fun findContacts(context: Context, name: String): List<ContactMatch> {
        val results = LinkedHashMap<String, ContactMatch>()

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (nameIndex < 0 || numberIndex < 0) return@use

            while (cursor.moveToNext()) {
                val displayName = cursor.getString(nameIndex) ?: continue
                val number = cursor.getString(numberIndex) ?: continue

                if (!matchesName(displayName, name)) continue

                val existing = results[displayName]
                if (existing == null) {
                    results[displayName] = ContactMatch(displayName, listOf(number))
                } else if (!existing.numbers.contains(number)) {
                    results[displayName] = existing.copy(numbers = existing.numbers + number)
                }
            }
        }

        // An exact (case-insensitive) match beats a partial one.
        val exact = results.values.filter { it.displayName.equals(name, ignoreCase = true) }
        return if (exact.isNotEmpty()) exact else results.values.toList()
    }

    private fun matchesName(displayName: String, query: String): Boolean {
        if (displayName.equals(query, ignoreCase = true)) return true
        val normalizedDisplay = displayName.lowercase(Locale.ROOT)
        val normalizedQuery = query.lowercase(Locale.ROOT)
        if (normalizedDisplay.contains(normalizedQuery)) return true
        // "priya" should find "Priya Sharma": compare the first word too.
        val firstWord = normalizedDisplay.split(' ').firstOrNull() ?: return false
        return firstWord == normalizedQuery
    }
}
