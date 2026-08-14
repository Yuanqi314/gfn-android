package dev.gfn.android.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.gfn.auth.AuthTokens
import dev.gfn.auth.TokenStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * OAuth token 的 Android 本地安全存储。
 *
 * AES 密钥只存在 AndroidKeyStore，SharedPreferences 仅保存 AES-GCM 密文。
 */
class AndroidKeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    override suspend fun load(): AuthTokens? {
        val encoded = prefs.getString(KEY_BLOB, null) ?: return null
        return runCatching {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            require(blob.size > NONCE_BYTES)
            val nonce = blob.copyOfRange(0, NONCE_BYTES)
            val ciphertext = blob.copyOfRange(NONCE_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, nonce))
            decodeTokens(cipher.doFinal(ciphertext))
        }.getOrElse {
            // 当前项目尚处开发期；旧格式、损坏数据或 KeyStore 失效时直接清理，要求重新登录。
            prefs.edit().remove(KEY_BLOB).apply()
            null
        }
    }

    override suspend fun save(tokens: AuthTokens) {
        val plaintext = encodeTokens(tokens)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, nonce))
        val encrypted = cipher.doFinal(plaintext)
        val blob = nonce + encrypted
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override suspend fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encodeTokens(tokens: AuthTokens): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(STORAGE_VERSION)
            out.writeBoundedString(tokens.accessToken)
            out.writeNullableString(tokens.refreshToken)
            out.writeNullableString(tokens.idToken)
            out.writeLong(tokens.expiresAt.epochSecond)
            out.writeInt(tokens.expiresAt.nano)
            out.writeNullableString(tokens.clientToken)
            out.writeNullableInstant(tokens.clientTokenExpiresAt)
        }
        bytes.toByteArray()
    }

    private fun decodeTokens(bytes: ByteArray): AuthTokens = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == STORAGE_VERSION) { "不支持的 token 存储版本" }
        AuthTokens(
            accessToken = input.readBoundedString(),
            refreshToken = input.readNullableString(),
            idToken = input.readNullableString(),
            expiresAt = Instant.ofEpochSecond(input.readLong(), input.readInt().toLong()),
            clientToken = input.readNullableString(),
            clientTokenExpiresAt = input.readNullableInstant(),
        )
    }

    private fun DataOutputStream.writeBoundedString(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_FIELD_BYTES) { "token 字段过大" }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readBoundedString(): String {
        val length = readInt()
        require(length in 0..MAX_FIELD_BYTES) { "token 字段长度非法" }
        val data = ByteArray(length)
        readFully(data)
        return data.toString(Charsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeBoundedString(value)
    }

    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readBoundedString() else null

    private fun DataOutputStream.writeNullableInstant(value: Instant?) {
        writeBoolean(value != null)
        if (value != null) {
            writeLong(value.epochSecond)
            writeInt(value.nano)
        }
    }

    private fun DataInputStream.readNullableInstant(): Instant? = if (readBoolean()) {
        Instant.ofEpochSecond(readLong(), readInt().toLong())
    } else {
        null
    }

    private companion object {
        const val PREFS_NAME = "gfn_secure_auth"
        const val KEY_BLOB = "oauth_tokens"
        const val KEY_ALIAS = "gfn_android_oauth_aes_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val STORAGE_VERSION = 3
        const val MAX_FIELD_BYTES = 1_048_576
    }
}
