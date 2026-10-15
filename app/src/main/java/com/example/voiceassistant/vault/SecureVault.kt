package com.example.voiceassistant.vault

import org.json.JSONObject
import java.io.File

/**
 * Encrypted, app-private document store.
 *
 * Every document is sealed with [VaultCipher] (hardware-key AES-GCM in the app) and
 * written to `filesDir/vault/<name>.enc`. The document name is bound in as associated
 * data, so an attacker who can write files cannot swap `memory.enc` for a
 * `preferences.enc` copy and have it accepted.
 *
 * There is no cloud, no `INTERNET` permission and no export path: the vault is only
 * ever readable by this app's UID on this device.
 */
class SecureVault(
    private val directory: File,
    private val cipher: VaultCipher,
    private val onError: (String) -> Unit = {}
) {
    /** Human-readable description of where the key lives (shown in the UI). */
    val backend: String get() = cipher.backend

    fun exists(name: String): Boolean = fileFor(name).isFile

    fun read(name: String): JSONObject? {
        val file = fileFor(name)
        if (!file.isFile) return null
        return try {
            val plaintext = cipher.open(file.readBytes(), name.toByteArray())
            JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (error: Exception) {
            // A tampered, truncated or stale-key entry is reported and ignored rather
            // than crashing the assistant; the caller falls back to an empty document.
            onError("Vault entry '$name' could not be decrypted: ${error.message}")
            null
        }
    }

    fun write(name: String, document: JSONObject) {
        requireValidName(name)
        directory.mkdirs()
        val plaintext = document.toString().toByteArray(Charsets.UTF_8)
        val sealed = cipher.seal(plaintext, name.toByteArray())
        val target = fileFor(name)
        val temporary = File(directory, "$name.enc.tmp")
        temporary.writeBytes(sealed)
        if (!temporary.renameTo(target)) {
            target.writeBytes(sealed)
            temporary.delete()
        }
    }

    fun delete(name: String): Boolean = fileFor(name).delete()

    /** Names of the documents currently stored (without the `.enc` suffix). */
    fun list(): List<String> =
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(SUFFIX) }
            .map { it.name.removeSuffix(SUFFIX) }
            .sorted()

    private fun fileFor(name: String): File {
        requireValidName(name)
        return File(directory, "$name$SUFFIX")
    }

    private fun requireValidName(name: String) {
        require(name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            "Vault document names may only contain letters, digits, '-' and '_'"
        }
    }

    private companion object {
        const val SUFFIX = ".enc"
    }
}
