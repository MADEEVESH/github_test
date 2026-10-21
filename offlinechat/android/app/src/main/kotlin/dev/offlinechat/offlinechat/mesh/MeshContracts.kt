package dev.offlinechat.offlinechat.mesh

/** Native Android mesh v2 contracts. All times are epoch milliseconds. */
data class PublicIdentity(
    val id: String,
    val name: String,
    val signingKey: ByteArray,
    val encryptionKey: ByteArray,
    val signature: ByteArray,
)

enum class EnvelopeKind { TEXT, RECEIPT }

data class SealedEnvelope(
    val id: String,
    val sender: PublicIdentity,
    val recipientId: String,
    val createdAt: Long,
    val expiresAt: Long,
    val maxHops: Int,
    val kind: EnvelopeKind,
    val ciphertext: ByteArray,
    val signature: ByteArray,
)

/** A signed edge binds each transfer to its intended next custodian. */
data class HopProof(
    val from: PublicIdentity,
    val toId: String,
    val index: Int,
    val previous: ByteArray,
    val signature: ByteArray,
)

data class RoutedEnvelope(val envelope: SealedEnvelope, val route: List<HopProof> = emptyList())

data class MeshSettings(
    val relayEnabled: Boolean = true,
    val maxHops: Int = 8,
    val ttlSeconds: Long = 86_400,
    val queueLimitBytes: Long = 25L * 1024 * 1024,
) {
    fun validated(): MeshSettings {
        require(maxHops in 1..16) { "Hop limit must be between 1 and 16." }
        require(ttlSeconds in 60..604_800) { "TTL must be between one minute and seven days." }
        require(queueLimitBytes in 1_048_576L..104_857_600L) { "Queue must be between 1 and 100 MB." }
        return this
    }
}

interface MeshCrypto {
    fun identity(): PublicIdentity
    fun rename(name: String)
    fun exportIdentity(): String
    fun parseIdentity(qr: String): PublicIdentity
    fun seal(recipient: PublicIdentity, kind: EnvelopeKind, plaintext: ByteArray,
             now: Long, ttlSeconds: Long, maxHops: Int): SealedEnvelope
    fun open(envelope: SealedEnvelope): ByteArray
    fun verifyEnvelope(envelope: SealedEnvelope): Boolean
    fun appendHop(packet: RoutedEnvelope, targetId: String): RoutedEnvelope
    fun verifyRoute(packet: RoutedEnvelope, receiverId: String, authenticatedPeerId: String? = null): Boolean
    fun encodePacket(packet: RoutedEnvelope): ByteArray
    fun decodePacket(bytes: ByteArray): RoutedEnvelope
    fun envelopeDigest(envelope: SealedEnvelope): ByteArray
    fun sign(bytes: ByteArray): ByteArray
    fun verify(identity: PublicIdentity, bytes: ByteArray, signature: ByteArray): Boolean
    fun encryptLocal(plaintext: ByteArray): ByteArray
    fun decryptLocal(ciphertext: ByteArray): ByteArray
}

/** Bounded SQLite transport journal; append succeeds only after durable write. */
interface FrameJournal {
    fun recordInbound(peer: String, transferId: Long, total: Int, sequence: Int, chunk: ByteArray): Boolean
    fun readInbound(peer: String, transferId: Long): List<ByteArray>
    fun clearInbound(peer: String, transferId: Long)
    fun recordOutbound(peer: String, transferId: Long, payload: ByteArray, total: Int, ackedThrough: Int)
    fun ackOutbound(peer: String, transferId: Long, ackedThrough: Int)
    fun clearOutbound(peer: String, transferId: Long)
    fun cleanupTransfers(now: Long)
}

interface MeshTransport {
    interface Listener {
        fun onPeerReady(address: String)
        fun onPeerDisconnected(address: String, reason: String)
        fun onPayload(address: String, payload: ByteArray)
        fun onState(state: Map<String, Any?>)
        fun onFailure(address: String?, reason: String)
    }
    var listener: Listener?
    fun start()
    fun stop()
    /** Queues real BLE transfer or throws; no simulated success. */
    fun send(address: String, payload: ByteArray)
    fun disconnect(address: String)
    fun capabilities(): Map<String, Any?>
}
