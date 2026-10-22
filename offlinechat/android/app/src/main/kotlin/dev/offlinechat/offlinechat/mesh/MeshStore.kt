package dev.offlinechat.offlinechat.mesh

data class QueuedEnvelope(
    val packet: RoutedEnvelope,
    val encoded: ByteArray,
    val digest: ByteArray,
    val local: Boolean,
    val receivedAt: Long,
) {
    val id: String get() = packet.envelope.id
}

data class HistoryRow(
    val id: String,
    val contactId: String,
    val encryptedBody: ByteArray,
    val outgoing: Boolean,
    val createdAt: Long,
    val expiresAt: Long,
    val status: String,
    val digest: ByteArray,
)

data class InventoryEntry(
    val id: String,
    val digest: String,
    val recipientId: String,
    val expiresAt: Long,
    val hops: Int,
    val kind: String,
) {
    val token: String get() = "$id:$digest"
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id, "digest" to digest, "recipientId" to recipientId,
        "expiresAt" to expiresAt, "hops" to hops, "kind" to kind,
    )
}

enum class StoreCommit { STORED, DUPLICATE, CONFLICT, QUOTA }

data class Backoff(val failures: Int, val nextAllowedAt: Long)

/** Stores ciphertext and public metadata only. Transaction commit precedes custody. */
interface MeshStore {
    fun settings(): MeshSettings
    fun updateSettings(settings: MeshSettings)
    fun contacts(): List<PublicIdentity>
    fun contact(id: String): PublicIdentity?
    fun addContact(identity: PublicIdentity, verified: Boolean = true)
    fun envelope(id: String): QueuedEnvelope?
    fun seenDigest(id: String): ByteArray?
    fun queued(now: Long): List<QueuedEnvelope>
    fun commit(incoming: QueuedEnvelope, history: HistoryRow? = null,
               generated: List<QueuedEnvelope> = emptyList()): StoreCommit
    fun history(id: String): HistoryRow?
    fun historyRows(contactId: String? = null): List<HistoryRow>
    fun expiringHistory(now: Long): List<HistoryRow>
    fun replaceHistory(row: HistoryRow)
    fun recordCustody(id: String, peerId: String, now: Long): Boolean
    fun hasCustody(id: String, peerId: String): Boolean
    fun incrementForwarded()
    fun forwardedCount(): Long
    fun queueBytes(): Long
    fun backoff(peerId: String): Backoff
    fun setBackoff(peerId: String, backoff: Backoff)
    fun cleanup(now: Long)
    fun log(level: String, message: String, now: Long = System.currentTimeMillis())
    fun diagnostics(): List<Map<String, Any?>>
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
