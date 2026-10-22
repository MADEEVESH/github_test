package dev.offlinechat.offlinechat.mesh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Runs Android SQLite itself, including file reopen and an additive v1->v2 migration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SqliteMeshStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()
    private val openStores = mutableListOf<SqliteMeshStore>()
    private val now = 1_700_000_000_000L

    private fun database(crypto: MeshCrypto, name: String = "mesh-${UUID.randomUUID()}.db"): SqliteMeshStore {
        if (name !in names) names += name
        return SqliteMeshStore(context, crypto, name).also { openStores += it }
    }

    @After fun closeDatabases() {
        openStores.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    @Test fun queueHistoryContactsSettingsAndCustodySurviveDatabaseReopen() {
        val a = IdentityCrypto.forTesting("Alice"); val c = IdentityCrypto.forTesting("Carol")
        val name = "durable-${UUID.randomUUID()}.db"
        val first = database(a, name)
        first.addContact(c.identity())
        val desired = MeshSettings(relayEnabled = false, maxHops = 4, ttlSeconds = 3600)
        first.updateSettings(desired)
        val router = MeshRouter(first, a) { now }
        val text = "sqlite must never contain this private message 3ee5b715"
        val id = router.sendText(c.identity().id, text)
        val original = first.envelope(id)!!
        assertTrue(router.custody(id, c.identity().id, original.digest))
        first.setBackoff(c.identity().id, Backoff(2, now + 4000))
        first.incrementForwarded()
        first.log("info", "durable diagnostic", now)
        first.close()

        val reopened = database(a, name)
        assertEquals(desired, reopened.settings())
        assertEquals(c.identity().id, reopened.contacts().single().id)
        assertArrayEquals(original.encoded, reopened.envelope(id)!!.encoded)
        assertArrayEquals(original.digest, reopened.seenDigest(id))
        assertTrue(reopened.hasCustody(id, c.identity().id))
        assertEquals(Backoff(2, now + 4000), reopened.backoff(c.identity().id))
        assertEquals(1L, reopened.forwardedCount())
        assertTrue(reopened.diagnostics().any { it["message"] == "durable diagnostic" })
        assertEquals("relayed", reopened.history(id)!!.status)
        assertEquals(text, MeshRouter(reopened, a) { now }.messages(c.identity().id).single()["text"])
        assertEquals(original.encoded.size.toLong(), reopened.queueBytes())
        reopened.close()
        assertFalse(containsBytes(context.getDatabasePath(name).readBytes(), text.toByteArray()))
    }

    @Test fun intermediateCiphertextSurvivesRestartAndReachesRecipientLater() {
        val a = IdentityCrypto.forTesting("Alice"); val b = IdentityCrypto.forTesting("Relay"); val c = IdentityCrypto.forTesting("Carol")
        val aStore = database(a); val cStore = database(c)
        aStore.addContact(c.identity()); cStore.addContact(a.identity())
        val aRouter = MeshRouter(aStore, a) { now }
        val id = aRouter.sendText(c.identity().id, "a later physical encounter")
        val relayName = "relay-${UUID.randomUUID()}.db"
        val first = database(b, relayName)
        val bRouter = MeshRouter(first, b) { now }
        assertTrue(bRouter.receive(aRouter.offers(b.identity().id, emptySet()).single(), a.identity().id).accepted)
        assertTrue(first.historyRows().isEmpty())
        first.close()
        val restarted = database(b, relayName)
        val laterRouter = MeshRouter(restarted, b) { now + 600_000 }
        val packet = laterRouter.offers(c.identity().id, emptySet()).single()
        assertEquals(id, packet.envelope.id)
        assertThrows(Exception::class.java) { b.open(packet.envelope) }
        val received = MeshRouter(cStore, c) { now + 600_000 }.receive(packet, b.identity().id)
        assertTrue(received.delivered)
        assertNotNull(received.receiptId)
    }

    @Test fun journalCommitsContiguousFragmentsAndAckProgressAcrossReopen() {
        val crypto = IdentityCrypto.forTesting("Journal")
        val name = "journal-${UUID.randomUUID()}.db"
        val first = database(crypto, name)
        assertTrue(first.recordInbound("AA:BB:CC:DD:EE:FF", 31, 3, 0, byteArrayOf(1, 2)))
        assertTrue(first.recordInbound("AA:BB:CC:DD:EE:FF", 31, 3, 1, byteArrayOf(3)))
        assertTrue(first.recordInbound("AA:BB:CC:DD:EE:FF", 31, 3, 1, byteArrayOf(3)))
        assertFalse(first.recordInbound("AA:BB:CC:DD:EE:FF", 31, 3, 1, byteArrayOf(99)))
        assertFalse(first.recordInbound("other", 32, 3, 2, byteArrayOf(4)))
        first.recordOutbound("AA:BB:CC:DD:EE:FF", 41, byteArrayOf(6, 7, 8), 3, -1)
        first.ackOutbound("AA:BB:CC:DD:EE:FF", 41, 0)
        first.close()

        val reopened = database(crypto, name)
        val chunks = reopened.readInbound("AA:BB:CC:DD:EE:FF", 31)
        assertEquals(2, chunks.size)
        assertArrayEquals(byteArrayOf(1, 2), chunks[0]); assertArrayEquals(byteArrayOf(3), chunks[1])
        assertFalse(reopened.recordInbound("AA:BB:CC:DD:EE:FF", 31, 4, 2, byteArrayOf(4)))
        assertTrue(reopened.recordInbound("AA:BB:CC:DD:EE:FF", 31, 3, 2, byteArrayOf(4)))
        reopened.ackOutbound("AA:BB:CC:DD:EE:FF", 41, 1)
        assertThrows(IllegalArgumentException::class.java) { reopened.ackOutbound("AA:BB:CC:DD:EE:FF", 41, 3) }
        reopened.writableDatabase.rawQuery("SELECT acked_through FROM outbound_frames WHERE transfer_id=41", null).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        reopened.clearInbound("AA:BB:CC:DD:EE:FF", 31)
        reopened.clearOutbound("AA:BB:CC:DD:EE:FF", 41)
        assertTrue(reopened.readInbound("AA:BB:CC:DD:EE:FF", 31).isEmpty())
    }

    @Test fun additiveMigrationKeepsCiphertextQueueAndHistoryAndAddsFragmentTables() {
        val a = IdentityCrypto.forTesting("Alice"); val c = IdentityCrypto.forTesting("Carol")
        val name = "migration-${UUID.randomUUID()}.db"
        val first = database(a, name)
        first.addContact(c.identity())
        val id = MeshRouter(first, a) { now }.sendText(c.identity().id, "survives v1 to v2")
        val digest = first.seenDigest(id)!!.copyOf()
        // Reproduce the actual v1 schema: all core tables, before BLE journals existed.
        first.writableDatabase.execSQL("DROP TABLE inbound_frames")
        first.writableDatabase.execSQL("DROP TABLE outbound_frames")
        first.writableDatabase.version = 1
        first.close()
        val upgraded = database(a, name)
        assertEquals(2, upgraded.writableDatabase.version)
        assertArrayEquals(digest, upgraded.envelope(id)!!.digest)
        assertEquals("survives v1 to v2", MeshRouter(upgraded, a) { now }.messages(c.identity().id).single()["text"])
        assertTrue(upgraded.recordInbound("peer", 1, 1, 0, byteArrayOf(1)))
        upgraded.recordOutbound("peer", 2, byteArrayOf(2), 1, -1)
    }

    @Test fun queueByteLimitRejectsWholeTransactionBeforeCustodyAndCanBeReclaimed() {
        val a = IdentityCrypto.forTesting("Alice"); val c = IdentityCrypto.forTesting("Carol")
        val store = database(a)
        store.addContact(c.identity()); store.updateSettings(MeshSettings(queueLimitBytes = 1_048_576))
        val router = MeshRouter(store, a) { now }
        val id = router.sendText(c.identity().id, "first")
        // Opaque persisted bytes exercise SQLite's byte accounting at the exact capacity edge.
        // No route is read from this capacity fixture; the next real signed envelope is refused.
        store.writableDatabase.execSQL("UPDATE relay_queue SET packet=zeroblob(1048570) WHERE id=?", arrayOf(id))
        assertThrows(IllegalStateException::class.java) { router.sendText(c.identity().id, "cannot fit") }
        assertEquals(1, store.historyRows().size)
        assertEquals(1_048_570L, store.queueBytes())
        store.cleanup(now + 86_400_001)
        assertEquals(0L, store.queueBytes())
        assertNotNull(store.seenDigest(id))
        store.cleanup(now + 86_400_000 + MeshRouter.CLOCK_SKEW_MS + 1)
        assertNull(store.seenDigest(id))
    }

    @Test fun duplicateAndConflictingIdsPreserveOriginalBytesAtomically() {
        val a = IdentityCrypto.forTesting("Alice"); val c = IdentityCrypto.forTesting("Carol")
        val store = database(a)
        store.addContact(c.identity())
        val id = MeshRouter(store, a) { now }.sendText(c.identity().id, "original")
        val original = store.envelope(id)!!
        assertEquals(StoreCommit.DUPLICATE, store.commit(original))
        val other = a.seal(c.identity(), EnvelopeKind.TEXT, "other".toByteArray(), now, 86400, 8).copy(id = id)
        val signed = other.copy(signature = a.sign(EnvelopeCodec.envelopeUnsigned(other)))
        val packet = RoutedEnvelope(signed)
        val conflict = QueuedEnvelope(packet, a.encodePacket(packet), a.envelopeDigest(signed), true, now)
        assertEquals(StoreCommit.CONFLICT, store.commit(conflict))
        assertArrayEquals(original.encoded, store.envelope(id)!!.encoded)
        assertEquals(1, store.historyRows().size)
    }

    @Test fun envelopeCountBudgetIncludesGeneratedReceiptAndCommitsNothingWhenOnlyOneSlotRemains() {
        val a = IdentityCrypto.forTesting("Alice"); val c = IdentityCrypto.forTesting("Carol")
        val aStore = database(a); val cStore = database(c)
        aStore.addContact(c.identity()); cStore.addContact(a.identity())
        val aRouter = MeshRouter(aStore, a) { now }; val cRouter = MeshRouter(cStore, c) { now }
        val fixtureId = cRouter.sendText(a.identity().id, "opaque capacity fixture")
        val db = cStore.writableDatabase
        var removable = ""
        db.beginTransaction()
        try {
            db.compileStatement("INSERT INTO relay_queue(id,digest,packet,local,received_at,expires_at) SELECT ?,digest,packet,local,received_at,expires_at FROM relay_queue WHERE id=?").use { statement ->
                repeat(SqliteMeshStore.MAX_QUEUED_ENVELOPES - 2) {
                    removable = UUID.randomUUID().toString()
                    statement.bindString(1, removable); statement.bindString(2, fixtureId)
                    statement.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val incomingId = aRouter.sendText(c.identity().id, "recipient needs two durable queue slots")
        val packet = aRouter.offers(c.identity().id, emptySet()).single()
        val refused = cRouter.receive(packet, a.identity().id)
        assertFalse(refused.accepted)
        assertNull(cStore.history(incomingId)); assertNull(cStore.seenDigest(incomingId))
        assertNull(refused.receiptId)
        db.rawQuery("SELECT count(*) FROM relay_queue", null).use { assertTrue(it.moveToFirst()); assertEquals(4095, it.getInt(0)) }
        db.delete("relay_queue", "id=?", arrayOf(removable))
        val retried = cRouter.receive(packet, a.identity().id)
        assertTrue(retried.delivered); assertNotNull(retried.receiptId)
        assertNotNull(cStore.history(incomingId)); assertNotNull(cStore.seenDigest(incomingId))
        db.rawQuery("SELECT count(*) FROM relay_queue", null).use { assertTrue(it.moveToFirst()); assertEquals(4096, it.getInt(0)) }
        assertThrows(IllegalStateException::class.java) { cRouter.sendText(a.identity().id, "count cap is full") }
        assertEquals(2, cStore.historyRows().size)
    }

    @Test fun invalidContactOrUnverifiedImportNeverEntersTrustedStore() {
        val a = IdentityCrypto.forTesting("Alice"); val c = IdentityCrypto.forTesting("Carol")
        val store = database(a)
        assertThrows(IllegalArgumentException::class.java) { store.addContact(c.identity(), false) }
        assertThrows(IllegalArgumentException::class.java) { store.addContact(c.identity().copy(signature = ByteArray(64))) }
        assertThrows(IllegalArgumentException::class.java) { store.addContact(a.identity()) }
        assertTrue(store.contacts().isEmpty())
        store.addContact(c.identity())
        assertEquals(c.identity().id, store.contact(c.identity().id)!!.id)
    }

    @Test fun staleJournalCleanupReclaimsBothDirectionsAndDiagnosticsStayBounded() {
        val store = database(IdentityCrypto.forTesting("Cleanup"))
        assertTrue(store.recordInbound("peer", 1, 1, 0, byteArrayOf(1)))
        store.recordOutbound("peer", 2, byteArrayOf(2), 1, -1)
        store.cleanupTransfers(System.currentTimeMillis())
        assertEquals(1, store.readInbound("peer", 1).size)
        store.writableDatabase.rawQuery("SELECT count(*) FROM outbound_frames", null).use {
            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
        }
        store.cleanupTransfers(System.currentTimeMillis() + 31 * 60 * 1000L)
        assertTrue(store.readInbound("peer", 1).isEmpty())
        store.writableDatabase.rawQuery("SELECT count(*) FROM outbound_frames", null).use {
            assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
        }
        repeat(205) { store.log("info", "event $it", now + it) }
        assertEquals(200, store.diagnostics().size)
        assertEquals("event 204", store.diagnostics().first()["message"])
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean =
        haystack.asList().windowed(needle.size).any { it == needle.asList() }
}
