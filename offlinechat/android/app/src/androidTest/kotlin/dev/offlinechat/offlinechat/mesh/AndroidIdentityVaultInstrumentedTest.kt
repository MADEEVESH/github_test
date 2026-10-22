package dev.offlinechat.offlinechat.mesh

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.crypto.tink.proto.EncryptedKeyset
import com.google.crypto.tink.shaded.protobuf.ByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.UUID

/** Runs against the real device AndroidKeyStore, with isolated preferences and no key deletion. */
@RunWith(AndroidJUnit4::class)
class AndroidIdentityVaultInstrumentedTest {
    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    @Before fun isolatePreferences() {
        val prefix = "mesh_vault_test_" + UUID.randomUUID().toString()
        context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$prefix.$name", mode)
        }
        preferences = context.getSharedPreferences("offlinechat_mesh_identity_encrypted_v1", Context.MODE_PRIVATE)
    }

    @After fun clearOnlyTestPreferences() { preferences.edit().clear().commit() }

    @Test fun encryptedKeysPersistAndHistorySurvivesReloadAndRename() {
        val first = IdentityCrypto.forAndroid(context, createIfMissing = true)
        first.rename("Vault device")
        val identity = first.identity()
        val history = first.encryptLocal("history encrypted on device".toByteArray())
        val second = IdentityCrypto.forAndroid(context)
        assertEquals(identity.id, second.identity().id)
        assertEquals("Vault device", second.identity().name)
        assertArrayEquals("history encrypted on device".toByteArray(), second.decryptLocal(history))
        val document = "proof of persisted private signing key".toByteArray()
        assertTrue(second.verify(identity, document, first.sign(document)))
        for (slot in listOf("signing", "encryption", "history")) {
            val encoded = requireNotNull(preferences.getString(slot, null))
            val keyset = EncryptedKeyset.parseFrom(Base64.getDecoder().decode(encoded))
            assertFalse("Private keyset must be encrypted for $slot", keyset.encryptedKeyset.isEmpty)
        }
    }

    @Test fun changedCiphertextOrSwappedPurposeFailsWithoutCreatingNewIdentity() {
        IdentityCrypto.forAndroid(context, createIfMissing = true)
        val original = preferences.all.toMap()
        val signing = requireNotNull(preferences.getString("signing", null))
        val keyset = EncryptedKeyset.parseFrom(Base64.getDecoder().decode(signing))
        val changed = keyset.encryptedKeyset.toByteArray().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val bytes = EncryptedKeyset.newBuilder(keyset).setEncryptedKeyset(ByteString.copyFrom(changed)).build().toByteArray()
        assertTrue(preferences.edit().putString("signing", Base64.getEncoder().encodeToString(bytes)).commit())
        assertThrows(Exception::class.java) { IdentityCrypto.forAndroid(context) }
        assertEquals(original["history"], preferences.getString("history", null))
        assertEquals(original["encryption"], preferences.getString("encryption", null))
        assertTrue(preferences.edit().putString("signing", original["history"] as String).commit())
        assertThrows(Exception::class.java) { IdentityCrypto.forAndroid(context) }
        assertEquals(original["history"], preferences.getString("history", null))
    }

    @Test fun partialOrUnsupportedVaultFailsClosed() {
        IdentityCrypto.forAndroid(context, createIfMissing = true)
        val signing = preferences.getString("signing", null)
        assertTrue(preferences.edit().remove("history").commit())
        assertThrows(Exception::class.java) { IdentityCrypto.forAndroid(context) }
        assertFalse(preferences.contains("history"))
        assertEquals(signing, preferences.getString("signing", null))
        assertTrue(preferences.edit().putInt("version", 99).commit())
        assertThrows(Exception::class.java) { IdentityCrypto.forAndroid(context) }
        assertEquals(99, preferences.getInt("version", -1))
    }

    @Test fun completelyMissingVaultDoesNotReplaceIdentityOnNormalLoad() {
        assertThrows(Exception::class.java) { IdentityCrypto.forAndroid(context) }
        assertTrue(preferences.all.isEmpty())
        val existing = IdentityCrypto.forAndroid(context, createIfMissing = true).identity()
        assertTrue(preferences.edit().clear().commit())
        assertThrows(Exception::class.java) { IdentityCrypto.forAndroid(context) }
        assertTrue("A load failure must not create replacement keysets", preferences.all.isEmpty())
        assertTrue(existing.id.isNotEmpty())
    }
}
