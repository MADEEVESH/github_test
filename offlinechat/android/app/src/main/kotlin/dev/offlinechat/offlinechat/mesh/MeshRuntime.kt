package dev.offlinechat.offlinechat.mesh

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One process-wide coordinator. Database, crypto, sessions and router have one owner thread.
 * Activity recreation only replaces the event observer; it never restarts the radio/service.
 */
class MeshRuntime private constructor(private val context: Context) : MeshTransport.Listener {
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "OfflineChat-mesh") }
    private val main = Handler(Looper.getMainLooper())
    private val preferences = context.getSharedPreferences("mesh_runtime_v2", Context.MODE_PRIVATE)
    private var crypto: MeshCrypto? = null
    private var store: SqliteMeshStore? = null
    private var router: MeshRouter? = null
    private var transport: MeshTransport? = null
    private var foreground = false
    private var serviceFailure: String? = null
    private var radioState: Map<String, Any?> = mapOf("running" to false, "status" to "Stopped")
    private var observer: ((Map<String, Any?>) -> Unit)? = null
    private var lastTick = 0L
    private val links = linkedMapOf<String, Link>()

    private class Link(val address: String, val started: Long) {
        lateinit var session: PeerSession
        val remoteTokens = linkedSetOf<String>()
        val sentTokens = linkedSetOf<String>()
        var inventoryReady = false
        var remoteCycle: String? = null
        var expectedPage = 0
        var ownCycle = ""
        var ownTokens = emptyList<String>()
        var ownNextPage = 0
        var ownFinished = false
        var inventoryAt = 0L
        var inventoryProgressAt = 0L
        var inFlight: String? = null
        var sentAt = 0L
        var controlsSince = 0L
        var controls = 0
    }

    init {
        worker.scheduleAtFixedRate({ guarded {
            val now = System.currentTimeMillis()
            if (router != null) {
                router!!.cleanup()
                store!!.cleanupTransfers(now)
                links.values.toList().forEach { link ->
                    if (!link.session.authenticated && now - link.started > 120_000) {
                        drop(link, "Peer identity authentication timed out.")
                    } else if (link.session.authenticated && (!link.inventoryReady || !link.ownFinished) &&
                        now - link.inventoryProgressAt > TRANSFER_DEADLINE_MS) {
                        drop(link, "Peer inventory exchange stopped progressing.")
                    } else if (link.inFlight != null && now - link.sentAt > TRANSFER_DEADLINE_MS) {
                        drop(link, "Custody acknowledgement timed out; encrypted message remains queued.")
                    } else if (link.session.authenticated) {
                        if (now - link.inventoryAt > 90_000 && link.inFlight == null && link.ownFinished && link.inventoryReady) beginInventory(link)
                        pump(link)
                    }
                }
                if (now - lastTick > 15_000) { lastTick = now; publish() }
            }
        } }, 5, 5, TimeUnit.SECONDS)
    }

    fun observe(callback: ((Map<String, Any?>) -> Unit)?) {
        worker.execute {
            observer = callback
            if (callback != null) guarded { if (onboarded()) initialize(); publish() }
        }
    }

    /** All responses and events return to Android's UI thread for Flutter's codec. */
    fun call(method: String, args: Map<*, *>, complete: (Any?, Throwable?) -> Unit) {
        worker.execute {
            try {
                if (method != "onboard" && onboarded()) initialize()
                val value: Any? = when (method) {
                    "bootstrap" -> snapshot()
                    "onboard" -> {
                        if (!onboarded()) {
                            val name = requiredString(args, "name", 128)
                            val keys = IdentityCrypto.forAndroid(context, createIfMissing = true)
                            keys.rename(name)
                            crypto = keys
                            initialize()
                            check(preferences.edit().putBoolean("onboarded", true).commit()) { "Could not save onboarding." }
                            store!!.log("info", "Local encrypted identity created. Exchange QR fingerprints to verify contacts.")
                        }
                        snapshot()
                    }
                    "validateContact" -> {
                        requireReady()
                        contactMap(crypto!!.parseIdentity(requiredString(args, "qr", 4096)), false)
                    }
                    "addContact" -> {
                        requireReady()
                        require(args["verified"] == true) { "Confirm the fingerprint with this person before trusting the contact." }
                        val contact = crypto!!.parseIdentity(requiredString(args, "qr", 4096))
                        require(contact.id != crypto!!.identity().id) { "This is your own identity QR." }
                        store!!.addContact(contact, true)
                        store!!.log("info", "Contact fingerprint explicitly verified: ${contact.id.take(12)}")
                        publish(); snapshot()
                    }
                    "messages" -> {
                        requireReady()
                        val id = requiredString(args, "contactId", 64)
                        require(store!!.contact(id) != null) { "Unknown verified contact." }
                        router!!.messages(id)
                    }
                    "send" -> {
                        requireReady()
                        router!!.sendText(requiredString(args, "contactId", 64), requiredString(args, "text", 4096))
                        links.values.toList().forEach { pump(it) }
                        publish(); snapshot()
                    }
                    "start" -> {
                        requireReady()
                        val capabilities = capabilities()
                        require(capabilities["supported"] == true && capabilities["advertising"] == true &&
                            capabilities["bluetoothOn"] == true && capabilities["permissionsGranted"] == true) {
                            capabilities["reason"]?.toString() ?: "Enable Bluetooth and grant Nearby devices permission."
                        }
                        require(capabilities["reason"] == null) { capabilities["reason"].toString() }
                        if (radioState["running"] != true) {
                            serviceFailure = null
                            radioState = mapOf("running" to false, "status" to "Starting foreground mesh service…")
                            MeshForegroundService.start(context)
                        }
                        publish(); snapshot()
                    }
                    "stop" -> {
                        serviceFailure = null
                        MeshForegroundService.stop(context)
                        radioState = radioState + ("status" to "Stopping mesh…")
                        publish(); snapshot()
                    }
                    "updateSettings" -> {
                        requireReady()
                        val settings = MeshSettings(
                            args["relayEnabled"] as? Boolean ?: error("Relay setting is required."),
                            (args["maxHops"] as? Number)?.toInt() ?: error("Hop limit is required."),
                            (args["ttlSeconds"] as? Number)?.toLong() ?: error("TTL is required."),
                            (args["queueLimitBytes"] as? Number)?.toLong() ?: error("Queue limit is required."),
                        ).validated()
                        store!!.updateSettings(settings)
                        publish(); snapshot()
                    }
                    "diagnostics" -> store?.diagnostics() ?: emptyList<Map<String, Any?>>()
                    else -> throw UnsupportedOperationException("Unknown mesh method: $method")
                }
                main.post { complete(value, null) }
            } catch (error: Exception) {
                main.post { complete(null, error) }
            }
        }
    }

    fun startTransport() { worker.execute { guarded {
        requireReady(); initialize()
        store!!.log("info", "User started BLE mesh participation.")
        transport!!.start()
        publish()
    } } }

    fun stopTransport() { worker.execute { guarded {
        links.clear()
        transport?.stop()
        radioState = mapOf("running" to false, "status" to (serviceFailure ?: "Stopped"))
        store?.log("info", "BLE mesh stopped; encrypted queued messages are retained.")
        publish()
    } } }

    fun setForeground(value: Boolean) { worker.execute { foreground = value; guarded { publish() } } }

    fun reportServiceFailure(reason: String) { worker.execute { guarded {
        links.clear()
        transport?.stop()
        foreground = false
        serviceFailure = reason.take(240)
        radioState = mapOf("running" to false, "status" to reason.take(240))
        store?.log("error", reason.take(240))
        publish()
    } } }

    private fun initialize() {
        if (crypto == null) crypto = IdentityCrypto.forAndroid(context)
        if (store == null) store = SqliteMeshStore(context, crypto!!).also { it.writableDatabase }
        if (router == null) router = MeshRouter(store!!, crypto!!)
        if (transport == null) transport = BleMeshTransport(context, store!!).also { it.listener = this }
    }

    override fun onPeerReady(address: String) { worker.execute { guarded {
        if (links.containsKey(address)) return@guarded
        val link = Link(address, System.currentTimeMillis())
        link.session = PeerSession(crypto!!,
            emit = { bytes -> transport!!.send(address, bytes) },
            onAuthenticated = { identity ->
                require(links.values.none { it !== link && it.session.authenticated && it.session.peer?.id == identity.id }) {
                    "This peer identity already has an active link."
                }
                store!!.log("info", "Authenticated nearby identity ${identity.id.take(12)}")
                beginInventory(link); publish()
            },
            onControl = { payload -> control(link, payload) },
        )
        links[address] = link
        try { link.session.begin() }
        catch (error: Exception) { drop(link, error.message ?: "Could not start peer authentication.") }
        publish()
    } } }

    override fun onPeerDisconnected(address: String, reason: String) { worker.execute { guarded {
        val link = links.remove(address)
        if (link?.inFlight != null) link.session.peer?.let { router!!.peerFailed(it.id, reason) }
        publish()
    } } }

    override fun onPayload(address: String, payload: ByteArray) { worker.execute {
        val link = links[address] ?: return@execute
        try { link.session.accept(payload) }
        catch (error: Exception) { drop(link, error.message ?: "Invalid peer session.") }
    } }

    override fun onState(state: Map<String, Any?>) { worker.execute { guarded {
        radioState = if (serviceFailure == null) state else state + ("status" to serviceFailure)
        publish()
    } } }

    override fun onFailure(address: String?, reason: String) { worker.execute { guarded {
        store?.log("warning", reason.take(240))
        if (address != null) links[address]?.let { drop(it, reason) }
        else radioState = radioState + ("status" to reason.take(240))
        publish()
    } } }

    private fun control(link: Link, bytes: ByteArray) {
        val now = System.currentTimeMillis()
        if (now - link.controlsSince !in 0 until 60_000) { link.controlsSince = now; link.controls = 0 }
        require(++link.controls <= 256) { "Peer control rate limit exceeded." }
        val message = JSONObject(MeshRouter.strictUtf8(bytes))
        when (message.getString("t")) {
            "inventory" -> {
                val cycle = message.getString("cycle")
                val page = message.getInt("page")
                require(cycle.length == 36 && page in 0..31)
                if (page == 0) {
                    require(cycle != link.remoteCycle) { "Repeated inventory cycle." }
                    link.remoteCycle = cycle; link.expectedPage = 0; link.remoteTokens.clear()
                    link.inventoryReady = false
                }
                require(cycle == link.remoteCycle && page == link.expectedPage++) { "Inventory page was reordered." }
                val tokens = message.getJSONArray("tokens")
                require(tokens.length() <= INVENTORY_PAGE)
                for (index in 0 until tokens.length()) {
                    val token = tokens.getString(index)
                    require(TOKEN.matches(token)) { "Invalid inventory token." }
                    link.remoteTokens.add(token)
                }
                require(link.remoteTokens.size <= INVENTORY_LIMIT)
                link.inventoryProgressAt = now
                if (message.getBoolean("done")) {
                    link.inventoryReady = true; link.sentTokens.clear()
                    pump(link)
                } else {
                    require(page < 31)
                    send(link, JSONObject().put("t", "inventory_next").put("cycle", cycle).put("page", page + 1))
                }
            }
            "inventory_next" -> {
                require(message.getString("cycle") == link.ownCycle && message.getInt("page") == link.ownNextPage)
                link.inventoryProgressAt = now
                sendInventoryPage(link, link.ownNextPage)
            }
            "packet" -> {
                val encoded = message.getString("data")
                require(encoded.length <= 44_000) { "Mesh packet is oversized." }
                val packet = crypto!!.decodePacket(Base64.getDecoder().decode(encoded))
                val result = router!!.receive(packet, link.session.peer!!.id)
                if (result.accepted) {
                    send(link, JSONObject().put("t", "custody").put("id", result.id).put("digest", result.digest.hex()))
                    if (result.delivered && !result.duplicate && packet.envelope.kind == EnvelopeKind.TEXT) MeshForegroundService.notifyIncoming(context)
                    publish()
                    pump(link) // A signed delivery receipt may now be ready for its next hop.
                } else {
                    send(link, JSONObject().put("t", "defer").put("id", result.id)
                        .put("reason", result.rejectedReason ?: "Envelope was not accepted."))
                }
            }
            "custody" -> {
                val id = message.getString("id")
                val digest = message.getString("digest")
                val token = "$id:$digest"
                require(TOKEN.matches(token) && token == link.inFlight) { "Unsolicited or mismatched custody acknowledgement." }
                val digestBytes = digest.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                require(router!!.custody(id, link.session.peer!!.id, digestBytes)) { "Custody did not match a stored envelope." }
                link.sentTokens.add(token)
                link.inFlight = null
                pump(link); publish()
            }
            "defer" -> {
                require(link.inFlight?.substringBefore(':') == message.getString("id")) { "Unexpected custody deferral." }
                drop(link, "Peer deferred custody: ${message.optString("reason").take(120)}")
            }
            else -> error("Unknown signed mesh control.")
        }
    }

    private fun beginInventory(link: Link) {
        if (!link.session.authenticated) return
        link.ownCycle = UUID.randomUUID().toString()
        link.ownTokens = router!!.inventorySnapshot(INVENTORY_LIMIT).map { it.token }
        link.inventoryAt = System.currentTimeMillis()
        link.inventoryProgressAt = link.inventoryAt
        link.ownNextPage = 0
        link.ownFinished = false
        sendInventoryPage(link, 0)
    }

    private fun sendInventoryPage(link: Link, page: Int) {
        require(page in 0..31 && page == link.ownNextPage)
        val tokens = link.ownTokens.drop(page * INVENTORY_PAGE).take(INVENTORY_PAGE)
        val done = (page + 1) * INVENTORY_PAGE >= link.ownTokens.size
        link.ownNextPage = page + 1
        link.ownFinished = done
        send(link, JSONObject().put("t", "inventory").put("cycle", link.ownCycle).put("page", page)
            .put("done", done).put("tokens", JSONArray(tokens)))
    }

    private fun pump(link: Link) {
        if (!link.session.authenticated || !link.inventoryReady || link.inFlight != null || link.sentTokens.size >= INVENTORY_LIMIT) return
        // Two bounded sets fit the router's 8192-token reconciliation budget.
        val known = link.remoteTokens + link.sentTokens
        val packet = router!!.offers(link.session.peer!!.id, known, 1).firstOrNull() ?: return
        val token = "${packet.envelope.id}:${crypto!!.envelopeDigest(packet.envelope).hex()}"
        link.inFlight = token
        link.sentAt = System.currentTimeMillis()
        // The user's send already committed. Radio failure must not report that durable send as failed.
        try {
            send(link, JSONObject().put("t", "packet").put("data", Base64.getEncoder().encodeToString(crypto!!.encodePacket(packet))))
        } catch (_: Exception) { /* send() closed the broken session; next encounter reconciles. */ }
    }

    private fun send(link: Link, message: JSONObject) {
        try { link.session.sendControl(message.toString().toByteArray(Charsets.UTF_8)) }
        catch (error: Exception) {
            drop(link, error.message ?: "BLE control send failed.")
            throw error
        }
    }

    private fun drop(link: Link, reason: String) {
        if (links.remove(link.address) == null) return
        link.session.peer?.let { router?.peerFailed(it.id, reason) }
        store?.log("warning", "Peer disconnected: ${reason.take(200)}")
        transport?.disconnect(link.address)
        publish()
    }

    private fun snapshot(): Map<String, Any?> {
        val settings = store?.settings() ?: MeshSettings()
        val authenticated = links.values.filter { it.session.authenticated }
        val discovered = (radioState["peers"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        val peers = discovered.map { entry ->
            val address = entry["address"]?.toString().orEmpty()
            val link = links[address]
            mapOf("address" to address, "id" to link?.session?.peer?.id,
                "name" to (link?.session?.peer?.name ?: "Nearby mesh device"), "rssi" to entry["rssi"],
                "state" to if (link?.session?.authenticated == true) "authenticated" else if (link != null) "authenticating" else entry["state"])
        }.toMutableList()
        links.values.filter { link -> peers.none { it["address"] == link.address } }.forEach { link ->
            peers.add(mapOf("address" to link.address, "id" to link.session.peer?.id,
                "name" to (link.session.peer?.name ?: "Nearby mesh device"),
                "state" to if (link.session.authenticated) "authenticated" else "authenticating"))
        }
        return mapOf(
            "onboarded" to onboarded(),
            "identity" to crypto?.identity()?.let { mapOf("id" to it.id, "name" to it.name, "qr" to crypto!!.exportIdentity()) },
            "contacts" to (store?.contacts()?.map { contactMap(it, true) } ?: emptyList<Map<String, Any?>>()),
            "chats" to (router?.conversations() ?: emptyList<Map<String, Any?>>()),
            "peers" to peers,
            "settings" to mapOf("relayEnabled" to settings.relayEnabled, "maxHops" to settings.maxHops,
                "ttlSeconds" to settings.ttlSeconds, "queueLimitBytes" to settings.queueLimitBytes),
            "capabilities" to capabilities(),
            "mesh" to mapOf("running" to (radioState["running"] == true), "foreground" to foreground,
                "connections" to authenticated.size, "forwarded" to (store?.forwardedCount() ?: 0L),
                "queueBytes" to (store?.queueBytes() ?: 0L), "status" to (radioState["status"] ?: "Stopped")),
            "diagnostics" to (store?.diagnostics() ?: emptyList<Map<String, Any?>>()),
        )
    }

    private fun capabilities(): Map<String, Any?> {
        transport?.let { return it.capabilities() }
        val granted = requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val supported = adapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        var enabled = false
        var advertising = false
        if (granted && adapter != null) try {
            enabled = adapter.isEnabled
            advertising = adapter.isMultipleAdvertisementSupported
        } catch (_: SecurityException) { /* Permission can be revoked between check and call. */ }
        val location = Build.VERSION.SDK_INT > 30 || context.getSystemService(LocationManager::class.java).isLocationEnabled
        val reason = when {
            !supported -> "Bluetooth LE is unavailable on this device."
            !granted -> "Grant Nearby devices permission (Android 10/11 also requires location for BLE scanning)."
            !enabled -> "Turn on Bluetooth, then start the mesh."
            !advertising -> "This device does not support BLE peripheral advertising."
            !location -> "Enable Location services for BLE scanning on Android 10/11."
            else -> null
        }
        return mapOf("supported" to supported, "advertising" to advertising, "bluetoothOn" to enabled,
            "permissionsGranted" to granted, "reason" to reason)
    }

    private fun publish() {
        val listener = observer ?: return
        val value = snapshot()
        main.post { listener(value) }
    }

    private fun guarded(block: () -> Unit) {
        try { block() }
        catch (error: Exception) {
            val reason = error.message?.take(240) ?: "Mesh operation failed."
            try { store?.log("error", reason) } catch (_: Exception) { }
            radioState = radioState + ("status" to reason)
        }
    }

    private fun onboarded() = preferences.getBoolean("onboarded", false)
    private fun requireReady() { check(onboarded()) { "Create your local identity first." }; initialize() }
    private fun contactMap(identity: PublicIdentity, verified: Boolean) = mapOf("id" to identity.id, "name" to identity.name, "verified" to verified)
    private fun requiredString(args: Map<*, *>, key: String, max: Int): String = (args[key] as? String)?.also {
        require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= max) { "Invalid $key size." }
    } ?: throw IllegalArgumentException("$key is required.")

    companion object {
        private const val INVENTORY_PAGE = 128
        private const val INVENTORY_LIMIT = 4096
        // Full transfers at the minimum MTU may need minutes. Per-fragment/GATT timeouts
        // still detect a stalled radio promptly; this ceiling bounds an authenticated peer.
        private const val TRANSFER_DEADLINE_MS = 15 * 60_000L
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9a-f]{64}")
        @Volatile private var instance: MeshRuntime? = null
        fun get(context: Context): MeshRuntime = instance ?: synchronized(this) {
            instance ?: MeshRuntime(context.applicationContext).also { instance = it }
        }
        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}
