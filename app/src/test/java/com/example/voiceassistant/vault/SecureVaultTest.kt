package com.example.voiceassistant.vault

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecureVaultTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val cipher = SoftwareVaultCipher()
    private lateinit var vault: SecureVault

    private fun newVault(errors: MutableList<String> = mutableListOf()): SecureVault =
        SecureVault(
            directory = File(temporaryFolder.root, "vault"),
            cipher = cipher,
            onError = { message -> errors += message }
        ).also { vault = it }

    @Test
    fun `documents round trip`() {
        newVault()
        vault.write("memory", JSONObject().put("taught", 3))

        val restored = vault.read("memory")
        assertNotNull(restored)
        assertEquals(3, restored!!.getInt("taught"))
    }

    @Test
    fun `missing documents read as null`() {
        newVault()
        assertNull(vault.read("memory"))
        assertFalse(vault.exists("memory"))
    }

    @Test
    fun `the file on disk is not readable plaintext`() {
        newVault()
        vault.write("memory", JSONObject().put("secret", "the user's private notes"))

        val onDisk = File(temporaryFolder.root, "vault/memory.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("Vault contents leaked to disk", onDisk.contains("private notes"))
    }

    @Test
    fun `an entry copied to another document name is rejected`() {
        // The document name is authenticated as associated data, so swapping files fails.
        newVault()
        vault.write("memory", JSONObject().put("taught", 1))

        File(temporaryFolder.root, "vault/memory.enc")
            .copyTo(File(temporaryFolder.root, "vault/preferences.enc"))

        assertNull(vault.read("preferences"))
    }

    @Test
    fun `tampered ciphertext is reported and ignored instead of crashing`() {
        val errors = mutableListOf<String>()
        newVault(errors)
        vault.write("memory", JSONObject().put("taught", 1))

        val file = File(temporaryFolder.root, "vault/memory.enc")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        file.writeBytes(bytes)

        assertNull(vault.read("memory"))
        assertTrue("The failure should be reported to the caller", errors.isNotEmpty())
    }

    @Test
    fun `document names are validated`() {
        newVault()
        val rejected = listOf(
            "../escape",
            "sub/dir",
            "",
            "with space",
            "semi;colon"
        )
        for (name in rejected) {
            try {
                vault.write(name, JSONObject())
                throw AssertionError("Expected '$name' to be rejected")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `listing reports stored documents`() {
        newVault()
        vault.write("memory", JSONObject())
        vault.write("preferences", JSONObject())
        assertEquals(listOf("memory", "preferences"), vault.list())
        assertTrue(vault.delete("preferences"))
        assertEquals(listOf("memory"), vault.list())
    }
}
