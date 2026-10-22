package dev.offlinechat.offlinechat.mesh

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64

/** No AndroidKeysetManager fallback: encrypted keysets are mandatory, including on first use. */
internal class AndroidIdentityVault(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(createIfMissing: Boolean = false): IdentityKeyMaterial = synchronized(LOCK) {
        try {
            val stored = preferences.all
            val exists = stored.isNotEmpty()
            check(exists || createIfMissing) {
                "The identity vault is missing. Existing identity cannot be recovered."
            }
            if (exists) {
                require(preferences.getInt("version", -1) == VERSION && SLOTS.all(preferences::contains) &&
                    preferences.contains("name")) { "Incomplete or unsupported identity vault." }
            }
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(MASTER_ALIAS)) {
                check(!exists) { "AndroidKeyStore identity key is missing. Existing identity cannot be recovered." }
                AndroidKeystoreKmsClient.generateNewAeadKey(MASTER_URI)
            }
            // getAead performs a real encrypt/decrypt check. Any provider/invalidation failure stops initialization.
            val master = AndroidKeystoreKmsClient().getAead(MASTER_URI)
            if (exists) {
                IdentityKeyMaterial(read("signing", master), read("encryption", master), read("history", master),
                    EnvelopeCodec.checkedName(requireNotNull(preferences.getString("name", null))))
            } else {
                val material = IdentityCrypto.newMaterial("OfflineChat")
                val written = preferences.edit().putInt("version", VERSION).putString("name", material.name)
                    .putString("signing", encrypt("signing", material.signing, master))
                    .putString("encryption", encrypt("encryption", material.encryption, master))
                    .putString("history", encrypt("history", material.history, master)).commit()
                check(written) { "Could not persist the encrypted identity vault." }
                material
            }
        } catch (error: Exception) {
            throw GeneralSecurityException("Secure identity storage is unavailable; no replacement identity was created.", error)
        }
    }

    fun rename(name: String) = synchronized(LOCK) {
        EnvelopeCodec.checkedName(name)
        check(preferences.getInt("version", -1) == VERSION && SLOTS.all(preferences::contains)) { "Identity vault is unavailable." }
        check(preferences.edit().putString("name", name).commit()) { "Could not persist the display name." }
    }

    private fun read(slot: String, master: Aead): KeysetHandle {
        val text = requireNotNull(preferences.getString(slot, null))
        require(text.length in 1..MAX_ENCRYPTED_KEYSET_CHARS) { "Invalid encrypted keyset size." }
        return TinkProtoKeysetFormat.parseEncryptedKeyset(Base64.getDecoder().decode(text), master, context(slot))
    }

    private fun encrypt(slot: String, handle: KeysetHandle, master: Aead): String =
        Base64.getEncoder().encodeToString(TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, master, context(slot)))

    private fun context(slot: String): ByteArray = "offlinechat.android.identity.v1:$slot".toByteArray(StandardCharsets.US_ASCII)

    companion object {
        private val LOCK = Any()
        private const val PREFERENCES = "offlinechat_mesh_identity_encrypted_v1"
        private const val MASTER_ALIAS = "offlinechat_mesh_identity_master_v1"
        private const val MASTER_URI = "android-keystore://$MASTER_ALIAS"
        private const val VERSION = 1
        private const val MAX_ENCRYPTED_KEYSET_CHARS = 8192
        private val SLOTS = listOf("signing", "encryption", "history")
    }
}
