package assistant.mobile.android.whoop

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM authenticated ciphertext; keys never leave Android Keystore or Android backup. */
internal class WhoopCrypto(private val alias: String = "assistant.native.whoop.v1") {
    @Synchronized private fun key(create: Boolean = true): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw GeneralSecurityException("Encryption key is unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun seal(plain: ByteArray, context: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(context.toByteArray())
        return byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
    }
    fun open(value: ByteArray, context: String): ByteArray {
        require(value.size >= 30 && value[0].toInt() == 1 && value[1].toInt() == 12)
        return decrypt(value, context, key(create = false))
    }
    /** Capture an existing key only; delayed readers must never recreate a deleted account key. */
    @Synchronized fun captureReader(): Reader {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("Encryption key is unavailable")
        return Reader(existing)
    }
    internal class Reader internal constructor(private val key: SecretKey) {
        fun open(value: ByteArray, context: String): ByteArray {
            require(value.size >= 30 && value[0].toInt() == 1 && value[1].toInt() == 12)
            return decrypt(value, context, key)
        }
    }
    private companion object {
        fun decrypt(value: ByteArray, context: String, key: SecretKey): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(2, 14)))
            cipher.updateAAD(context.toByteArray())
            return cipher.doFinal(value.copyOfRange(14, value.size))
        }
    }
    fun delete() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
