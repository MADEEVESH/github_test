package dev.offlinechat.offlinechat.mesh

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * Single-owner-thread stop-and-wait BLE framing. A frame ACK acknowledges only
 * durable fragment custody, never authenticated mesh delivery.
 *
 * DATA: magic/version/type/u32 transfer/u16 sequence/u16 total/body.
 * ACK:  magic/version/type/u32 transfer/u16 sequence.
 * Native GATT operation serialization and address-specific writes are supplied
 * by [emit]. Every incoming fragment is journaled before its ACK is emitted.
 */
class ReliableFrames(
    private val peer: String,
    private val journal: FrameJournal,
    private val emit: (ByteArray) -> Unit,
    private val deliver: (ByteArray) -> Unit,
    private val fail: (String) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object {
        const val MIN_ATT_PAYLOAD = 20
        const val MAX_ATT_PAYLOAD = 244
        const val MAX_PAYLOAD_BYTES = 65_536
        const val DATA_HEADER_BYTES = 11
        const val ACK_BYTES = 9
        const val ACK_TIMEOUT_MS = 2_000L
        const val MAX_RETRIES = 3
        const val MAX_QUEUED_TRANSFERS = 8
        const val MAX_QUEUED_BYTES = 256 * 1024
        const val MAX_INCOMING_TRANSFERS = 4
        const val MAX_FRAMES = (MAX_PAYLOAD_BYTES + 8) / 9
        const val INBOUND_TIMEOUT_MS = 30_000L
        private const val COMPLETED_TTL_MS = 10 * 60_000L
        private const val MAX_COMPLETED_IDS = 64
        private const val MAGIC = 0x4d
        private const val VERSION = 2
        private const val DATA = 1
        private const val ACK = 2
        private val random = SecureRandom()

        fun isAck(frame: ByteArray): Boolean = frame.size == ACK_BYTES &&
            (frame[0].toInt() and 0xff) == MAGIC &&
            (frame[1].toInt() and 0xff) == VERSION &&
            (frame[2].toInt() and 0xff) == ACK
    }

    private data class Outgoing(
        val id: Long,
        val payload: ByteArray,
        val chunks: List<ByteArray>,
        var sequence: Int = 0,
        var retries: Int = 0,
        var sentAt: Long = 0,
    )

    private data class Incoming(
        val total: Int,
        val chunks: MutableList<ByteArray>,
        var bytes: Int,
        var updatedAt: Long,
    )

    private data class Completed(
        val total: Int,
        val lastChunk: ByteArray,
        val completedAt: Long,
    )

    private var attPayload = MIN_ATT_PAYLOAD
    private var closed = false
    private var queuedBytes = 0
    private val queued = ArrayDeque<ByteArray>()
    private var outgoing: Outgoing? = null
    private val incoming = LinkedHashMap<Long, Incoming>()
    private val completed = LinkedHashMap<Long, Completed>()

    fun setPayloadSize(bytes: Int) {
        require(bytes in MIN_ATT_PAYLOAD..MAX_ATT_PAYLOAD) { "Invalid negotiated BLE payload size." }
        if (closed) return
        if (bytes < attPayload && outgoing?.chunks?.any { it.size + DATA_HEADER_BYTES > bytes } == true) {
            terminate("BLE MTU shrank during a transfer; reconnect and retry the durable message.", true)
            return
        }
        attPayload = bytes
    }

    fun send(payload: ByteArray) {
        check(!closed) { "The BLE transfer link is closed." }
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
            "BLE payload must contain between 1 and $MAX_PAYLOAD_BYTES bytes."
        }
        check(queued.size + (if (outgoing == null) 0 else 1) < MAX_QUEUED_TRANSFERS) {
            "BLE transfer queue is full; retry after peer backoff."
        }
        check(queuedBytes + payload.size <= MAX_QUEUED_BYTES) {
            "BLE transfer byte quota is full; retry after peer backoff."
        }
        queued.addLast(payload.copyOf())
        queuedBytes += payload.size
        startNext()
    }

    fun accept(frame: ByteArray) {
        if (closed) return
        if (frame.size < ACK_BYTES || frame.size > MAX_ATT_PAYLOAD ||
            (frame[0].toInt() and 0xff) != MAGIC || (frame[1].toInt() and 0xff) != VERSION) {
            terminate("Malformed or unsupported BLE frame boundary.", true)
            return
        }
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        buffer.position(2)
        val type = buffer.get().toInt() and 0xff
        val id = buffer.int.toLong() and 0xffff_ffffL
        val sequence = buffer.short.toInt() and 0xffff
        if (id == 0L) {
            terminate("Invalid BLE transfer identifier.", true)
            return
        }
        when (type) {
            ACK -> {
                if (frame.size != ACK_BYTES) {
                    terminate("Invalid BLE ACK length.", true)
                } else {
                    acceptAck(id, sequence)
                }
            }
            DATA -> {
                if (frame.size <= DATA_HEADER_BYTES) {
                    terminate("Empty or truncated BLE data frame.", true)
                    return
                }
                val total = buffer.short.toInt() and 0xffff
                if (total !in 1..MAX_FRAMES || sequence >= total) {
                    terminate("Invalid BLE fragment count or sequence.", true)
                    return
                }
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                acceptData(id, sequence, total, chunk)
            }
            else -> terminate("Unknown BLE frame type.", true)
        }
    }

    fun tick(now: Long = clock()) {
        if (closed) return
        completed.entries.removeAll { now - it.value.completedAt >= COMPLETED_TTL_MS }
        if (incoming.values.any { now - it.updatedAt >= INBOUND_TIMEOUT_MS }) {
            terminate("BLE receive timed out; durable fragments were retained for cleanup/retry.", true)
            return
        }
        val current = outgoing ?: return
        if (now - current.sentAt < ACK_TIMEOUT_MS) return
        if (current.retries >= MAX_RETRIES) {
            terminate("BLE fragment ACK timed out after $MAX_RETRIES retries; message remains pending.", true)
            return
        }
        current.retries++
        writeCurrent(current, now)
    }

    /** Retains interrupted journal state; the mesh queue reconciles full envelopes on reconnect. */
    fun close(reason: String) {
        val unfinished = outgoing != null || queued.isNotEmpty() || incoming.isNotEmpty()
        terminate(reason, unfinished)
    }

    private fun startNext() {
        if (closed || outgoing != null || queued.isEmpty()) return
        val payload = queued.removeFirst()
        val chunkBytes = attPayload - DATA_HEADER_BYTES
        val chunks = ArrayList<ByteArray>((payload.size + chunkBytes - 1) / chunkBytes)
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + chunkBytes, payload.size)
            chunks.add(payload.copyOfRange(offset, end))
            offset = end
        }
        var id: Long
        do {
            id = random.nextInt().toLong() and 0xffff_ffffL
        } while (id == 0L)
        val current = Outgoing(id, payload, chunks)
        try {
            journal.recordOutbound(peer, id, payload, chunks.size, -1)
        } catch (error: Exception) {
            terminate("Could not persist outgoing BLE transfer: ${error.message}", true)
            return
        }
        outgoing = current
        writeCurrent(current, clock())
    }

    private fun writeCurrent(current: Outgoing, now: Long) {
        current.sentAt = now
        val body = current.chunks[current.sequence]
        val frame = ByteBuffer.allocate(DATA_HEADER_BYTES + body.size).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC.toByte()).put(VERSION.toByte()).put(DATA.toByte())
            .putInt(current.id.toInt()).putShort(current.sequence.toShort())
            .putShort(current.chunks.size.toShort()).put(body).array()
        emitFrame(frame)
    }

    private fun acceptAck(id: Long, sequence: Int) {
        val current = outgoing ?: return // A delayed ACK cannot start or complete a message.
        if (id != current.id || sequence < current.sequence) return
        if (sequence != current.sequence) {
            terminate("BLE peer acknowledged a fragment that was not sent.", true)
            return
        }
        try {
            journal.ackOutbound(peer, id, sequence)
            if (sequence + 1 == current.chunks.size) journal.clearOutbound(peer, id)
        } catch (error: Exception) {
            terminate("Could not persist BLE ACK progress: ${error.message}", true)
            return
        }
        if (sequence + 1 == current.chunks.size) {
            outgoing = null
            queuedBytes -= current.payload.size
            startNext()
        } else {
            current.sequence++
            current.retries = 0
            writeCurrent(current, clock())
        }
    }

    private fun acceptData(id: Long, sequence: Int, total: Int, chunk: ByteArray) {
        val previous = completed[id]
        if (previous != null) {
            if (total == previous.total && sequence == total - 1 && previous.lastChunk.contentEquals(chunk)) {
                emitAck(id, sequence)
            } else {
                terminate("A completed BLE transfer identifier was reused with different data.", true)
            }
            return
        }
        var state = incoming[id]
        if (state == null) {
            if (incoming.size >= MAX_INCOMING_TRANSFERS) {
                terminate("Too many simultaneous incoming BLE transfers.", true)
                return
            }
            val restored = try {
                journal.readInbound(peer, id).map { it.copyOf() }
            } catch (error: Exception) {
                terminate("Could not restore incoming BLE fragments: ${error.message}", true)
                return
            }
            val size = restored.sumOf { it.size }
            if (restored.size > total || size > MAX_PAYLOAD_BYTES || restored.any { it.isEmpty() }) {
                terminate("Corrupt or oversized durable BLE transfer state.", true)
                return
            }
            if (restored.isEmpty() && sequence != 0) {
                terminate("BLE continuation arrived without a durable first fragment.", true)
                return
            }
            state = Incoming(total, restored.toMutableList(), size, clock())
            incoming[id] = state
        }
        if (state.total != total || sequence > state.chunks.size) {
            terminate("BLE transfer fragments were reordered or changed their declared count.", true)
            return
        }
        if (sequence < state.chunks.size && !state.chunks[sequence].contentEquals(chunk)) {
            terminate("BLE duplicate fragment changed its contents.", true)
            return
        }
        if (sequence == state.chunks.size && state.bytes + chunk.size > MAX_PAYLOAD_BYTES) {
            terminate("Incoming BLE transfer exceeded $MAX_PAYLOAD_BYTES bytes.", true)
            return
        }
        val persisted = try {
            journal.recordInbound(peer, id, total, sequence, chunk)
        } catch (error: Exception) {
            terminate("Could not persist incoming BLE fragment: ${error.message}", true)
            return
        }
        if (!persisted) {
            terminate("Incoming BLE fragment was not durably accepted (quota or conflicting data).", true)
            return
        }
        if (sequence == state.chunks.size) {
            state.chunks.add(chunk.copyOf())
            state.bytes += chunk.size
        }
        state.updatedAt = clock()
        if (state.chunks.size == total) {
            val assembled = ByteArray(state.bytes)
            var offset = 0
            for (part in state.chunks) {
                part.copyInto(assembled, offset)
                offset += part.size
            }
            incoming.remove(id)
            try {
                deliver(assembled)
                journal.clearInbound(peer, id)
            } catch (error: Exception) {
                terminate("Assembled BLE payload could not be handled: ${error.message}", true)
                return
            }
            completed[id] = Completed(total, state.chunks.last().copyOf(), clock())
            while (completed.size > MAX_COMPLETED_IDS) completed.remove(completed.keys.first())
        }
        emitAck(id, sequence)
    }

    private fun emitAck(id: Long, sequence: Int) {
        val frame = ByteBuffer.allocate(ACK_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC.toByte()).put(VERSION.toByte()).put(ACK.toByte())
            .putInt(id.toInt()).putShort(sequence.toShort()).array()
        emitFrame(frame)
    }

    private fun emitFrame(frame: ByteArray) {
        if (closed) return
        try {
            emit(frame)
        } catch (error: Exception) {
            terminate("Native BLE write rejected a frame: ${error.message}", true)
        }
    }

    private fun terminate(reason: String, notify: Boolean) {
        if (closed) return
        closed = true
        queued.clear()
        outgoing = null
        queuedBytes = 0
        incoming.clear()
        completed.clear()
        if (notify) fail(reason)
    }
}
