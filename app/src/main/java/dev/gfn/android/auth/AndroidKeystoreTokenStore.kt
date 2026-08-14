package dev.gfn.android.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dev.gfn.auth.AuthTokens
import dev.gfn.auth.TokenStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
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

    override suspend fun load(): AuthTokens? {
        val encoded = prefs.getString(KEY_BLOB, null) ?: run {
            Log.i(TAG, "CredentialRestore:BLOB_MISSING")
            return null
        }
        return runCatching {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val encryptedBlob = decodeEncryptedBlob(blob)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_BITS, encryptedBlob.iv),
            )
            decodeTokens(cipher.doFinal(encryptedBlob.ciphertext))
        }.onSuccess {
            Log.i(TAG, "CredentialRestore:OK")
        }.getOrElse { error ->
            // Keep current cleanup behavior unchanged; add reason-only diagnostics without token material.
            Log.w(TAG, "CredentialRestore:FAILED error=${error::class.simpleName}")
            Log.w(TAG, "CredentialCleanup:reason=RESTORE_FAILED")
            prefs.edit().remove(KEY_BLOB).apply()
            null
        }
    }

    override suspend fun save(tokens: AuthTokens) {
        val plaintext = encodeTokens(tokens)
        val cipher = Cipher.getInstance(TRANSFORMATION)

        // AndroidKeyStore + setRandomizedEncryptionRequired(true) 禁止调用方在加密时提供 IV。
        // 必须让 Keystore 生成随机 GCM IV，然后通过 cipher.iv 取回并与密文一起保存。
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext)
        val iv = cipher.iv ?: error("AndroidKeyStore 未返回 GCM IV")
        require(iv.isNotEmpty() && iv.size <= MAX_IV_BYTES) { "GCM IV 长度非法: ${iv.size}" }

        val blob = encodeEncryptedBlob(iv, encrypted)
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override suspend fun clear() {
        Log.i(TAG, "CredentialCleanup:reason=EXPLICIT_CLEAR")
        prefs.edit().remove(KEY_BLOB).apply()
    }


    private data class EncryptedBlob(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    private fun encodeEncryptedBlob(iv: ByteArray, ciphertext: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(BLOB_MAGIC)
                out.writeInt(BLOB_VERSION)
                out.writeInt(iv.size)
                out.write(iv)
                out.writeInt(ciphertext.size)
                out.write(ciphertext)
            }
            bytes.toByteArray()
        }

    private fun decodeEncryptedBlob(blob: ByteArray): EncryptedBlob {
        require(blob.isNotEmpty()) { "加密 token 数据为空" }

        // 兼容 v2/v3 的旧格式：固定 12-byte IV + ciphertext。
        if (blob.size >= Int.SIZE_BYTES) {
            val magic = DataInputStream(ByteArrayInputStream(blob)).use { it.readInt() }
            if (magic == BLOB_MAGIC) {
                return DataInputStream(ByteArrayInputStream(blob)).use { input ->
                    require(input.readInt() == BLOB_MAGIC) { "token blob magic 错误" }
                    require(input.readInt() == BLOB_VERSION) { "不支持的 token blob 版本" }
                    val ivLength = input.readInt()
                    require(ivLength in 1..MAX_IV_BYTES) { "GCM IV 长度非法" }
                    val iv = ByteArray(ivLength).also { input.readFully(it) }
                    val ciphertextLength = input.readInt()
                    require(ciphertextLength in 1..MAX_BLOB_BYTES) { "token 密文长度非法" }
                    val ciphertext = ByteArray(ciphertextLength).also { input.readFully(it) }
                    require(input.available() == 0) { "token blob 存在尾随数据" }
                    EncryptedBlob(iv = iv, ciphertext = ciphertext)
                }
            }
        }

        require(blob.size > LEGACY_NONCE_BYTES) { "旧版 token blob 长度非法" }
        return EncryptedBlob(
            iv = blob.copyOfRange(0, LEGACY_NONCE_BYTES),
            ciphertext = blob.copyOfRange(LEGACY_NONCE_BYTES, blob.size),
        )
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
        const val LEGACY_NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val STORAGE_VERSION = 3
        const val BLOB_MAGIC = 0x47464E34 // ASCII: GFN4
        const val BLOB_VERSION = 1
        const val MAX_IV_BYTES = 64
        const val MAX_BLOB_BYTES = 8 * 1_048_576
        const val MAX_FIELD_BYTES = 1_048_576
        const val TAG = "GfnCredential"
    }
}
