package dev.offlinechat.offlinechat.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.LinkedHashMap

data class ReceiveResult(
    val accepted: Boolean,
    val duplicate: Boolean = false,
    val delivered: Boolean = false,
    val rejectedReason: String? = null,
    val id: String = "",
    val digest: ByteArray = byteArrayOf(),
    val receiptId: String? = null,
)

/** Real encrypted routing; BLE custody never implies end-recipient delivery. */
class MeshRouter(
    val store: MeshStore,
    private val crypto: MeshCrypto,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Rate(var since: Long, var messages: Int, var bytes: Long)
    private val rates = object : LinkedHashMap<String, Rate>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Rate>?): Boolean = size > 128
    }

    @Synchronized
    fun sendText(recipientId: String, text: String): String {
        cleanup()
        require(text.isNotBlank()) { "Enter a message." }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024) { "Message limit is 1024 UTF-8 bytes." }
        val recipient = store.contact(recipientId)
            ?: throw IllegalArgumentException("Verify this contact's QR code before messaging.")
        require(recipient.id != crypto.identity().id) { "Choose another device as recipient." }
        val settings = store.settings().validated()
        val now = clock()
        val envelope = crypto.seal(recipient, EnvelopeKind.TEXT, bytes, now, settings.ttlSeconds, settings.maxHops)
        val packet = RoutedEnvelope(envelope)
        val digest = crypto.envelopeDigest(envelope)
        val history = historyRow(envelope, recipient.id, text, true, "pending", digest)
        when (store.commit(queued(packet, true, now), history)) {
            StoreCommit.STORED -> Unit
            StoreCommit.QUOTA -> throw IllegalStateException("Relay queue is full. Wait for expiry or increase its storage limit.")
            else -> throw IllegalStateException("Could not create a unique message; no message was queued.")
        }
        store.log("info", "Encrypted message queued: ${envelope.id}", now)
        return envelope.id
    }

    @Synchronized
    fun receive(packet: RoutedEnvelope, authenticatedPeerId: String): ReceiveResult {
        val now = clock()
        cleanup()
        val envelope = packet.envelope
        var digest = byteArrayOf()
        try {
            require(authenticatedPeerId.isNotBlank()) { "An authenticated peer session is required." }
            require(envelope.createdAt <= now + CLOCK_SKEW_MS) { "Message creation time is too far in the future." }
            require(envelope.expiresAt > now && envelope.expiresAt > envelope.createdAt) { "Message has expired." }
            require(envelope.expiresAt - envelope.createdAt <= MAX_TTL_MS) { "Message TTL exceeds seven days." }
            require(envelope.maxHops in 1..16) { "Invalid signed hop budget." }
            require(crypto.verifyEnvelope(envelope)) { "Invalid envelope signature or identity." }
            require(crypto.verifyRoute(packet, crypto.identity().id, authenticatedPeerId)) { "Invalid signed route or peer binding." }
            val encoded = crypto.encodePacket(packet)
            require(encoded.size <= MAX_PACKET_BYTES) { "Envelope exceeds the transfer size limit." }
            require(checkRate(authenticatedPeerId, encoded.size, now)) { "Peer receive rate limit reached; retry later." }
            digest = crypto.envelopeDigest(envelope)
            val seen = store.seenDigest(envelope.id)
            if (seen != null) {
                require(MessageDigest.isEqual(seen, digest)) { "Message ID conflicts with a different immutable envelope." }
                return ReceiveResult(true, duplicate = true, id = envelope.id, digest = digest)
            }
            val local = envelope.recipientId == crypto.identity().id
            if (!local) {
                require(store.settings().relayEnabled) { "Relay participation is disabled." }
                require(packet.route.size < envelope.maxHops) { "Hop budget is exhausted before reaching recipient." }
                return commitResult(store.commit(QueuedEnvelope(packet, encoded, digest, false, now)), envelope.id, digest)
            }
            val contact = store.contact(envelope.sender.id)
                ?: throw IllegalArgumentException("Sender is not a verified contact; exchange QR codes first.")
            require(sameKeys(contact, envelope.sender)) { "Sender identity does not match the verified contact." }
            val plaintext = crypto.open(envelope)
            if (envelope.kind == EnvelopeKind.TEXT) {
                require(plaintext.isNotEmpty() && plaintext.size <= 1024) { "Invalid plaintext message size." }
                val text = strictUtf8(plaintext)
                require(text.isNotBlank()) { "Empty message is not accepted." }
                val history = historyRow(envelope, contact.id, text, false, "delivered", digest)
                val receiptBody = ReceiptPayload(envelope.id, digest, now).encode()
                val settings = store.settings()
                val receipt = crypto.seal(contact, EnvelopeKind.RECEIPT, receiptBody, now, settings.ttlSeconds, settings.maxHops)
                val result = store.commit(
                    QueuedEnvelope(packet, encoded, digest, false, now), history,
                    listOf(queued(RoutedEnvelope(receipt), true, now)),
                )
                return commitResult(result, envelope.id, digest, delivered = true, receiptId = receipt.id)
            }
            val receipt = ReceiptPayload.decode(plaintext)
            val original = store.history(receipt.originalId)
                ?: throw IllegalArgumentException("Receipt does not refer to an outgoing message on this device.")
            require(original.outgoing && original.contactId == envelope.sender.id) { "Only the original recipient can acknowledge delivery." }
            // Validate the authenticated local history before trusting database metadata.
            HistoryPayload.decode(crypto.decryptLocal(original.encryptedBody), original)
            require(MessageDigest.isEqual(original.digest, receipt.originalDigest)) { "Receipt refers to a different envelope digest." }
            require(receipt.deliveredAt in original.createdAt..original.expiresAt && receipt.deliveredAt <= now + CLOCK_SKEW_MS) {
                "Receipt delivery time is outside the original message lifetime."
            }
            val updated = withStatus(original, "delivered")
            val result = store.commit(QueuedEnvelope(packet, encoded, digest, false, now), updated)
            return commitResult(result, envelope.id, digest, delivered = true)
        } catch (error: Exception) {
            val reason = error.message?.take(240) ?: error.javaClass.simpleName
            store.log("warning", "Envelope rejected: $reason", now)
            return ReceiveResult(false, rejectedReason = reason, id = envelope.id, digest = digest)
        }
    }

    @Synchronized
    fun inventory(limit: Int = 256, offset: Int = 0): List<InventoryEntry> {
        require(limit in 1..256 && offset >= 0) { "Invalid inventory page." }
        cleanup()
        return store.queued(clock()).asSequence().filter { validStored(it) }
            .sortedWith(priority(null)).drop(offset).take(limit)
            .map { record ->
                val e = record.packet.envelope
                InventoryEntry(e.id, record.digest.hex(), e.recipientId, e.expiresAt, record.packet.route.size, e.kind.name.lowercase())
            }.toList()
    }

    @Synchronized
    fun offers(peerId: String, remoteIds: Set<String>, limit: Int = 8): List<RoutedEnvelope> {
        require(limit in 1..32 && remoteIds.size <= 8192) { "Invalid inventory or offer batch." }
        cleanup()
        val now = clock()
        if (store.backoff(peerId).nextAllowedAt > now) return emptyList()
        return store.queued(now).asSequence()
            .filter { validStored(it) }
            .filter { it.local || store.settings().relayEnabled }
            .filter { it.packet.envelope.recipientId != crypto.identity().id }
            .filter { it.packet.route.size < it.packet.envelope.maxHops }
            .filter { !remoteIds.contains("${it.id}:${it.digest.hex()}") && !store.hasCustody(it.id, peerId) }
            .sortedWith(priority(peerId))
            .mapNotNull { record ->
                try { crypto.appendHop(record.packet, peerId) }
                catch (_: IllegalArgumentException) { null } // Visited peer/terminal recipient; never transmit a loop.
            }.take(limit).toList()
    }

    /** An authenticated hop receipt is custody only, never end-to-end delivery. */
    @Synchronized
    fun custody(id: String, peerId: String, digest: ByteArray): Boolean {
        val record = store.envelope(id) ?: return false
        if (!MessageDigest.isEqual(record.digest, digest)) return false
        val first = store.recordCustody(id, peerId, clock())
        if (first && !record.local) store.incrementForwarded()
        val history = store.history(id)
        if (history != null && history.outgoing && history.status == "pending") {
            store.replaceHistory(withStatus(history, "relayed"))
        }
        peerSucceeded(peerId)
        return true
    }

    @Synchronized
    fun peerFailed(peerId: String, reason: String) {
        val failures = (store.backoff(peerId).failures + 1).coerceAtMost(12)
        val delay = (2_000L shl (failures - 1)).coerceAtMost(300_000L)
        store.setBackoff(peerId, Backoff(failures, clock() + delay))
        store.log("warning", "Peer transfer deferred (${failures} failures): ${reason.take(160)}", clock())
    }

    @Synchronized
    fun peerSucceeded(peerId: String) = store.setBackoff(peerId, Backoff(0, 0))

    @Synchronized
    fun messages(contactId: String): List<Map<String, Any?>> {
        cleanup()
        return store.historyRows(contactId).map { row ->
            val payload = HistoryPayload.decode(crypto.decryptLocal(row.encryptedBody), row)
            mapOf("id" to row.id, "contactId" to row.contactId, "text" to payload.text,
                "outgoing" to row.outgoing, "time" to row.createdAt, "status" to row.status)
        }
    }

    @Synchronized
    fun conversations(): List<Map<String, Any?>> {
        cleanup()
        return store.historyRows().groupBy { it.contactId }.map { (contactId, rows) ->
            val last = rows.maxBy { it.createdAt }
            val body = HistoryPayload.decode(crypto.decryptLocal(last.encryptedBody), last)
            mapOf("contactId" to contactId, "name" to (store.contact(contactId)?.name ?: "Verified contact"),
                "lastText" to body.text, "lastAt" to last.createdAt, "unread" to 0)
        }.sortedByDescending { (it["lastAt"] as Number).toLong() }
    }

    @Synchronized
    fun cleanup() {
        val now = clock()
        store.cleanup(now)
        store.expiringHistory(now).forEach { store.replaceHistory(withStatus(it, "expired")) }
    }

    private fun checkRate(peer: String, bytes: Int, now: Long): Boolean {
        val rate = rates[peer]?.takeIf { now - it.since in 0 until 60_000 } ?: Rate(now, 0, 0).also { rates[peer] = it }
        if (rate.messages >= 64 || rate.bytes + bytes > 512 * 1024) return false
        rate.messages++; rate.bytes += bytes
        return true
    }

    private fun validStored(record: QueuedEnvelope): Boolean = try {
        crypto.verifyEnvelope(record.packet.envelope) &&
            MessageDigest.isEqual(crypto.envelopeDigest(record.packet.envelope), record.digest) &&
            crypto.verifyRoute(record.packet, crypto.identity().id, null)
    } catch (_: Exception) { false }

    private fun priority(peer: String?): Comparator<QueuedEnvelope> = compareBy<QueuedEnvelope>(
        { if (it.packet.envelope.recipientId == peer) 0 else 1 },
        { if (it.packet.envelope.kind == EnvelopeKind.RECEIPT) 0 else 1 },
        { it.packet.envelope.createdAt },
        { it.id },
    )

    private fun queued(packet: RoutedEnvelope, local: Boolean, now: Long) = QueuedEnvelope(
        packet, crypto.encodePacket(packet), crypto.envelopeDigest(packet.envelope), local, now,
    )

    private fun historyRow(e: SealedEnvelope, contact: String, text: String, outgoing: Boolean,
                           status: String, digest: ByteArray): HistoryRow {
        val bare = HistoryRow(e.id, contact, byteArrayOf(), outgoing, e.createdAt, e.expiresAt, status, digest)
        return bare.copy(encryptedBody = crypto.encryptLocal(HistoryPayload.encode(bare, text)))
    }

    private fun withStatus(row: HistoryRow, status: String): HistoryRow {
        val text = HistoryPayload.decode(crypto.decryptLocal(row.encryptedBody), row).text
        val changed = row.copy(status = status)
        return changed.copy(encryptedBody = crypto.encryptLocal(HistoryPayload.encode(changed, text)))
    }

    private fun commitResult(result: StoreCommit, id: String, digest: ByteArray,
                             delivered: Boolean = false, receiptId: String? = null): ReceiveResult = when (result) {
        StoreCommit.STORED -> ReceiveResult(true, delivered = delivered, id = id, digest = digest, receiptId = receiptId)
        StoreCommit.DUPLICATE -> ReceiveResult(true, duplicate = true, id = id, digest = digest)
        StoreCommit.CONFLICT -> ReceiveResult(false, rejectedReason = "Conflicting message ID.", id = id, digest = digest)
        StoreCommit.QUOTA -> ReceiveResult(false, rejectedReason = "Persistent queue capacity reached; custody was not acknowledged.", id = id, digest = digest)
    }

    companion object {
        const val CLOCK_SKEW_MS = 300_000L
        const val MAX_TTL_MS = 604_800_000L
        const val MAX_PACKET_BYTES = 65_536
        private fun sameKeys(a: PublicIdentity, b: PublicIdentity): Boolean = a.id == b.id &&
            MessageDigest.isEqual(a.signingKey, b.signingKey) && MessageDigest.isEqual(a.encryptionKey, b.encryptionKey)
        internal fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
}

/** Binds locally encrypted text and mutable status to all displayed row metadata. */
private data class HistoryPayload(val text: String) {
    companion object {
        fun encode(row: HistoryRow, text: String): ByteArray = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { stream ->
                stream.writeInt(0x4f4d4832); stream.writeUTF(row.id); stream.writeUTF(row.contactId)
                stream.writeBoolean(row.outgoing); stream.writeLong(row.createdAt); stream.writeLong(row.expiresAt)
                stream.writeUTF(row.status); stream.writeInt(row.digest.size); stream.write(row.digest)
                val bytes = text.toByteArray(Charsets.UTF_8); stream.writeInt(bytes.size); stream.write(bytes)
            }
        }.toByteArray()

        fun decode(bytes: ByteArray, row: HistoryRow): HistoryPayload {
            require(bytes.size <= 4096) { "Local history record is oversized." }
            DataInputStream(ByteArrayInputStream(bytes)).use { stream ->
                require(stream.readInt() == 0x4f4d4832 && stream.readUTF() == row.id && stream.readUTF() == row.contactId)
                require(stream.readBoolean() == row.outgoing && stream.readLong() == row.createdAt && stream.readLong() == row.expiresAt)
                require(stream.readUTF() == row.status && stream.readInt() == 32)
                val digest = ByteArray(32); stream.readFully(digest); require(MessageDigest.isEqual(digest, row.digest))
                val length = stream.readInt(); require(length in 1..1024)
                val text = ByteArray(length); stream.readFully(text); require(stream.available() == 0)
                return HistoryPayload(MeshRouter.strictUtf8(text))
            }
        }
    }
}

private data class ReceiptPayload(val originalId: String, val originalDigest: ByteArray, val deliveredAt: Long) {
    fun encode(): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { stream ->
            stream.writeInt(0x4f4d5232); stream.writeUTF(originalId); stream.write(originalDigest); stream.writeLong(deliveredAt)
        }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): ReceiptPayload {
            require(bytes.size in 46..128) { "Invalid delivery receipt size." }
            DataInputStream(ByteArrayInputStream(bytes)).use { stream ->
                require(stream.readInt() == 0x4f4d5232) { "Invalid delivery receipt version." }
                val id = stream.readUTF(); require(id.length in 1..64)
                val digest = ByteArray(32); stream.readFully(digest)
                val receipt = ReceiptPayload(id, digest, stream.readLong())
                require(stream.available() == 0) { "Trailing receipt data." }
                return receipt
            }
        }
    }
}
