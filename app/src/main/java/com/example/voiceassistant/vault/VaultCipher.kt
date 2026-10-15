package com.example.voiceassistant.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric encryption used by the vault. Kept behind an interface so the vault's
 * storage logic can be unit-tested on the JVM without a hardware keystore.
 */
interface VaultCipher {
    /** AES-GCM: returns `initializationVector || ciphertext || tag`. */
    fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray

    /** Reverses [seal]; throws if the data or its associated data was tampered with. */
    fun open(payload: ByteArray, associatedData: ByteArray): ByteArray

    /** Where the key lives, for display in the UI. */
    val backend: String
}

/**
 * Hardware-backed AES-GCM cipher.
 *
 * The key never leaves the device's secure element: it is generated inside the Android
 * Keystore, is non-exportable, and is used through [Cipher] only. StrongBox (a dedicated
 * security chip) is requested when the device has one, and the implementation falls back
 * to the TEE-backed keystore otherwise, so vaults still work on devices without one.
 */
class AndroidKeystoreVaultCipher(
    private val alias: String = DEFAULT_ALIAS
) : VaultCipher {

    override val backend: String
        get() = if (strongBoxUsed) "StrongBox secure element ($alias)" else "Hardware keystore ($alias)"

    @Volatile
    private var strongBoxUsed = false

    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv + ciphertext
    }

    override fun open(payload: ByteArray, associatedData: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH_BYTES) { "Encrypted vault entry is truncated" }
        val initializationVector = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, initializationVector)
        )
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }

    /** Deletes the vault key — every entry encrypted with it becomes unrecoverable. */
    fun destroyKey() {
        runCatching { keyStore().deleteEntry(alias) }
        strongBoxUsed = false
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore().getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // StrongBox is optional: request it, and fall back to the TEE if absent.
            builder.setIsStrongBoxBacked(true)
        }

        return try {
            generator.init(builder.build())
            val key = generator.generateKey()
            strongBoxUsed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            key
        } catch (error: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                builder.setIsStrongBoxBacked(false)
            }
            generator.init(builder.build())
            strongBoxUsed = false
            generator.generateKey()
        }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    companion object {
        const val DEFAULT_ALIAS = "jarvis-vault-aes-gcm"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
    }
}

/**
 * Deterministic AES-GCM cipher with a caller-supplied key, for JVM unit tests.
 *
 * Not used by the app: a software key offers none of the hardware protections of
 * [AndroidKeystoreVaultCipher].
 */
class SoftwareVaultCipher(key: ByteArray = ByteArray(32) { index -> (index * 7 + 11).toByte() }) :
    VaultCipher {

    private val secretKey = SecretKeySpec(key, "AES")

    override val backend: String get() = "Software test key"

    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return cipher.iv + ciphertext
    }

    override fun open(payload: ByteArray, associatedData: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH_BYTES) { "Encrypted vault entry is truncated" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(TAG_LENGTH_BITS, payload.copyOfRange(0, IV_LENGTH_BYTES))
        )
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return cipher.doFinal(payload.copyOfRange(IV_LENGTH_BYTES, payload.size))
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val IV_LENGTH_BYTES = 12
    }
}
