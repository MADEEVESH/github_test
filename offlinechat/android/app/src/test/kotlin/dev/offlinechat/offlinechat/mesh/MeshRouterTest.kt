package dev.offlinechat.offlinechat.mesh

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Router integration tests use real HPKE/Ed25519 identities, signatures and encrypted storage. */
class MeshRouterTest {
    private var now = 1_700_000_000_000L

    private inner class Node(name: String, settings: MeshSettings = MeshSettings()) {
        val crypto = IdentityCrypto.forTesting(name)
        val store = MemoryMeshStore(settings)
        val id get() = crypto.identity().id
        var router = MeshRouter(store, crypto) { now }
        fun restartRouter() { router = MeshRouter(store, crypto) { now } }
    }

    private fun trust(a: Node, b: Node) {
        a.store.addContact(b.crypto.identity())
        b.store.addContact(a.crypto.identity())
    }

    private fun offer(from: Node, to: Node, id: String? = null): RoutedEnvelope =
        from.router.offers(to.id, emptySet()).single { id == null || it.envelope.id == id }

    private fun status(node: Node, contact: Node, id: String): Any? =
        node.router.messages(contact.id).single { it["id"] == id }["status"]

    @Test fun encryptedMessageCrossesUntrustedRelayAndOnlyRecipientReceiptMeansDelivered() {
        val a = Node("Alice"); val b = Node("Bob relay"); val c = Node("Carol")
        trust(a, c)
        val text = "private route content \uD83D\uDD12"
        val id = a.router.sendText(c.id, text)
        val firstHop = offer(a, b, id)
        val heldByB = b.router.receive(firstHop, a.id)
        assertTrue(heldByB.accepted)
        assertFalse(heldByB.delivered)
        assertNull(b.store.contact(a.id))
        assertTrue(b.store.historyRows().isEmpty())
        assertFalse(containsBytes(b.store.envelope(id)!!.encoded, text.toByteArray()))
        assertThrows(Exception::class.java) { b.crypto.open(firstHop.envelope) }

        assertTrue(a.router.custody(id, b.id, heldByB.digest))
        assertEquals("relayed", status(a, c, id))
        assertEquals(0L, a.store.forwardedCount())
        val finalHop = offer(b, c, id)
        assertEquals(2, finalHop.route.size)
        val delivered = c.router.receive(finalHop, b.id)
        assertTrue(delivered.accepted)
        assertTrue(delivered.delivered)
        assertNotNull(delivered.receiptId)
        assertEquals(text, c.router.messages(a.id).single()["text"])
        assertFalse(containsBytes(c.store.history(id)!!.encryptedBody, text.toByteArray()))
        assertEquals("relayed", status(a, c, id))

        assertTrue(b.router.custody(id, c.id, delivered.digest))
        assertEquals(1L, b.store.forwardedCount())
        assertTrue(b.router.custody(id, c.id, delivered.digest))
        assertEquals(1L, b.store.forwardedCount())
        val receiptToB = offer(c, b, delivered.receiptId)
        assertEquals(EnvelopeKind.RECEIPT, receiptToB.envelope.kind)
        assertTrue(b.router.receive(receiptToB, c.id).accepted)
        assertTrue(b.store.historyRows().isEmpty())
        val receiptToA = offer(b, a, delivered.receiptId)
        val acknowledged = a.router.receive(receiptToA, b.id)
        assertTrue(acknowledged.accepted)
        assertTrue(acknowledged.delivered)
        assertEquals("delivered", status(a, c, id))
        assertEquals(text, a.router.messages(c.id).single()["text"])
    }

    @Test fun delayedEncounterUsesStoredCiphertextAfterRouterRestart() {
        val a = Node("Alice"); val b = Node("Relay"); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "wait until the next encounter")
        assertTrue(b.router.receive(offer(a, b), a.id).accepted)
        val originalBytes = b.store.envelope(id)!!.encoded.copyOf()
        now += 30 * 60 * 1000L
        b.restartRouter()
        val later = offer(b, c)
        assertArrayEquals(originalBytes, b.store.envelope(id)!!.encoded)
        assertEquals(id, later.envelope.id)
        assertTrue(c.router.receive(later, b.id).delivered)
        assertEquals("wait until the next encounter", c.router.messages(a.id).single()["text"])
    }

    @Test fun recipientsShortTtlDoesNotExpireReceiptBeforeDelayedReturnEncounter() {
        val a = Node("Alice"); val b = Node("Relay"); val c = Node("Carol", MeshSettings(ttlSeconds = 60))
        trust(a, c)
        val id = a.router.sendText(c.id, "the return route may take longer than one minute")
        val originalExpiry = a.store.envelope(id)!!.packet.envelope.expiresAt
        assertTrue(b.router.receive(offer(a, b), a.id).accepted)
        val delivery = c.router.receive(offer(b, c), b.id)
        assertTrue(delivery.delivered)
        val receipt = c.store.envelope(delivery.receiptId!!)!!.packet.envelope
        assertTrue(receipt.expiresAt >= originalExpiry)
        assertEquals(60L, c.store.settings().ttlSeconds)

        now += 61_000
        c.restartRouter()
        val toB = offer(c, b, delivery.receiptId)
        assertEquals(EnvelopeKind.RECEIPT, toB.envelope.kind)
        assertTrue(b.router.receive(toB, c.id).accepted)
        val acknowledged = a.router.receive(offer(b, a, delivery.receiptId), b.id)
        assertTrue(acknowledged.accepted)
        assertTrue(acknowledged.delivered)
        assertEquals("delivered", status(a, c, id))
    }

    @Test fun allowedSenderClockSkewProducesReceiptValidWhenClocksAlign() {
        val a = Node("Alice"); val c = Node("Carol")
        trust(a, c)
        val recipientTime = now
        now += 60_000
        val id = a.router.sendText(c.id, "sender clock is one minute ahead")
        val packet = offer(a, c)
        val sourceTime = now

        now = recipientTime
        val delivery = c.router.receive(packet, a.id)
        assertTrue(delivery.accepted)
        assertTrue(delivery.delivered)
        val receipt = offer(c, a, delivery.receiptId)
        assertEquals(recipientTime, receipt.envelope.createdAt)
        now = sourceTime
        val acknowledged = a.router.receive(receipt, c.id)
        assertTrue(acknowledged.accepted)
        assertTrue(acknowledged.delivered)
        assertEquals("delivered", status(a, c, id))
    }

    @Test fun expiryRemovesQueueAndRetainsTombstoneWhileHistoryBecomesExpired() {
        val a = Node("Alice", MeshSettings(ttlSeconds = 60)); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "short lived")
        val packet = offer(a, c)
        val digest = a.store.seenDigest(id)
        now += 60_001
        a.router.cleanup()
        assertEquals(0L, a.store.queueBytes())
        assertTrue(a.router.inventory().isEmpty())
        assertTrue(a.router.offers(c.id, emptySet()).isEmpty())
        assertArrayEquals(digest, a.store.seenDigest(id))
        assertEquals("expired", status(a, c, id))
        val rejected = c.router.receive(packet, a.id)
        assertFalse(rejected.accepted)
        assertTrue(rejected.rejectedReason!!.contains("expired"))
        assertTrue(c.store.historyRows().isEmpty())
        now += MeshRouter.CLOCK_SKEW_MS + 1
        a.router.cleanup()
        assertNull(a.store.seenDigest(id))
    }

    @Test fun signedHopBudgetAllowsOneDirectHopButNoIntermediateRelay() {
        val a = Node("Alice", MeshSettings(maxHops = 1)); val b = Node("Relay"); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "one hop only")
        val rejected = b.router.receive(offer(a, b), a.id)
        assertFalse(rejected.accepted)
        assertTrue(rejected.rejectedReason!!.contains("Hop budget"))
        assertNull(b.store.envelope(id))
        val direct = c.router.receive(offer(a, c), a.id)
        assertTrue(direct.delivered)
    }

    @Test fun relayCannotOfferBackToVisitedNodeOrResetRoute() {
        val a = Node("Alice"); val b = Node("Relay"); val c = Node("Carol")
        trust(a, c)
        a.router.sendText(c.id, "no loops")
        val packet = offer(a, b)
        assertTrue(b.router.receive(packet, a.id).accepted)
        assertTrue(b.router.offers(a.id, emptySet()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { b.crypto.appendHop(packet, a.id) }
        val reset = RoutedEnvelope(packet.envelope)
        assertFalse(c.router.receive(reset, b.id).accepted)
        assertTrue(c.store.historyRows().isEmpty())
    }

    @Test fun authenticatedTransportPeerMustMatchLastSignedHop() {
        val a = Node("Alice"); val b = Node("Impostor"); val c = Node("Carol")
        trust(a, c)
        a.router.sendText(c.id, "bound to authenticated peer")
        val result = c.router.receive(offer(a, c), b.id)
        assertFalse(result.accepted)
        assertTrue(c.store.queued(now).isEmpty())
        assertTrue(c.store.historyRows().isEmpty())
    }

    @Test fun relayOptOutStillAcceptsMessagesAddressedToThisDevice() {
        val a = Node("Alice"); val b = Node("Relay off", MeshSettings(relayEnabled = false)); val c = Node("Carol")
        trust(a, b); trust(a, c)
        val relayedId = a.router.sendText(c.id, "requires relay")
        val refused = b.router.receive(offer(a, b, relayedId), a.id)
        assertFalse(refused.accepted)
        assertTrue(refused.rejectedReason!!.contains("disabled"))
        assertNull(b.store.seenDigest(relayedId))
        val directId = a.router.sendText(b.id, "for you")
        assertTrue(b.router.receive(offer(a, b, directId), a.id).delivered)
        assertEquals("for you", b.router.messages(a.id).single()["text"])
        val receipt = b.router.offers(a.id, emptySet()).single()
        assertEquals(EnvelopeKind.RECEIPT, receipt.envelope.kind)
    }

    @Test fun disablingRelayAfterCustodySuppressesForwardingWithoutDeletingQueue() {
        val a = Node("Alice"); val b = Node("Relay"); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "hold this")
        assertTrue(b.router.receive(offer(a, b), a.id).accepted)
        b.store.updateSettings(b.store.settings().copy(relayEnabled = false))
        assertTrue(b.router.offers(c.id, emptySet()).isEmpty())
        assertNotNull(b.store.envelope(id))
        b.store.updateSettings(b.store.settings().copy(relayEnabled = true))
        assertEquals(id, offer(b, c).envelope.id)
    }

    @Test fun receiptSignedByAnotherVerifiedContactCannotAcknowledgeRecipient() {
        val a = Node("Alice"); val c = Node("Carol"); val mallory = Node("Mallory")
        trust(a, c); trust(a, mallory)
        val id = a.router.sendText(c.id, "recipient-bound")
        val digest = a.store.history(id)!!.digest
        val forged = receipt(mallory, a, id, digest, now)
        assertTrue(a.crypto.verifyEnvelope(forged.envelope))
        val rejected = a.router.receive(forged, mallory.id)
        assertFalse(rejected.accepted)
        assertTrue(rejected.rejectedReason!!.contains("original recipient"))
        assertEquals("pending", status(a, c, id))
        assertNull(a.store.seenDigest(forged.envelope.id))
    }

    @Test fun recipientSignatureCannotAcknowledgeDifferentImmutableDigest() {
        val a = Node("Alice"); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "digest-bound")
        val wrongDigest = a.store.history(id)!!.digest.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val rejected = a.router.receive(receipt(c, a, id, wrongDigest, now), c.id)
        assertFalse(rejected.accepted)
        assertTrue(rejected.rejectedReason!!.contains("digest"))
        assertEquals("pending", status(a, c, id))
    }

    @Test fun recipientCannotClaimDeliveryOutsideOriginalLifetime() {
        val a = Node("Alice", MeshSettings(ttlSeconds = 60)); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "time-bound")
        val history = a.store.history(id)!!
        val rejected = a.router.receive(receipt(c, a, id, history.digest, history.expiresAt + 1), c.id)
        assertFalse(rejected.accepted)
        assertEquals("pending", status(a, c, id))
    }

    @Test fun duplicateDeliveryDoesNotDuplicateHistoryOrGenerateAnotherReceipt() {
        val a = Node("Alice"); val c = Node("Carol")
        trust(a, c)
        a.router.sendText(c.id, "exactly once")
        val packet = offer(a, c)
        val first = c.router.receive(packet, a.id)
        val bytes = c.store.queueBytes()
        val duplicate = c.router.receive(packet, a.id)
        assertTrue(first.delivered)
        assertTrue(duplicate.accepted)
        assertTrue(duplicate.duplicate)
        assertFalse(duplicate.delivered)
        assertNull(duplicate.receiptId)
        assertEquals(bytes, c.store.queueBytes())
        assertEquals(1, c.router.messages(a.id).size)
        assertEquals(1, c.store.queued(now).count { it.packet.envelope.kind == EnvelopeKind.RECEIPT })
        c.store.evictQueued(packet.envelope.id)
        assertTrue(c.router.receive(packet, a.id).duplicate)
        assertNull(c.store.envelope(packet.envelope.id))
    }

    @Test fun conflictingSignedEnvelopeIdDoesNotOverwriteAcceptedCiphertext() {
        val a = Node("Alice"); val b = Node("Relay"); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "original")
        val original = offer(a, b)
        assertTrue(b.router.receive(original, a.id).accepted)
        val different = a.crypto.seal(c.crypto.identity(), EnvelopeKind.TEXT, "different".toByteArray(), now, 86_400, 8)
        val unsigned = different.copy(id = id, signature = byteArrayOf())
        val resigned = unsigned.copy(signature = a.crypto.sign(EnvelopeCodec.envelopeUnsigned(unsigned)))
        assertTrue(a.crypto.verifyEnvelope(resigned))
        val conflicting = a.crypto.appendHop(RoutedEnvelope(resigned), b.id)
        val rejected = b.router.receive(conflicting, a.id)
        assertFalse(rejected.accepted)
        assertTrue(rejected.rejectedReason!!.contains("conflict", ignoreCase = true))
        assertArrayEquals(a.crypto.encodePacket(original), b.store.envelope(id)!!.encoded)
        assertArrayEquals(a.crypto.envelopeDigest(original.envelope), b.store.seenDigest(id))
        assertEquals(1, b.store.queued(now).size)
    }

    @Test fun fullRelayQueueRejectsCustodyAndDoesNotCreateTombstone() {
        val a = Node("Alice"); val b = Node("Relay"); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "queue full")
        val packet = offer(a, b)
        b.store.capacityBytes = b.crypto.encodePacket(packet).size.toLong() - 1
        val rejected = b.router.receive(packet, a.id)
        assertFalse(rejected.accepted)
        assertTrue(rejected.rejectedReason!!.contains("capacity"))
        assertEquals(0L, b.store.queueBytes())
        assertNull(b.store.seenDigest(id))
        assertTrue(b.store.historyRows().isEmpty())
        b.store.capacityBytes = null
        assertTrue(b.router.receive(packet, a.id).accepted)
    }

    @Test fun messageHistoryAndGeneratedReceiptCommitAtomicallyUnderQuota() {
        val a = Node("Alice"); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "atomic recipient commit")
        val packet = offer(a, c)
        // Enough for the incoming packet alone, insufficient for incoming plus generated receipt.
        c.store.capacityBytes = c.crypto.encodePacket(packet).size.toLong()
        val rejected = c.router.receive(packet, a.id)
        assertFalse(rejected.accepted)
        assertNull(rejected.receiptId)
        assertNull(c.store.history(id))
        assertNull(c.store.seenDigest(id))
        assertEquals(0L, c.store.queueBytes())
        c.store.capacityBytes = null
        val retried = c.router.receive(packet, a.id)
        assertTrue(retried.delivered)
        assertEquals(2, c.store.queued(now).size)
        assertEquals(1, c.router.messages(a.id).size)
    }

    @Test fun outgoingQuotaFailureCreatesNeitherHistoryNorQueue() {
        val a = Node("Alice"); val c = Node("Carol")
        trust(a, c)
        a.store.capacityBytes = 0
        assertThrows(IllegalStateException::class.java) { a.router.sendText(c.id, "no space") }
        assertTrue(a.store.historyRows().isEmpty())
        assertEquals(0L, a.store.queueBytes())
    }

    @Test fun custodyIsDigestBoundAndCurrentInventoryAllowsRetryAfterRemoteDataLoss() {
        val a = Node("Alice"); val b = Node("Relay"); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "recover after remote data loss")
        val packet = offer(a, b)
        val result = b.router.receive(packet, a.id)
        assertTrue(result.accepted)
        assertFalse(a.router.custody(id, b.id, ByteArray(32)))
        assertEquals("pending", status(a, c, id))
        assertTrue(a.router.custody(id, b.id, result.digest))
        assertEquals("relayed", status(a, c, id))
        val inventory = b.router.inventory().map { it.token }.toSet()
        assertTrue(a.router.offers(b.id, inventory).isEmpty())
        b.store.evictQueued(id)
        assertTrue(b.router.inventory().isEmpty())
        // Historical custody must not permanently suppress reconciliation with fresh inventory.
        assertEquals(id, a.router.offers(b.id, emptySet()).single().envelope.id)
    }

    @Test fun peerBackoffPersistsAcrossRouterRestartAndSuccessResetsIt() {
        val a = Node("Alice"); val c = Node("Carol")
        trust(a, c)
        a.router.sendText(c.id, "retry later")
        a.router.peerFailed(c.id, "disconnected")
        assertEquals(Backoff(1, now + 2_000), a.store.backoff(c.id))
        a.restartRouter()
        assertTrue(a.router.offers(c.id, emptySet()).isEmpty())
        now += 2_000
        assertEquals(1, a.router.offers(c.id, emptySet()).size)
        a.router.peerFailed(c.id, "again")
        assertEquals(Backoff(2, now + 4_000), a.store.backoff(c.id))
        a.router.peerSucceeded(c.id)
        assertEquals(Backoff(0, 0), a.store.backoff(c.id))
        assertEquals(1, a.router.offers(c.id, emptySet()).size)
    }

    @Test fun unverifiedContactCannotSendOrDecryptIncomingMessageIntoHistory() {
        val a = Node("Alice"); val c = Node("Carol")
        a.store.addContact(c.crypto.identity(), verified = false)
        assertThrows(IllegalArgumentException::class.java) { a.router.sendText(c.id, "not verified") }
        a.store.addContact(c.crypto.identity())
        a.router.sendText(c.id, "Carol has not verified Alice")
        val rejected = c.router.receive(offer(a, c), a.id)
        assertFalse(rejected.accepted)
        assertTrue(rejected.rejectedReason!!.contains("verified contact"))
        assertTrue(c.store.historyRows().isEmpty())
        assertEquals(0L, c.store.queueBytes())
    }

    @Test fun locallyEncryptedHistoryAuthenticatesDisplayedMetadata() {
        val a = Node("Alice"); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "status is authenticated")
        val original = a.store.history(id)!!
        a.store.replaceHistory(original.copy(status = "delivered"))
        assertThrows(Exception::class.java) { a.router.messages(c.id) }
        a.store.replaceHistory(original)
        assertEquals("pending", status(a, c, id))
    }

    @Test fun directRecipientsAndReceiptsReceivePriorityAndInventorySuppressesOnlyExactDigest() {
        val a = Node("Alice"); val b = Node("Relay"); val c = Node("Carol"); val d = Node("Dave")
        trust(a, c); trust(a, d); trust(c, d)
        a.router.sendText(d.id, "older other recipient")
        now++
        val directId = a.router.sendText(c.id, "newer direct recipient")
        val toC = a.router.offers(c.id, emptySet())
        assertEquals(directId, toC.first().envelope.id)
        val delivered = c.router.receive(toC.first(), a.id)
        assertTrue(delivered.delivered)
        now++
        val ordinaryId = c.router.sendText(d.id, "ordinary relay message")
        val toB = c.router.offers(b.id, emptySet())
        assertEquals(listOf(delivered.receiptId, ordinaryId), toB.map { it.envelope.id })
        val directInventory = a.router.inventory().single { it.id == directId }
        assertFalse(a.router.offers(c.id, setOf(directInventory.token)).any { it.envelope.id == directId })
        // A matching UUID with a different digest is not evidence the peer holds our envelope.
        assertTrue(a.router.offers(c.id, setOf("$directId:${"00".repeat(32)}")).any { it.envelope.id == directId })
    }

    @Test fun duplicateFloodIsRateLimitedAndWindowRecoversWithoutNewHistory() {
        val a = Node("Alice"); val c = Node("Carol")
        trust(a, c)
        a.router.sendText(c.id, "bounded replay processing")
        val packet = offer(a, c)
        assertTrue(c.router.receive(packet, a.id).delivered)
        repeat(63) { assertTrue(c.router.receive(packet, a.id).duplicate) }
        val limited = c.router.receive(packet, a.id)
        assertFalse(limited.accepted)
        assertTrue(limited.rejectedReason!!.contains("rate limit"))
        now += 60_001
        assertTrue(c.router.receive(packet, a.id).duplicate)
        assertEquals(1, c.router.messages(a.id).size)
        assertEquals(1, c.store.queued(now).count { it.packet.envelope.kind == EnvelopeKind.RECEIPT })
    }

    @Test fun immutableCiphertextTamperingFailsBeforeCustodyOrHistory() {
        val a = Node("Alice"); val b = Node("Relay"); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "signature covers ciphertext")
        val packet = offer(a, b)
        val changed = packet.envelope.ciphertext.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val forged = packet.copy(envelope = packet.envelope.copy(ciphertext = changed))
        val rejected = b.router.receive(forged, a.id)
        assertFalse(rejected.accepted)
        assertNull(b.store.seenDigest(id))
        assertEquals(0L, b.store.queueBytes())
        assertTrue(b.store.historyRows().isEmpty())
    }

    @Test fun previouslyValidatedQueueIsNeverOfferedAfterPacketBytesOrDigestAreCorrupted() {
        val a = Node("Alice"); val c = Node("Carol")
        trust(a, c)
        val id = a.router.sendText(c.id, "recheck persisted bytes before forwarding")
        assertEquals(id, a.router.inventorySnapshot().single().id)
        val record = a.store.envelope(id)!!
        val cipher = record.packet.envelope.ciphertext.copyOf()
        val encoded = record.encoded.copyOf()
        val digest = record.digest.copyOf()
        val signature = record.packet.envelope.signature.copyOf()

        record.packet.envelope.ciphertext[0] = (cipher[0].toInt() xor 1).toByte()
        assertTrue(a.router.offers(c.id, emptySet()).isEmpty())
        assertTrue(a.router.inventorySnapshot().isEmpty())
        cipher.copyInto(record.packet.envelope.ciphertext)
        record.encoded[0] = (encoded[0].toInt() xor 1).toByte()
        assertTrue(a.router.offers(c.id, emptySet()).isEmpty())
        encoded.copyInto(record.encoded)
        record.digest[0] = (digest[0].toInt() xor 1).toByte()
        assertTrue(a.router.offers(c.id, emptySet()).isEmpty())
        digest.copyInto(record.digest)
        assertEquals(id, a.router.offers(c.id, emptySet()).single().envelope.id)

        // Coordinated packet+stored-byte corruption must also invalidate a prior positive check.
        record.packet.envelope.signature[0] = (signature[0].toInt() xor 1).toByte()
        a.crypto.encodePacket(record.packet).copyInto(record.encoded)
        assertTrue(a.router.offers(c.id, emptySet()).isEmpty())
        signature.copyInto(record.packet.envelope.signature)
        encoded.copyInto(record.encoded)
        assertEquals(id, a.router.inventorySnapshot().single().id)
    }

    @Test fun completeInventorySnapshotMatchesBoundedPages() {
        val a = Node("Alice"); val c = Node("Carol")
        trust(a, c)
        repeat(3) { a.router.sendText(c.id, "inventory message $it"); now++ }
        val snapshot = a.router.inventorySnapshot()
        assertEquals(3, snapshot.size)
        assertEquals(snapshot, (0..2).flatMap { a.router.inventory(1, it) })
        assertTrue(a.router.inventory(1, 3).isEmpty())
    }

    private fun receipt(sender: Node, receiver: Node, originalId: String, digest: ByteArray, deliveredAt: Long): RoutedEnvelope {
        val body = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeInt(0x4f4d5232)
                it.writeUTF(originalId)
                it.write(digest)
                it.writeLong(deliveredAt)
            }
        }.toByteArray()
        val envelope = sender.crypto.seal(receiver.crypto.identity(), EnvelopeKind.RECEIPT, body, now, 86_400, 8)
        return sender.crypto.appendHop(RoutedEnvelope(envelope), receiver.id)
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean =
        needle.isNotEmpty() && haystack.size >= needle.size &&
            (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }
}
