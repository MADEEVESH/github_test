package dev.offlinechat.offlinechat.mesh

import java.security.MessageDigest

/** Transactional test store. Crypto, packet validation and routing stay in the production router. */
internal class MemoryMeshStore(
    initialSettings: MeshSettings = MeshSettings(),
    var capacityBytes: Long? = null,
) : MeshStore {
    private data class Seen(val digest: ByteArray, val retainUntil: Long)

    private var currentSettings = initialSettings.validated()
    private val verifiedContacts = linkedMapOf<String, PublicIdentity>()
    private val envelopes = linkedMapOf<String, QueuedEnvelope>()
    private val seen = linkedMapOf<String, Seen>()
    private val histories = linkedMapOf<String, HistoryRow>()
    private val custody = linkedSetOf<Pair<String, String>>()
    private val backoffs = mutableMapOf<String, Backoff>()
    private val logs = ArrayDeque<Map<String, Any?>>()
    private var forwarded = 0L

    @Synchronized override fun settings(): MeshSettings = currentSettings

    @Synchronized override fun updateSettings(settings: MeshSettings) {
        currentSettings = settings.validated()
    }

    @Synchronized override fun contacts(): List<PublicIdentity> = verifiedContacts.values.toList()
    @Synchronized override fun contact(id: String): PublicIdentity? = verifiedContacts[id]

    @Synchronized override fun addContact(identity: PublicIdentity, verified: Boolean) {
        if (verified) verifiedContacts[identity.id] = identity else verifiedContacts.remove(identity.id)
    }

    @Synchronized override fun envelope(id: String): QueuedEnvelope? = envelopes[id]
    @Synchronized override fun seenDigest(id: String): ByteArray? = seen[id]?.digest?.copyOf()

    @Synchronized override fun queued(now: Long): List<QueuedEnvelope> =
        envelopes.values.filter { it.packet.envelope.expiresAt > now }

    @Synchronized override fun commit(
        incoming: QueuedEnvelope,
        history: HistoryRow?,
        generated: List<QueuedEnvelope>,
    ): StoreCommit {
        seen[incoming.id]?.let {
            return if (MessageDigest.isEqual(it.digest, incoming.digest)) StoreCommit.DUPLICATE else StoreCommit.CONFLICT
        }
        // Validate the entire transaction before mutating any queue, tombstone, or history row.
        val additions = linkedMapOf(incoming.id to incoming)
        for (record in generated) {
            val previous = additions[record.id]?.digest ?: seen[record.id]?.digest
            if (previous != null) {
                if (!MessageDigest.isEqual(previous, record.digest)) return StoreCommit.CONFLICT
                return StoreCommit.DUPLICATE
            }
            additions[record.id] = record
        }
        val bytes = additions.values.sumOf { it.encoded.size.toLong() }
        if (queueBytes() + bytes > (capacityBytes ?: currentSettings.queueLimitBytes)) return StoreCommit.QUOTA
        additions.values.forEach { record ->
            envelopes[record.id] = record
            seen[record.id] = Seen(record.digest.copyOf(), record.packet.envelope.expiresAt + MeshRouter.CLOCK_SKEW_MS)
        }
        if (history != null) histories[history.id] = history
        return StoreCommit.STORED
    }

    @Synchronized override fun history(id: String): HistoryRow? = histories[id]

    @Synchronized override fun historyRows(contactId: String?): List<HistoryRow> =
        histories.values.filter { contactId == null || it.contactId == contactId }.sortedBy { it.createdAt }

    @Synchronized override fun expiringHistory(now: Long): List<HistoryRow> =
        histories.values.filter { it.expiresAt <= now && it.status in setOf("pending", "relayed") }

    @Synchronized override fun replaceHistory(row: HistoryRow) {
        require(histories.containsKey(row.id))
        histories[row.id] = row
    }

    @Synchronized override fun recordCustody(id: String, peerId: String, now: Long): Boolean {
        require(envelopes.containsKey(id))
        return custody.add(id to peerId)
    }

    @Synchronized override fun hasCustody(id: String, peerId: String): Boolean = (id to peerId) in custody
    @Synchronized override fun incrementForwarded() { forwarded++ }
    @Synchronized override fun forwardedCount(): Long = forwarded
    @Synchronized override fun queueBytes(): Long = envelopes.values.sumOf { it.encoded.size.toLong() }
    @Synchronized override fun backoff(peerId: String): Backoff = backoffs[peerId] ?: Backoff(0, 0)
    @Synchronized override fun setBackoff(peerId: String, backoff: Backoff) { backoffs[peerId] = backoff }

    @Synchronized override fun cleanup(now: Long) {
        val expired = envelopes.values.filter { it.packet.envelope.expiresAt <= now }.map { it.id }.toSet()
        expired.forEach(envelopes::remove)
        custody.removeAll { it.first in expired }
        seen.entries.removeAll { it.value.retainUntil < now }
    }

    @Synchronized override fun log(level: String, message: String, now: Long) {
        logs.addLast(mapOf("time" to now, "level" to level, "message" to message))
        while (logs.size > 250) logs.removeFirst()
    }

    @Synchronized override fun diagnostics(): List<Map<String, Any?>> = logs.toList()

    /** Models a missing queue record while preserving its replay tombstone. */
    @Synchronized fun evictQueued(id: String) { envelopes.remove(id) }
}
