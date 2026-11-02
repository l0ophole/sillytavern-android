package app.sillytavern.android.data

import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Symmetric encryption primitive; Android uses Keystore, tests use a plain AES key. */
interface SecretCrypto {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES-256-GCM. Blob layout: 12-byte IV followed by ciphertext+tag. */
class AesGcmCrypto(private val keyProvider: () -> SecretKey) : SecretCrypto {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    companion object {
        fun keystore(alias: String = "st_secrets_v1") = AesGcmCrypto {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey) ?: run {
                val gen = KeyGenerator.getInstance("AES", "AndroidKeyStore")
                gen.init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        alias,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                gen.generateKey()
            }
        }
    }
}

/** Encrypted key/value store for API keys (replaces SillyTavern's plaintext secrets.json). */
class SecretStore(private val file: File, private val crypto: SecretCrypto) {
    private val json = Json
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    @Synchronized
    fun get(key: String): String? =
        load()[key]?.let { String(crypto.decrypt(Base64.getDecoder().decode(it)), Charsets.UTF_8) }

    @Synchronized
    fun set(key: String, value: String) {
        val map = load().toMutableMap()
        map[key] = Base64.getEncoder().encodeToString(crypto.encrypt(value.toByteArray(Charsets.UTF_8)))
        save(map)
    }

    @Synchronized
    fun remove(key: String) {
        val map = load().toMutableMap()
        if (map.remove(key) != null) save(map)
    }

    @Synchronized
    fun has(key: String) = load().containsKey(key)

    private fun load(): Map<String, String> =
        if (file.exists()) runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())
        else emptyMap()

    private fun save(map: Map<String, String>) = file.writeAtomic(json.encodeToString(serializer, map))
}
