package dev.offlinechat.offlinechat.mesh

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyStatus
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.hybrid.PredefinedHybridParameters
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.PredefinedSignatureParameters
import com.google.crypto.tink.signature.SignatureConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Tink HPKE/Ed25519 primitives and the authenticated v2 envelope/route rules. */
class IdentityCrypto private constructor(
    material: IdentityKeyMaterial,
    private val persistName: (String) -> Unit,
) : MeshCrypto {
    private val signer = material.signing.getPrimitive(PublicKeySign::class.java)
    private val decryptor = material.encryption.getPrimitive(HybridDecrypt::class.java)
    private val history = material.history.getPrimitive(Aead::class.java)
    private val signingPublic = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(material.signing.publicKeysetHandle)
    private val encryptionPublic = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(material.encryption.publicKeysetHandle)
    @Volatile private var currentIdentity = makeIdentity(material.name)
    private val historyContext = ("offlinechat.history.v2:" + currentIdentity.id).toByteArray(StandardCharsets.US_ASCII)

    override fun identity(): PublicIdentity = currentIdentity.snapshot()

    @Synchronized override fun rename(name: String) {
        val next = makeIdentity(EnvelopeCodec.checkedName(name))
        persistName(name) // A failed durable write must not change the in-memory identity.
        currentIdentity = next
    }

    override fun exportIdentity(): String = QR_PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(EnvelopeCodec.encodeIdentity(currentIdentity))

    override fun parseIdentity(qr: String): PublicIdentity {
        require(qr.startsWith(QR_PREFIX) && qr.length <= MAX_QR_CHARS) { "Invalid or oversized OfflineChat v2 identity QR." }
        val encoded = qr.substring(QR_PREFIX.length)
        require(encoded.isNotEmpty() && encoded.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
            "Invalid identity QR encoding."
        }
        val data = Base64.getUrlDecoder().decode(encoded)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(data) == encoded) { "Noncanonical identity QR encoding." }
        val identity = EnvelopeCodec.decodeIdentity(data)
        validatedIdentity(identity)
        return identity
    }

    override fun seal(recipient: PublicIdentity, kind: EnvelopeKind, plaintext: ByteArray,
                      now: Long, ttlSeconds: Long, maxHops: Int): SealedEnvelope {
        require(plaintext.size <= EnvelopeCodec.MAX_PLAINTEXT_BYTES) { "Message exceeds 1024 UTF-8 bytes." }
        require(ttlSeconds in 60..604_800 && now > 0 && maxHops in 1..16) { "Invalid message lifetime or hop budget." }
        val target = recipient.snapshot()
        val keys = validatedIdentity(target)
        val unsigned = SealedEnvelope(UUID.randomUUID().toString(), identity(), target.id, now,
            Math.addExact(now, Math.multiplyExact(ttlSeconds, 1000L)), maxHops, kind, byteArrayOf(), byteArrayOf())
        val encrypted = unsigned.copy(ciphertext = keys.encryption.encrypt(plaintext, EnvelopeCodec.envelopeHeader(unsigned)))
        return encrypted.copy(signature = signer.sign(EnvelopeCodec.envelopeUnsigned(encrypted)))
    }

    override fun open(envelope: SealedEnvelope): ByteArray {
        require(envelope.recipientId == currentIdentity.id) { "This envelope is addressed to another identity." }
        require(verifyEnvelope(envelope)) { "Invalid signed envelope." }
        return decryptor.decrypt(envelope.ciphertext, EnvelopeCodec.envelopeHeader(envelope)).also {
            require(it.size <= EnvelopeCodec.MAX_PLAINTEXT_BYTES) { "Decrypted message exceeds its limit." }
        }
    }

    override fun verifyEnvelope(envelope: SealedEnvelope): Boolean = valid {
        val keys = validatedIdentity(envelope.sender)
        require(envelope.signature.size in 1..128) { "Invalid envelope signature." }
        keys.signing.verify(envelope.signature, EnvelopeCodec.envelopeUnsigned(envelope))
    }

    override fun appendHop(packet: RoutedEnvelope, targetId: String): RoutedEnvelope {
        val local = identity()
        require(verifyRoute(packet, local.id)) { "Cannot forward an invalid route or a route addressed to another custodian." }
        require(EnvelopeCodec.validId(targetId) && targetId != local.id) { "Invalid next custodian." }
        require(packet.route.size < packet.envelope.maxHops) { "Signed hop budget exhausted." }
        val visited = mutableSetOf(packet.envelope.sender.id)
        packet.route.forEach { visited.add(it.toId) }
        require(targetId !in visited) { "A route cannot revisit a custodian." }
        val digest = envelopeDigest(packet.envelope)
        val previous = packet.route.lastOrNull()?.let { EnvelopeCodec.hopDigest(digest, it) }
            ?: EnvelopeCodec.routeSeed(digest)
        val hop = HopProof(local, targetId, packet.route.size + 1, previous, byteArrayOf())
        return packet.copy(route = packet.route + hop.copy(signature = signer.sign(EnvelopeCodec.hopUnsigned(digest, hop))))
    }

    override fun verifyRoute(packet: RoutedEnvelope, receiverId: String, authenticatedPeerId: String?): Boolean = valid {
        require(EnvelopeCodec.validId(receiverId) && verifyEnvelope(packet.envelope)) { "Invalid route envelope or receiver." }
        if (packet.route.isEmpty()) {
            require(authenticatedPeerId == null && receiverId == currentIdentity.id && receiverId == packet.envelope.sender.id) {
                "An incoming transfer must include its origin-signed first edge."
            }
        } else {
            require(packet.route.size <= packet.envelope.maxHops && packet.route.size <= 16) { "Signed hop budget exceeded." }
            val digest = envelopeDigest(packet.envelope)
            var previous = EnvelopeCodec.routeSeed(digest)
            var custodian = packet.envelope.sender.id
            val visited = mutableSetOf(custodian)
            packet.route.forEachIndexed { index, hop ->
                require(hop.from.id == custodian && hop.index == index + 1 &&
                    MessageDigest.isEqual(hop.previous, previous)) { "Broken signed route chain." }
                require(EnvelopeCodec.validId(hop.toId) && visited.add(hop.toId)) { "Route contains a loop." }
                val keys = validatedIdentity(hop.from)
                require(hop.signature.size in 1..128) { "Invalid route signature." }
                keys.signing.verify(hop.signature, EnvelopeCodec.hopUnsigned(digest, hop))
                previous = EnvelopeCodec.hopDigest(digest, hop)
                custodian = hop.toId
            }
            require(custodian == receiverId) { "Route targets another custodian." }
            require(authenticatedPeerId == null ||
                (EnvelopeCodec.validId(authenticatedPeerId) && packet.route.last().from.id == authenticatedPeerId)) {
                "Last signed edge does not match the authenticated transport peer."
            }
        }
    }

    override fun encodePacket(packet: RoutedEnvelope): ByteArray = EnvelopeCodec.encodePacket(packet)
    override fun decodePacket(bytes: ByteArray): RoutedEnvelope = EnvelopeCodec.decodePacket(bytes)
    override fun envelopeDigest(envelope: SealedEnvelope): ByteArray = EnvelopeCodec.digest(EnvelopeCodec.encodeEnvelope(envelope))

    override fun sign(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_SIGNED_BYTES) { "Signed document exceeds its limit." }
        return signer.sign(bytes)
    }

    override fun verify(identity: PublicIdentity, bytes: ByteArray, signature: ByteArray): Boolean = valid {
        require(bytes.size <= MAX_SIGNED_BYTES && signature.size in 1..128) { "Invalid signed document size." }
        validatedIdentity(identity).signing.verify(signature, bytes)
    }

    override fun encryptLocal(plaintext: ByteArray): ByteArray {
        require(plaintext.size <= EnvelopeCodec.MAX_PACKET_BYTES) { "Local history record exceeds its limit." }
        return history.encrypt(plaintext, historyContext)
    }

    override fun decryptLocal(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size in 1..EnvelopeCodec.MAX_PACKET_BYTES + 128) { "Invalid local history record size." }
        return history.decrypt(ciphertext, historyContext).also {
            require(it.size <= EnvelopeCodec.MAX_PACKET_BYTES) { "Local history record exceeds its limit." }
        }
    }

    private fun makeIdentity(name: String): PublicIdentity {
        val unsigned = PublicIdentity(EnvelopeCodec.fingerprint(signingPublic, encryptionPublic),
            EnvelopeCodec.checkedName(name), signingPublic.copyOf(), encryptionPublic.copyOf(), byteArrayOf())
        return unsigned.copy(signature = signer.sign(EnvelopeCodec.identityUnsigned(unsigned)))
    }

    private data class PublicKeys(val signing: PublicKeyVerify, val encryption: HybridEncrypt)

    private fun validatedIdentity(identity: PublicIdentity): PublicKeys {
        EnvelopeCodec.validateIdentityShape(identity)
        require(identity.id == EnvelopeCodec.fingerprint(identity.signingKey, identity.encryptionKey)) { "Identity fingerprint does not bind both keys." }
        require(identity.signature.size in 1..128) { "Missing identity self-signature." }
        val signing = TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.signingKey)
        val encryption = TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.encryptionKey)
        require(signing.size() == 1 && encryption.size() == 1 && signing.primary.status == KeyStatus.ENABLED &&
            encryption.primary.status == KeyStatus.ENABLED) { "Only one active public key per purpose is allowed." }
        require(signing.primary.key is Ed25519PublicKey &&
            signing.primary.key.parameters == PredefinedSignatureParameters.ED25519WithRawOutput &&
            encryption.primary.key is HpkePublicKey &&
            encryption.primary.key.parameters == PredefinedHybridParameters.HPKE_X25519_HKDF_SHA256_AES_128_GCM) {
            "Identity does not use the required Ed25519 and X25519 HPKE keys."
        }
        require(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(signing).contentEquals(identity.signingKey) &&
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(encryption).contentEquals(identity.encryptionKey)) { "Noncanonical public keyset." }
        val verifier = signing.getPrimitive(PublicKeyVerify::class.java)
        verifier.verify(identity.signature, EnvelopeCodec.identityUnsigned(identity))
        return PublicKeys(verifier, encryption.getPrimitive(HybridEncrypt::class.java))
    }

    private fun valid(action: () -> Unit): Boolean = try { action(); true } catch (_: Exception) { false }

    companion object {
        private const val QR_PREFIX = "offlinechat:v2:"
        private const val MAX_QR_CHARS = 2800
        private const val MAX_SIGNED_BYTES = 64 * 1024

        init {
            AeadConfig.register()
            SignatureConfig.register()
            HybridConfig.register()
        }

        @JvmStatic fun forAndroid(context: Context, createIfMissing: Boolean = false): MeshCrypto {
            val vault = AndroidIdentityVault(context.applicationContext)
            return IdentityCrypto(vault.load(createIfMissing), vault::rename)
        }

        /** Real ephemeral Tink keys, intentionally never persisted. */
        @JvmStatic fun forTesting(name: String): MeshCrypto = IdentityCrypto(newMaterial(name)) { }

        internal fun newMaterial(name: String): IdentityKeyMaterial = IdentityKeyMaterial(
            KeysetHandle.generateNew(PredefinedSignatureParameters.ED25519WithRawOutput),
            KeysetHandle.generateNew(PredefinedHybridParameters.HPKE_X25519_HKDF_SHA256_AES_128_GCM),
            KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM),
            EnvelopeCodec.checkedName(name),
        )
    }
}

internal data class IdentityKeyMaterial(
    val signing: KeysetHandle,
    val encryption: KeysetHandle,
    val history: KeysetHandle,
    val name: String,
)

private fun PublicIdentity.snapshot(): PublicIdentity = copy(
    signingKey = signingKey.copyOf(), encryptionKey = encryptionKey.copyOf(), signature = signature.copyOf(),
)
