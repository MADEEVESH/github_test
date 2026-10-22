package dev.offlinechat.offlinechat.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the transport protocol without claiming physical Bluetooth coverage. */
class ReliableFramesTest {
    @Test
    fun maximumPayloadRoundTripsAtTheMinimumAttSize() {
        val link = TestLink()
        val payload = ByteArray(ReliableFrames.MAX_PAYLOAD_BYTES) { it.toByte() }

        link.a.send(payload)
        link.pump()

        assertEquals(1, link.receivedB.size)
        assertArrayEquals(payload, link.receivedB.single())
        assertTrue(link.errorsA.isEmpty())
        assertTrue(link.errorsB.isEmpty())
        assertTrue(link.framesA.all { it.size <= ReliableFrames.MIN_ATT_PAYLOAD })
        val first = link.framesA.first()
        assertArrayEquals(byteArrayOf(0x4d, 2, 1), first.copyOfRange(0, 3))
        assertTrue(transferId(first) != 0L)
        assertEquals(0, sequence(first))
        assertEquals((payload.size + 8) / 9, total(first))
        assertTrue(link.journalA.outbound.isEmpty())
        assertTrue(link.journalB.inbound.isEmpty())
    }

    @Test
    fun negotiatedPayloadSizeReducesFragmentCount() {
        val link = TestLink(ReliableFrames.MAX_ATT_PAYLOAD)
        val payload = ByteArray(4_096) { (it * 13).toByte() }

        link.a.send(payload)
        link.pump()

        assertArrayEquals(payload, link.receivedB.single())
        assertEquals((payload.size + 232) / 233, link.framesA.size)
        assertEquals(ReliableFrames.MAX_ATT_PAYLOAD, link.framesA.first().size)
        assertTrue(link.framesB.all(ReliableFrames::isAck))
    }

    @Test
    fun bothDirectionsTransferIndependentlyOnOneLink() {
        val link = TestLink(80)
        val toB = ByteArray(1_024) { it.toByte() }
        val toA = ByteArray(767) { (255 - it).toByte() }

        link.a.send(toB)
        link.b.send(toA)
        link.pump()

        assertArrayEquals(toA, link.receivedA.single())
        assertArrayEquals(toB, link.receivedB.single())
        assertTrue(link.errorsA.isEmpty())
        assertTrue(link.errorsB.isEmpty())
    }

    @Test
    fun aLostDataFrameRetriesTheSameFragment() {
        val link = TestLink()
        val payload = ByteArray(27) { it.toByte() }
        link.a.send(payload)
        val dropped = link.wire.removeFirst()
        assertTrue(dropped.toB)

        link.clock.now = ReliableFrames.ACK_TIMEOUT_MS - 1
        link.a.tick()
        assertEquals(1, link.framesA.size)
        link.clock.now++
        link.a.tick()

        assertArrayEquals(dropped.frame, link.framesA.last())
        link.pump()
        assertArrayEquals(payload, link.receivedB.single())
        assertTrue(link.errorsA.isEmpty())
    }

    @Test
    fun aLostFinalAckDoesNotDeliverThePayloadTwice() {
        val link = TestLink()
        val payload = "final ACK can disappear".toByteArray()
        var discarded = false
        link.a.send(payload)
        link.pump { packet ->
            if (!packet.toB && sequence(packet.frame) == 2 && !discarded) {
                discarded = true
                false
            } else true
        }
        assertTrue(discarded)
        assertEquals(1, link.receivedB.size)
        assertFalse(link.journalA.outbound.isEmpty())

        link.clock.now += ReliableFrames.ACK_TIMEOUT_MS
        link.a.tick()
        link.pump()

        assertArrayEquals(payload, link.receivedB.single())
        assertTrue(link.journalA.outbound.isEmpty())
        assertTrue(link.errorsB.isEmpty())
    }

    @Test
    fun duplicateAcksCannotSkipTheNextFragmentOrStartTheNextTransfer() {
        val link = TestLink()
        val first = ByteArray(19) { it.toByte() }
        val second = "queued".toByteArray()
        link.a.send(first)
        link.a.send(second)

        link.b.accept(link.wire.removeFirst().frame)
        val ack = link.wire.removeFirst().frame
        link.a.accept(ack)
        val emittedAfterAck = link.framesA.size
        link.a.accept(ack)
        assertEquals(emittedAfterAck, link.framesA.size)
        link.pump()
        val completedCount = link.framesA.size
        link.a.accept(ack)

        assertEquals(completedCount, link.framesA.size)
        assertEquals(2, link.receivedB.size)
        assertArrayEquals(first, link.receivedB[0])
        assertArrayEquals(second, link.receivedB[1])
        assertTrue(link.errorsA.isEmpty())
    }

    @Test
    fun journalWritesPrecedeDataAndAckEmission() {
        val outbound = MemoryJournal()
        val outboundEvents = mutableListOf<String>()
        val sender = ReliableFrames("peer", outbound, {
            assertEquals(1, outbound.outbound.size)
            outboundEvents.add("data")
        }, {}, { throw AssertionError(it) })
        sender.send(byteArrayOf(1))
        assertEquals(listOf("data"), outboundEvents)

        val inbound = MemoryJournal()
        val receiver = ReliableFrames("peer", inbound, {
            assertEquals(listOf("in:0"), inbound.events)
            assertArrayEquals(byteArrayOf(7), inbound.inbound.values.single().single())
            inbound.events.add("ack")
        }, {}, { throw AssertionError(it) })
        receiver.accept(data(10, 0, 2, byteArrayOf(7)))

        assertEquals(listOf("in:0", "ack"), inbound.events)
    }

    @Test
    fun finalPayloadHandlingPrecedesFinalAckAndTransportAckIsNotDelivery() {
        val journal = MemoryJournal()
        val events = mutableListOf<String>()
        val receiver = ReliableFrames("peer", journal, {
            assertTrue(ReliableFrames.isAck(it))
            events.add("ack")
        }, { events.add("payload") }, { throw AssertionError(it) })

        receiver.accept(data(11, 0, 1, byteArrayOf(9)))

        assertEquals(listOf("payload", "ack"), events)
        // The sender has no application-delivery callback. A frame ACK merely
        // advances/clears this journal; signed envelope receipts are separate.
        val delivered = mutableListOf<ByteArray>()
        val sent = mutableListOf<ByteArray>()
        val sender = ReliableFrames("peer", journal, { sent.add(it) }, { delivered.add(it) }, {})
        sender.send(byteArrayOf(1))
        sender.accept(ack(transferId(sent.single()), 0))
        assertTrue(delivered.isEmpty())
        assertTrue(journal.outbound.isEmpty())
    }

    @Test
    fun retriesAreBoundedAndInterruptedOutboundJournalIsRetained() {
        val link = TestLink()
        link.a.send(byteArrayOf(1, 2, 3))
        repeat(ReliableFrames.MAX_RETRIES) {
            link.clock.now += ReliableFrames.ACK_TIMEOUT_MS
            link.a.tick()
        }
        assertEquals(1 + ReliableFrames.MAX_RETRIES, link.framesA.size)
        assertTrue(link.errorsA.isEmpty())

        link.clock.now += ReliableFrames.ACK_TIMEOUT_MS
        link.a.tick()
        link.a.tick()

        assertEquals(1, link.errorsA.size)
        assertTrue(link.errorsA.single().contains("timed out"))
        assertEquals(1, link.journalA.outbound.size)
        assertEquals(-1, link.journalA.outbound.values.single().ackedThrough)
        assertFails<IllegalStateException> { link.a.send(byteArrayOf(4)) }
    }

    @Test
    fun inboundTimeoutRetainsTheDurablePartialTransfer() {
        val link = TestLink()
        link.b.accept(data(12, 0, 2, byteArrayOf(1)))

        link.clock.now = ReliableFrames.INBOUND_TIMEOUT_MS
        link.b.tick()

        assertEquals(1, link.errorsB.size)
        assertEquals(1, link.journalB.inbound.size)
        assertArrayEquals(byteArrayOf(1), link.journalB.inbound.values.single().single())
        assertTrue(link.receivedB.isEmpty())
    }

    @Test
    fun aNewReceiverRestoresDurableFragmentsAfterInterruption() {
        val journal = MemoryJournal()
        val oldAcks = mutableListOf<ByteArray>()
        val old = ReliableFrames("peer", journal, { oldAcks.add(it) }, {}, {})
        old.accept(data(13, 0, 3, byteArrayOf(1, 2)))
        old.accept(data(13, 1, 3, byteArrayOf(3, 4)))
        old.close("process interrupted")
        assertEquals(2, journal.inbound.values.single().size)

        val received = mutableListOf<ByteArray>()
        val acks = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()
        val restored = ReliableFrames("peer", journal, { acks.add(it) }, { received.add(it) }, { errors.add(it) })
        // A duplicate of the last durable fragment is idempotent on restart.
        restored.accept(data(13, 1, 3, byteArrayOf(3, 4)))
        restored.accept(data(13, 2, 3, byteArrayOf(5)))

        assertEquals(listOf(1, 2), acks.map(::sequence))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), received.single())
        assertTrue(journal.inbound.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun durableWriteRejectionAndExceptionsNeverProduceAnAck() {
        for (throwInstead in listOf(false, true)) {
            val link = TestLink()
            link.journalB.rejectInbound = !throwInstead
            link.journalB.throwInbound = throwInstead

            link.b.accept(data(14, 0, 1, byteArrayOf(1)))

            assertTrue(link.framesB.isEmpty())
            assertTrue(link.receivedB.isEmpty())
            assertEquals(1, link.errorsB.size)
        }
    }

    @Test
    fun anUnpersistableOutboundTransferIsNeverEmitted() {
        val link = TestLink()
        link.journalA.throwOutbound = true

        link.a.send(byteArrayOf(1))

        assertTrue(link.framesA.isEmpty())
        assertEquals(1, link.errorsA.size)
    }

    @Test
    fun aFailedAckJournalWriteCannotAdvanceToTheNextFragment() {
        val link = TestLink()
        link.a.send(ByteArray(10) { it.toByte() })
        val first = link.framesA.single()
        link.journalA.throwAck = true

        link.a.accept(ack(transferId(first), 0))

        assertEquals(1, link.framesA.size)
        assertEquals(-1, link.journalA.outbound.values.single().ackedThrough)
        assertEquals(1, link.errorsA.size)
    }

    @Test
    fun futureAcksAndMissingFirstFragmentsAreRejected() {
        val sender = TestLink()
        sender.a.send(ByteArray(20))
        sender.a.accept(ack(transferId(sender.framesA.single()), 1))
        assertEquals(1, sender.errorsA.size)
        assertEquals(-1, sender.journalA.outbound.values.single().ackedThrough)

        val receiver = TestLink()
        receiver.b.accept(data(15, 1, 2, byteArrayOf(1)))
        assertEquals(1, receiver.errorsB.size)
        assertTrue(receiver.framesB.isEmpty())
        assertTrue(receiver.journalB.inbound.isEmpty())
    }

    @Test
    fun reorderedOrChangedDuplicateFragmentsAreRejectedWithoutAnotherAck() {
        for (next in listOf(data(16, 2, 3, byteArrayOf(2)), data(16, 0, 3, byteArrayOf(2)),
            data(16, 1, 2, byteArrayOf(2)))) {
            val link = TestLink()
            link.b.accept(data(16, 0, 3, byteArrayOf(1)))
            link.b.accept(next)

            assertEquals(1, link.framesB.size)
            assertEquals(1, link.errorsB.size)
            assertEquals(1, link.journalB.inbound.values.single().size)
        }
    }

    @Test
    fun malformedBoundariesAndOversizedNegotiatedFramesFailClosed() {
        val invalidFrames = listOf(
            ByteArray(0), ByteArray(8), data(0, 0, 1, byteArrayOf(1)),
            data(17, 0, 0, byteArrayOf(1)), data(17, 1, 1, byteArrayOf(1)),
            data(17, 0, ReliableFrames.MAX_FRAMES + 1, byteArrayOf(1)),
            data(17, 0, 1, ByteArray(10)), // 21 bytes exceeds default ATT payload.
            ack(17, 0) + byteArrayOf(0),
            data(17, 0, 1, byteArrayOf(1)).also { it[1] = 1 },
            data(17, 0, 1, byteArrayOf(1)).also { it[2] = 7 },
        )
        for (frame in invalidFrames) {
            val link = TestLink()
            link.b.accept(frame)
            link.b.accept(data(18, 0, 1, byteArrayOf(1)))
            assertEquals("frame: ${frame.contentToString()}", 1, link.errorsB.size)
            assertTrue(link.framesB.isEmpty())
            assertTrue(link.receivedB.isEmpty())
        }
        assertFalse(ReliableFrames.isAck(ByteArray(0)))
        assertFalse(ReliableFrames.isAck(ByteArray(9)))
        assertTrue(ReliableFrames.isAck(ack(18, 0)))
    }

    @Test
    fun corruptOversizedJournalFragmentsAreRejectedBeforeAckOrDelivery() {
        val link = TestLink(ReliableFrames.MAX_ATT_PAYLOAD)
        link.journalB.seed("A", 19, 2, listOf(ByteArray(234)))

        link.b.accept(data(19, 1, 2, byteArrayOf(1)))

        assertEquals(1, link.errorsB.size)
        assertTrue(link.framesB.isEmpty())
        assertTrue(link.receivedB.isEmpty())
    }

    @Test
    fun completedTransferIdCannotBeReusedWithDifferentContents() {
        val link = TestLink()
        link.b.accept(data(20, 0, 1, byteArrayOf(1)))
        link.b.accept(data(20, 0, 1, byteArrayOf(1)))
        assertEquals(2, link.framesB.size)
        assertEquals(1, link.receivedB.size)

        link.b.accept(data(20, 0, 1, byteArrayOf(2)))

        assertEquals(2, link.framesB.size)
        assertEquals(1, link.receivedB.size)
        assertEquals(1, link.errorsB.size)
    }

    @Test
    fun queueLimitsAndDefensiveCopiesProtectPendingTransfers() {
        val link = TestLink()
        val payload = byteArrayOf(1, 2, 3)
        link.a.send(payload)
        payload.fill(9)
        repeat(ReliableFrames.MAX_QUEUED_TRANSFERS - 1) { link.a.send(byteArrayOf(it.toByte())) }
        assertFails<IllegalStateException> { link.a.send(byteArrayOf(7)) }
        link.pump()
        assertArrayEquals(byteArrayOf(1, 2, 3), link.receivedB.first())
        assertEquals(ReliableFrames.MAX_QUEUED_TRANSFERS, link.receivedB.size)

        val byteLimited = TestLink()
        repeat(4) { byteLimited.a.send(ByteArray(ReliableFrames.MAX_PAYLOAD_BYTES)) }
        assertFails<IllegalStateException> { byteLimited.a.send(byteArrayOf(1)) }
        assertFails<IllegalArgumentException> { TestLink().a.send(ByteArray(0)) }
        assertFails<IllegalArgumentException> { TestLink().a.send(ByteArray(ReliableFrames.MAX_PAYLOAD_BYTES + 1)) }
    }

    @Test
    fun shrinkingAnActiveMtuFailsWithoutDiscardingTheDurableTransfer() {
        val link = TestLink(ReliableFrames.MAX_ATT_PAYLOAD)
        link.a.send(ByteArray(300))

        link.a.setPayloadSize(ReliableFrames.MIN_ATT_PAYLOAD)

        assertEquals(1, link.errorsA.size)
        assertEquals(1, link.framesA.size)
        assertEquals(1, link.journalA.outbound.size)
    }

    @Test
    fun aPayloadHandlerFailureRetainsJournalAndWithholdsFinalAck() {
        val journal = MemoryJournal()
        val emitted = mutableListOf<ByteArray>()
        val errors = mutableListOf<String>()
        val receiver = ReliableFrames("peer", journal, { emitted.add(it) }, {
            throw IllegalStateException("router handoff failed")
        }, { errors.add(it) })

        receiver.accept(data(21, 0, 1, byteArrayOf(1)))

        assertTrue(emitted.isEmpty())
        assertEquals(1, journal.inbound.size)
        assertEquals(1, errors.size)
    }

    private class TestClock(var now: Long = 0)
    private data class Packet(val toB: Boolean, val frame: ByteArray)

    private class TestLink(payloadSize: Int = ReliableFrames.MIN_ATT_PAYLOAD) {
        val clock = TestClock()
        val journalA = MemoryJournal()
        val journalB = MemoryJournal()
        val wire = ArrayDeque<Packet>()
        val framesA = mutableListOf<ByteArray>()
        val framesB = mutableListOf<ByteArray>()
        val receivedA = mutableListOf<ByteArray>()
        val receivedB = mutableListOf<ByteArray>()
        val errorsA = mutableListOf<String>()
        val errorsB = mutableListOf<String>()
        val a = ReliableFrames("B", journalA, {
            framesA.add(it.copyOf())
            wire.addLast(Packet(true, it.copyOf()))
        }, { receivedA.add(it.copyOf()) }, { errorsA.add(it) }, { clock.now })
        val b = ReliableFrames("A", journalB, {
            framesB.add(it.copyOf())
            wire.addLast(Packet(false, it.copyOf()))
        }, { receivedB.add(it.copyOf()) }, { errorsB.add(it) }, { clock.now })

        init {
            a.setPayloadSize(payloadSize)
            b.setPayloadSize(payloadSize)
        }

        fun pump(accept: (Packet) -> Boolean = { true }) {
            var operations = 0
            while (wire.isNotEmpty()) {
                check(++operations <= 100_000) { "Protocol did not quiesce." }
                val packet = wire.removeFirst()
                if (accept(packet)) {
                    if (packet.toB) b.accept(packet.frame) else a.accept(packet.frame)
                }
            }
        }
    }

    private data class Pending(val payload: ByteArray, val total: Int, var ackedThrough: Int)

    /** Enforces contiguous/idempotent writes like the production bounded journal. */
    private class MemoryJournal : FrameJournal {
        val inbound = linkedMapOf<Pair<String, Long>, MutableList<ByteArray>>()
        private val inboundTotals = mutableMapOf<Pair<String, Long>, Int>()
        val outbound = linkedMapOf<Pair<String, Long>, Pending>()
        val events = mutableListOf<String>()
        var rejectInbound = false
        var throwInbound = false
        var throwOutbound = false
        var throwAck = false

        fun seed(peer: String, id: Long, total: Int, chunks: List<ByteArray>) {
            inbound[peer to id] = chunks.map { it.copyOf() }.toMutableList()
            inboundTotals[peer to id] = total
        }

        override fun recordInbound(peer: String, transferId: Long, total: Int, sequence: Int, chunk: ByteArray): Boolean {
            if (throwInbound) throw IllegalStateException("disk unavailable")
            if (rejectInbound) return false
            val key = peer to transferId
            if (inboundTotals[key]?.let { it != total } == true) return false
            val chunks = inbound.getOrPut(key) { mutableListOf() }
            if (sequence > chunks.size || sequence < 0) return false
            if (sequence < chunks.size && !chunks[sequence].contentEquals(chunk)) return false
            inboundTotals[key] = total
            if (sequence == chunks.size) chunks.add(chunk.copyOf())
            events.add("in:$sequence")
            return true
        }

        override fun readInbound(peer: String, transferId: Long): List<ByteArray> =
            inbound[peer to transferId]?.map { it.copyOf() } ?: emptyList()

        override fun clearInbound(peer: String, transferId: Long) {
            inbound.remove(peer to transferId)
            inboundTotals.remove(peer to transferId)
            events.add("clearIn")
        }

        override fun recordOutbound(peer: String, transferId: Long, payload: ByteArray, total: Int, ackedThrough: Int) {
            if (throwOutbound) throw IllegalStateException("disk unavailable")
            outbound[peer to transferId] = Pending(payload.copyOf(), total, ackedThrough)
            events.add("out:$ackedThrough")
        }

        override fun ackOutbound(peer: String, transferId: Long, ackedThrough: Int) {
            if (throwAck) throw IllegalStateException("disk unavailable")
            val pending = checkNotNull(outbound[peer to transferId])
            check(ackedThrough == pending.ackedThrough + 1)
            pending.ackedThrough = ackedThrough
            events.add("outAck:$ackedThrough")
        }

        override fun clearOutbound(peer: String, transferId: Long) {
            outbound.remove(peer to transferId)
            events.add("clearOut")
        }

        override fun cleanupTransfers(now: Long) = Unit
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return
            throw AssertionError("Expected ${T::class.java.simpleName}, got ${error::class.java.simpleName}", error)
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    companion object {
        private fun data(id: Long, sequence: Int, total: Int, chunk: ByteArray): ByteArray =
            ByteBuffer.allocate(ReliableFrames.DATA_HEADER_BYTES + chunk.size).order(ByteOrder.BIG_ENDIAN)
                .put(0x4d.toByte()).put(2.toByte()).put(1.toByte()).putInt(id.toInt())
                .putShort(sequence.toShort()).putShort(total.toShort()).put(chunk).array()

        private fun ack(id: Long, sequence: Int): ByteArray =
            ByteBuffer.allocate(ReliableFrames.ACK_BYTES).order(ByteOrder.BIG_ENDIAN)
                .put(0x4d.toByte()).put(2.toByte()).put(2.toByte()).putInt(id.toInt())
                .putShort(sequence.toShort()).array()

        private fun transferId(frame: ByteArray): Long = ByteBuffer.wrap(frame).getInt(3).toLong() and 0xffff_ffffL
        private fun sequence(frame: ByteArray): Int = ByteBuffer.wrap(frame).getShort(7).toInt() and 0xffff
        private fun total(frame: ByteArray): Int = ByteBuffer.wrap(frame).getShort(9).toInt() and 0xffff
    }
}
