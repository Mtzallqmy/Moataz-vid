package com.moatazvid.ai.provider.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.moatazvid.ai.provider.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts provider keys with a non-exportable Android Keystore AES key. Ciphertext stays in app-private preferences. */
class AndroidKeystoreSecretStore(
    private val blobs: EncryptedSecretBlobStore,
    private val keyAlias: String = "moataz_vid_provider_secrets_v1",
) : SecretStore {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override suspend fun saveSecret(providerId: ProviderId, value: CharArray): String {
        val bytes = value.concatToString().encodeToByteArray()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            blobs.put(providerId.value, cipher.iv, cipher.doFinal(bytes))
            return "keystore:${providerId.value}"
        } finally { bytes.fill(0); value.fill('\u0000') }
    }
    override suspend fun readSecret(providerId: ProviderId): SecretValue? {
        val blob = blobs.get(providerId.value) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.iv)) }
        val clear = cipher.doFinal(blob.ciphertext)
        return try { SecretValue(clear.decodeToString().toCharArray()) } finally { clear.fill(0) }
    }
    override suspend fun deleteSecret(providerId: ProviderId): Boolean = blobs.delete(providerId.value)

    private fun key(): SecretKey = (keyStore.getKey(keyAlias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        generateKey()
    }
}

data class EncryptedSecretBlob(val iv: ByteArray, val ciphertext: ByteArray)
interface EncryptedSecretBlobStore {
    suspend fun put(providerId: String, iv: ByteArray, ciphertext: ByteArray)
    suspend fun get(providerId: String): EncryptedSecretBlob?
    suspend fun delete(providerId: String): Boolean
}
