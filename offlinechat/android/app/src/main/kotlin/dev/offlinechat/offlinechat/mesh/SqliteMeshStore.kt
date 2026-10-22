package dev.offlinechat.offlinechat.mesh

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/**
 * Durable ciphertext queue and fragment journal. Every custody/fragment ACK is issued only
 * after its caller observes a committed transaction. No private key or message plaintext is
 * written here. The separate history ciphertext authenticates its public row metadata.
 */
class SqliteMeshStore(
    context: Context,
    private val crypto: MeshCrypto,
    databaseName: String = "offlinechat_mesh.db",
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION), MeshStore, FrameJournal {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        // FULL also makes a successful commit meaningful across a power loss.
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        createCore(db)
        createJournal(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion >= 1 && newVersion <= DATABASE_VERSION) { "Unsupported mesh database migration." }
        // Version 1 is the ciphertext queue/history schema; v2 adds resumable BLE journals.
        // These CREATE IF NOT EXISTS statements deliberately retain queued messages and keys.
        if (oldVersion < 2) createJournal(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("A newer mesh database cannot be opened by this app version.")
    }

    @Synchronized
    override fun settings(): MeshSettings = readSettings(readableDatabase)

    @Synchronized
    override fun updateSettings(settings: MeshSettings) {
        val value = settings.validated()
        transaction { db ->
            require(queueBytes(db) <= value.queueLimitBytes) { "The new limit is smaller than the current queue. Wait for expiry first." }
            db.update("mesh_settings", values(
                "relay_enabled" to value.relayEnabled, "max_hops" to value.maxHops,
                "ttl_seconds" to value.ttlSeconds, "queue_limit_bytes" to value.queueLimitBytes,
            ), "singleton=1", null)
        }
    }

    @Synchronized
    override fun contacts(): List<PublicIdentity> = readableDatabase.rawQuery(
        "SELECT identity FROM contacts WHERE verified=1 ORDER BY id", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(decodeContact(cursor.getBlob(0))) } }

    @Synchronized
    override fun contact(id: String): PublicIdentity? = readableDatabase.rawQuery(
        "SELECT identity FROM contacts WHERE id=? AND verified=1", arrayOf(id),
    ).use { if (it.moveToFirst()) decodeContact(it.getBlob(0)) else null }

    @Synchronized
    override fun addContact(identity: PublicIdentity, verified: Boolean) {
        require(verified) { "Contacts must be verified by scanning their signed QR identity." }
        val encoded = EnvelopeCodec.encodeIdentity(identity)
        decodeContact(encoded)
        require(identity.id != crypto.identity().id) { "This QR code belongs to this device." }
        transaction { db ->
            val existing = db.rawQuery("SELECT identity FROM contacts WHERE id=?", arrayOf(identity.id)).use {
                if (it.moveToFirst()) decodeContact(it.getBlob(0)) else null
            }
            if (existing != null) require(
                MessageDigest.isEqual(existing.signingKey, identity.signingKey) &&
                    MessageDigest.isEqual(existing.encryptionKey, identity.encryptionKey),
            ) { "A verified contact's public keys cannot be replaced." }
            require(existing != null || scalar(db, "SELECT count(*) FROM contacts") < MAX_CONTACTS) { "Verified contact limit reached." }
            db.insertWithOnConflict("contacts", null, values(
                "id" to identity.id, "identity" to encoded, "verified" to true,
            ), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    @Synchronized
    override fun envelope(id: String): QueuedEnvelope? = readableDatabase.rawQuery(
        "SELECT packet,digest,local,received_at FROM relay_queue WHERE id=?", arrayOf(id),
    ).use { if (it.moveToFirst()) queuedRow(it) else null }

    @Synchronized
    override fun seenDigest(id: String): ByteArray? = seenDigest(readableDatabase, id)

    @Synchronized
    override fun queued(now: Long): List<QueuedEnvelope> = readableDatabase.rawQuery(
        "SELECT packet,digest,local,received_at FROM relay_queue WHERE expires_at>? ORDER BY received_at,id", arrayOf(now.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(queuedRow(cursor)) } }

    @Synchronized
    override fun commit(incoming: QueuedEnvelope, history: HistoryRow?, generated: List<QueuedEnvelope>): StoreCommit {
        validateQueued(incoming)
        require(generated.size <= 1) { "Only one delivery receipt may accompany an envelope." }
        generated.forEach { validateQueued(it); require(it.local) { "Generated envelopes must originate locally." } }
        history?.let(::validateHistory)
        return transaction { db ->
            val seen = seenDigest(db, incoming.id)
            if (seen != null) return@transaction if (MessageDigest.isEqual(seen, incoming.digest)) StoreCommit.DUPLICATE else StoreCommit.CONFLICT
            val batch = listOf(incoming) + generated
            if (batch.map { it.id }.toSet().size != batch.size) return@transaction StoreCommit.CONFLICT
            for (record in generated) if (seenDigest(db, record.id) != null) return@transaction StoreCommit.CONFLICT
            val extraBytes = batch.sumOf { it.encoded.size.toLong() }
            if (queueBytes(db) + extraBytes > readSettings(db).queueLimitBytes ||
                scalar(db, "SELECT count(*) FROM relay_queue") + batch.size > MAX_QUEUED_ENVELOPES ||
                scalar(db, "SELECT count(*) FROM seen_envelopes") + batch.size > MAX_SEEN ||
                (history != null && historyRow(db, history.id) == null && scalar(db, "SELECT count(*) FROM message_history") >= MAX_HISTORY)
            ) return@transaction StoreCommit.QUOTA
            history?.let { row ->
                val old = historyRow(db, row.id)
                require(old == null || sameHistoryIdentity(old, row)) { "History metadata cannot be replaced by another envelope." }
            }
            for (record in batch) {
                val envelope = record.packet.envelope
                db.insertOrThrow("relay_queue", null, values(
                    "id" to record.id, "digest" to record.digest, "packet" to record.encoded,
                    "local" to record.local, "received_at" to record.receivedAt,
                    "expires_at" to envelope.expiresAt,
                ))
                db.insertOrThrow("seen_envelopes", null, values(
                    "id" to record.id, "digest" to record.digest,
                    // Keep the immutable ID binding beyond expiry for clock-skew replays.
                    "retain_until" to safeAdd(envelope.expiresAt, MeshRouter.CLOCK_SKEW_MS),
                ))
            }
            history?.let { writeHistory(db, it) }
            StoreCommit.STORED
        }
    }

    @Synchronized
    override fun history(id: String): HistoryRow? = historyRow(readableDatabase, id)

    @Synchronized
    override fun historyRows(contactId: String?): List<HistoryRow> = readableDatabase.rawQuery(
        "SELECT $HISTORY_COLUMNS FROM message_history" + (if (contactId == null) "" else " WHERE contact_id=?") + " ORDER BY created_at,id",
        contactId?.let { arrayOf(it) },
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(historyRow(cursor)) } }

    @Synchronized
    override fun expiringHistory(now: Long): List<HistoryRow> = readableDatabase.rawQuery(
        "SELECT $HISTORY_COLUMNS FROM message_history WHERE outgoing=1 AND expires_at<=? AND status IN ('pending','relayed') ORDER BY created_at,id",
        arrayOf(now.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(historyRow(cursor)) } }

    @Synchronized
    override fun replaceHistory(row: HistoryRow) {
        validateHistory(row)
        transaction { db ->
            val existing = historyRow(db, row.id) ?: throw IllegalArgumentException("History row does not exist.")
            require(sameHistoryIdentity(existing, row)) { "History metadata is immutable." }
            writeHistory(db, row)
        }
    }

    @Synchronized
    override fun recordCustody(id: String, peerId: String, now: Long): Boolean {
        require(EnvelopeCodec.validId(peerId)) { "Invalid peer identity." }
        return transaction { db ->
            if (scalar(db, "SELECT count(*) FROM relay_queue WHERE id=?", arrayOf(id)) == 0L) return@transaction false
            // The route budget bounds meaningful custody peers; cap metadata as well.
            if (scalar(db, "SELECT count(*) FROM custody WHERE envelope_id=?", arrayOf(id)) >= 64) return@transaction false
            db.insertWithOnConflict("custody", null, values(
                "envelope_id" to id, "peer_id" to peerId, "acked_at" to now,
            ), SQLiteDatabase.CONFLICT_IGNORE) != -1L
        }
    }

    @Synchronized
    override fun hasCustody(id: String, peerId: String): Boolean = scalar(
        readableDatabase, "SELECT count(*) FROM custody WHERE envelope_id=? AND peer_id=?", arrayOf(id, peerId),
    ) != 0L

    @Synchronized
    override fun incrementForwarded() {
        writableDatabase.execSQL("UPDATE mesh_stats SET value=value+1 WHERE name='forwarded'")
    }

    @Synchronized
    override fun forwardedCount(): Long = scalar(readableDatabase, "SELECT value FROM mesh_stats WHERE name='forwarded'")

    @Synchronized
    override fun queueBytes(): Long = queueBytes(readableDatabase)

    @Synchronized
    override fun backoff(peerId: String): Backoff = readableDatabase.rawQuery(
        "SELECT failures,next_allowed_at FROM peer_backoff WHERE peer_id=?", arrayOf(peerId),
    ).use { if (it.moveToFirst()) Backoff(it.getInt(0), it.getLong(1)) else Backoff(0, 0) }

    @Synchronized
    override fun setBackoff(peerId: String, backoff: Backoff) {
        require(EnvelopeCodec.validId(peerId) && backoff.failures in 0..12 && backoff.nextAllowedAt >= 0) { "Invalid peer backoff." }
        transaction { db ->
            if (backoff.failures == 0) db.delete("peer_backoff", "peer_id=?", arrayOf(peerId))
            else {
                db.insertWithOnConflict("peer_backoff", null, values(
                    "peer_id" to peerId, "failures" to backoff.failures, "next_allowed_at" to backoff.nextAllowedAt,
                ), SQLiteDatabase.CONFLICT_REPLACE)
                db.execSQL("DELETE FROM peer_backoff WHERE peer_id NOT IN (SELECT peer_id FROM peer_backoff ORDER BY next_allowed_at DESC LIMIT 128)")
            }
        }
    }

    @Synchronized
    override fun cleanup(now: Long) {
        transaction { db ->
            db.delete("relay_queue", "expires_at<=?", arrayOf(now.toString()))
            db.delete("custody", "envelope_id NOT IN (SELECT id FROM relay_queue)", null)
            db.delete("seen_envelopes", "retain_until<=?", arrayOf(now.toString()))
            db.delete("peer_backoff", "next_allowed_at<?", arrayOf((now - 86_400_000L).toString()))
            cleanupTransfers(db, now)
        }
    }

    @Synchronized
    override fun log(level: String, message: String, now: Long) {
        transaction { db ->
            db.insertOrThrow("diagnostics", null, values("time" to now, "level" to level.take(16), "message" to message.take(500)))
            db.execSQL("DELETE FROM diagnostics WHERE id NOT IN (SELECT id FROM diagnostics ORDER BY id DESC LIMIT 200)")
        }
    }

    @Synchronized
    override fun diagnostics(): List<Map<String, Any?>> = readableDatabase.rawQuery(
        "SELECT time,level,message FROM diagnostics ORDER BY id DESC LIMIT 200", null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(mapOf("time" to cursor.getLong(0), "level" to cursor.getString(1), "message" to cursor.getString(2))) } }

    @Synchronized
    override fun recordInbound(peer: String, transferId: Long, total: Int, sequence: Int, chunk: ByteArray): Boolean {
        validateTransfer(peer, transferId, total)
        require(sequence in 0 until total && chunk.size in 1..MAX_FRAME_CHUNK) { "Invalid inbound fragment." }
        return transaction { db ->
            val existing = db.rawQuery(
                "SELECT total,chunk FROM inbound_frames WHERE peer=? AND transfer_id=? AND sequence=?",
                arrayOf(peer, transferId.toString(), sequence.toString()),
            ).use { if (it.moveToFirst()) Pair(it.getInt(0), it.getBlob(1)) else null }
            if (existing != null) return@transaction existing.first == total && MessageDigest.isEqual(existing.second, chunk)
            val transferArgs = arrayOf(peer, transferId.toString())
            val count = scalar(db, "SELECT count(*) FROM inbound_frames WHERE peer=? AND transfer_id=?", transferArgs)
            if (count != sequence.toLong()) return@transaction false // The journal always has a contiguous prefix.
            val firstTotal = db.rawQuery("SELECT total FROM inbound_frames WHERE peer=? AND transfer_id=? LIMIT 1", transferArgs).use {
                if (it.moveToFirst()) it.getInt(0) else total
            }
            if (firstTotal != total) return@transaction false
            if (count == 0L && scalar(db, "SELECT count(DISTINCT transfer_id) FROM inbound_frames WHERE peer=?", arrayOf(peer)) >= MAX_INBOUND_PER_PEER) return@transaction false
            if (count == 0L && scalar(db, "SELECT count(*) FROM (SELECT peer,transfer_id FROM inbound_frames GROUP BY peer,transfer_id)") >= MAX_JOURNAL_TRANSFERS) return@transaction false
            if (scalar(db, "SELECT coalesce(sum(length(chunk)),0) FROM inbound_frames WHERE peer=? AND transfer_id=?", transferArgs) + chunk.size > MAX_TRANSFER_BYTES ||
                scalar(db, "SELECT coalesce(sum(length(chunk)),0) FROM inbound_frames") + chunk.size > MAX_JOURNAL_BYTES
            ) return@transaction false
            db.insertOrThrow("inbound_frames", null, values(
                "peer" to peer, "transfer_id" to transferId, "total" to total, "sequence" to sequence,
                "chunk" to chunk, "updated_at" to System.currentTimeMillis(),
            ))
            true
        }
    }

    @Synchronized
    override fun readInbound(peer: String, transferId: Long): List<ByteArray> = readableDatabase.rawQuery(
        "SELECT chunk FROM inbound_frames WHERE peer=? AND transfer_id=? ORDER BY sequence", arrayOf(peer, transferId.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getBlob(0)) } }

    @Synchronized
    override fun clearInbound(peer: String, transferId: Long) {
        writableDatabase.delete("inbound_frames", "peer=? AND transfer_id=?", arrayOf(peer, transferId.toString()))
    }

    @Synchronized
    override fun recordOutbound(peer: String, transferId: Long, payload: ByteArray, total: Int, ackedThrough: Int) {
        validateTransfer(peer, transferId, total)
        require(payload.size in 1..MAX_TRANSFER_BYTES && ackedThrough in -1 until total) { "Invalid outbound journal entry." }
        transaction { db ->
            val existing = db.rawQuery("SELECT payload,total,acked_through FROM outbound_frames WHERE peer=? AND transfer_id=?", arrayOf(peer, transferId.toString())).use {
                if (it.moveToFirst()) Triple(it.getBlob(0), it.getInt(1), it.getInt(2)) else null
            }
            if (existing != null) {
                require(MessageDigest.isEqual(existing.first, payload) && existing.second == total && ackedThrough >= existing.third) { "Transfer identifier conflicts with an existing journal." }
            } else require(
                scalar(db, "SELECT count(*) FROM outbound_frames") < MAX_JOURNAL_TRANSFERS &&
                    scalar(db, "SELECT coalesce(sum(length(payload)),0) FROM outbound_frames") + payload.size <= MAX_JOURNAL_BYTES,
            ) { "Persistent outbound fragment journal is full." }
            db.insertWithOnConflict("outbound_frames", null, values(
                "peer" to peer, "transfer_id" to transferId, "payload" to payload, "total" to total,
                "acked_through" to ackedThrough, "updated_at" to System.currentTimeMillis(),
            ), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    @Synchronized
    override fun ackOutbound(peer: String, transferId: Long, ackedThrough: Int) {
        transaction { db ->
            val current = db.rawQuery("SELECT total,acked_through FROM outbound_frames WHERE peer=? AND transfer_id=?", arrayOf(peer, transferId.toString())).use {
                require(it.moveToFirst()) { "Outbound transfer is missing from its durable journal." }
                Pair(it.getInt(0), it.getInt(1))
            }
            require(ackedThrough in current.second until current.first && ackedThrough <= current.second + 1) { "Invalid outbound ACK progression." }
            db.update("outbound_frames", values("acked_through" to ackedThrough, "updated_at" to System.currentTimeMillis()), "peer=? AND transfer_id=?", arrayOf(peer, transferId.toString()))
        }
    }

    @Synchronized
    override fun clearOutbound(peer: String, transferId: Long) {
        writableDatabase.delete("outbound_frames", "peer=? AND transfer_id=?", arrayOf(peer, transferId.toString()))
    }

    @Synchronized
    override fun cleanupTransfers(now: Long) { transaction { cleanupTransfers(it, now) } }

    private fun cleanupTransfers(db: SQLiteDatabase, now: Long) {
        val cutoff = now - JOURNAL_TTL_MS
        // Age a complete transfer by its latest persisted fragment, not its first fragment.
        db.execSQL("DELETE FROM inbound_frames WHERE (peer,transfer_id) IN (SELECT peer,transfer_id FROM inbound_frames GROUP BY peer,transfer_id HAVING max(updated_at)<?)", arrayOf<Any>(cutoff))
        db.delete("outbound_frames", "updated_at<?", arrayOf(cutoff.toString()))
    }

    private fun decodeContact(encoded: ByteArray): PublicIdentity = EnvelopeCodec.decodeIdentity(encoded).also {
        require(it.id == EnvelopeCodec.fingerprint(it.signingKey, it.encryptionKey) &&
            crypto.verify(it, EnvelopeCodec.identityUnsigned(it), it.signature)) { "Stored contact identity failed signature verification." }
    }

    private fun validateQueued(record: QueuedEnvelope) {
        require(record.digest.size == 32 && record.encoded.size in 1..MeshRouter.MAX_PACKET_BYTES && record.receivedAt >= 0)
        require(MessageDigest.isEqual(record.digest, crypto.envelopeDigest(record.packet.envelope)) &&
            MessageDigest.isEqual(record.encoded, crypto.encodePacket(record.packet))) { "Queued bytes do not match their immutable envelope." }
        if (record.local) require(record.packet.envelope.sender.id == crypto.identity().id && record.packet.route.isEmpty()) { "Local envelopes must originate on this device." }
    }

    private fun validateHistory(row: HistoryRow) {
        require(row.id.length in 1..64 && EnvelopeCodec.validId(row.contactId) && row.encryptedBody.size in 1..8192 && row.digest.size == 32)
        require(row.createdAt >= 0 && row.expiresAt > row.createdAt && row.status in setOf("pending", "relayed", "delivered", "expired"))
    }

    private fun validateTransfer(peer: String, id: Long, total: Int) {
        require(peer.isNotBlank() && peer.length <= 128 && id in 1..0xffff_ffffL && total in 1..MAX_FRAMES) { "Invalid fragment transfer identifier or bounds." }
    }

    private fun queuedRow(cursor: Cursor): QueuedEnvelope {
        val bytes = cursor.getBlob(0)
        return QueuedEnvelope(crypto.decodePacket(bytes), bytes, cursor.getBlob(1), cursor.getInt(2) != 0, cursor.getLong(3))
    }

    private fun historyRow(db: SQLiteDatabase, id: String): HistoryRow? = db.rawQuery(
        "SELECT $HISTORY_COLUMNS FROM message_history WHERE id=?", arrayOf(id),
    ).use { if (it.moveToFirst()) historyRow(it) else null }

    private fun historyRow(cursor: Cursor) = HistoryRow(
        cursor.getString(0), cursor.getString(1), cursor.getBlob(2), cursor.getInt(3) != 0,
        cursor.getLong(4), cursor.getLong(5), cursor.getString(6), cursor.getBlob(7),
    )

    private fun writeHistory(db: SQLiteDatabase, row: HistoryRow) {
        db.insertWithOnConflict("message_history", null, values(
            "id" to row.id, "contact_id" to row.contactId, "encrypted_body" to row.encryptedBody,
            "outgoing" to row.outgoing, "created_at" to row.createdAt, "expires_at" to row.expiresAt,
            "status" to row.status, "digest" to row.digest,
        ), SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun sameHistoryIdentity(a: HistoryRow, b: HistoryRow): Boolean = a.id == b.id && a.contactId == b.contactId &&
        a.outgoing == b.outgoing && a.createdAt == b.createdAt && a.expiresAt == b.expiresAt && MessageDigest.isEqual(a.digest, b.digest)

    private fun seenDigest(db: SQLiteDatabase, id: String): ByteArray? = db.rawQuery(
        "SELECT digest FROM seen_envelopes WHERE id=?", arrayOf(id),
    ).use { if (it.moveToFirst()) it.getBlob(0) else null }

    private fun readSettings(db: SQLiteDatabase): MeshSettings = db.rawQuery(
        "SELECT relay_enabled,max_hops,ttl_seconds,queue_limit_bytes FROM mesh_settings WHERE singleton=1", null,
    ).use { require(it.moveToFirst()); MeshSettings(it.getInt(0) != 0, it.getInt(1), it.getLong(2), it.getLong(3)).validated() }

    private fun queueBytes(db: SQLiteDatabase): Long = scalar(db, "SELECT coalesce(sum(length(packet)),0) FROM relay_queue")

    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { return block(db).also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }

    companion object {
        const val DATABASE_VERSION = 2
        /** Complete bounded inventories fit one synchronization cycle, including receipts. */
        const val MAX_QUEUED_ENVELOPES = 4096
        private const val HISTORY_COLUMNS = "id,contact_id,encrypted_body,outgoing,created_at,expires_at,status,digest"
        private const val MAX_CONTACTS = 500L
        private const val MAX_HISTORY = 10_000L
        private const val MAX_SEEN = 100_000L
        private const val MAX_TRANSFER_BYTES = 65_536
        private const val MAX_FRAME_CHUNK = 233
        private const val MAX_FRAMES = 7282
        private const val MAX_INBOUND_PER_PEER = 4L
        private const val MAX_JOURNAL_TRANSFERS = 32L
        private const val MAX_JOURNAL_BYTES = 1_048_576L
        private const val JOURNAL_TTL_MS = 30 * 60 * 1000L

        private fun safeAdd(value: Long, add: Long): Long = if (value > Long.MAX_VALUE - add) Long.MAX_VALUE else value + add

        private fun scalar(db: SQLiteDatabase, sql: String, args: Array<String>? = null): Long = db.rawQuery(sql, args).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }

        private fun values(vararg entries: Pair<String, Any>): ContentValues = ContentValues(entries.size).also { result ->
            for ((key, value) in entries) when (value) {
                is String -> result.put(key, value)
                is ByteArray -> result.put(key, value)
                is Boolean -> result.put(key, if (value) 1 else 0)
                is Int -> result.put(key, value)
                is Long -> result.put(key, value)
                else -> error("Unsupported SQLite value.")
            }
        }

        private fun createCore(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE mesh_settings(singleton INTEGER PRIMARY KEY CHECK(singleton=1),relay_enabled INTEGER NOT NULL,max_hops INTEGER NOT NULL,ttl_seconds INTEGER NOT NULL,queue_limit_bytes INTEGER NOT NULL)")
            db.execSQL("INSERT INTO mesh_settings VALUES(1,1,8,86400,26214400)")
            db.execSQL("CREATE TABLE contacts(id TEXT PRIMARY KEY NOT NULL,identity BLOB NOT NULL,verified INTEGER NOT NULL CHECK(verified=1))")
            db.execSQL("CREATE TABLE relay_queue(id TEXT PRIMARY KEY NOT NULL,digest BLOB NOT NULL,packet BLOB NOT NULL,local INTEGER NOT NULL,received_at INTEGER NOT NULL,expires_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX relay_expiry ON relay_queue(expires_at)")
            db.execSQL("CREATE TABLE seen_envelopes(id TEXT PRIMARY KEY NOT NULL,digest BLOB NOT NULL,retain_until INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX seen_expiry ON seen_envelopes(retain_until)")
            db.execSQL("CREATE TABLE message_history(id TEXT PRIMARY KEY NOT NULL,contact_id TEXT NOT NULL,encrypted_body BLOB NOT NULL,outgoing INTEGER NOT NULL,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,status TEXT NOT NULL,digest BLOB NOT NULL)")
            db.execSQL("CREATE INDEX history_contact ON message_history(contact_id,created_at)")
            db.execSQL("CREATE TABLE custody(envelope_id TEXT NOT NULL REFERENCES relay_queue(id) ON DELETE CASCADE,peer_id TEXT NOT NULL,acked_at INTEGER NOT NULL,PRIMARY KEY(envelope_id,peer_id))")
            db.execSQL("CREATE TABLE peer_backoff(peer_id TEXT PRIMARY KEY NOT NULL,failures INTEGER NOT NULL,next_allowed_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE mesh_stats(name TEXT PRIMARY KEY NOT NULL,value INTEGER NOT NULL)")
            db.execSQL("INSERT INTO mesh_stats VALUES('forwarded',0)")
            db.execSQL("CREATE TABLE diagnostics(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,level TEXT NOT NULL,message TEXT NOT NULL)")
        }

        private fun createJournal(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS inbound_frames(peer TEXT NOT NULL,transfer_id INTEGER NOT NULL,total INTEGER NOT NULL,sequence INTEGER NOT NULL,chunk BLOB NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(peer,transfer_id,sequence))")
            db.execSQL("CREATE INDEX IF NOT EXISTS inbound_age ON inbound_frames(updated_at)")
            db.execSQL("CREATE TABLE IF NOT EXISTS outbound_frames(peer TEXT NOT NULL,transfer_id INTEGER NOT NULL,payload BLOB NOT NULL,total INTEGER NOT NULL,acked_through INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(peer,transfer_id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS outbound_age ON outbound_frames(updated_at)")
        }
    }
}
