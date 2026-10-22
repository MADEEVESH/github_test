package dev.offlinechat.offlinechat.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Actual Android BLE transport. Every mutable transport object is owned by its
 * HandlerThread; no Bluetooth callback mutates link state on a binder thread.
 * Frame ACKs are separate from mesh custody and recipient delivery receipts.
 */
@SuppressLint("MissingPermission")
class BleMeshTransport(context: Context, private val journal: FrameJournal) : MeshTransport {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("f4b98a12-828d-4bd0-8a4a-d473ce839f68")
        val RX_UUID: UUID = UUID.fromString("f4b98a13-828d-4bd0-8a4a-d473ce839f68")
        val TX_UUID: UUID = UUID.fromString("f4b98a14-828d-4bd0-8a4a-d473ce839f68")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MANUFACTURER_ID = 0xffff // Experimental, non-assigned test manufacturer.
        private const val MAX_LINKS = 3
        private const val MAX_NEARBY = 64
        private const val MAX_NATIVE_QUEUE = 64
        private const val SETUP_TIMEOUT = 20_000L
        private const val OP_TIMEOUT = 8_000L
    }

    @Volatile override var listener: MeshTransport.Listener? = null
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val thread = HandlerThread("offlinechat-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private val links = LinkedHashMap<String, Link>()
    private val nearby = LinkedHashMap<String, Seen>()
    private val backoff = LinkedHashMap<String, Backoff>()
    private var requested = false
    private var epoch = 0L
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var advertising = false
    private var serverReady = false
    private var lowBattery = false
    private var status = "Mesh stopped."
    private var localTag = ByteArray(4)
    private var lastStateAt = 0L
    private var serverWrite: ServerWrite? = null
    private var serverWriteAt = 0L
    private val serverControl = ArrayDeque<ServerWrite>()
    private val serverData = ArrayDeque<ServerWrite>()

    private enum class Stage { CONNECTING, MTU, DISCOVERING, SUBSCRIBING, READY }
    private inner class Link(val device: BluetoothDevice, val client: Boolean) {
        val address: String = device.address
        var gatt: BluetoothGatt? = null
        var rx: BluetoothGattCharacteristic? = null
        var stage = Stage.CONNECTING
        var ready = false
        var closed = false
        var mtu = 23
        val startedAt = now()
        var nativeBusy = false
        var nativeAt = 0L
        val controls = ArrayDeque<ByteArray>()
        val data = ArrayDeque<ByteArray>()
        val frames = ReliableFrames(address, journal,
            emit = { frame -> writeFrame(this, frame) },
            deliver = { bytes -> listener?.onPayload(address, bytes) },
            fail = { reason -> closeLink(this, reason, penalize = true) },
            clock = ::now)
    }
    private data class Seen(val device: BluetoothDevice, var rssi: Int, var tag: ByteArray, var lastSeen: Long)
    private data class Backoff(var failures: Int, var until: Long)
    private data class ServerWrite(val link: BleMeshTransport.Link, val bytes: ByteArray)

    private val radioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            post {
                when (action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        // Query the protected adapter state instead of trusting intent extras.
                        val enabled = runCatching { manager?.adapter?.isEnabled == true }.getOrDefault(false)
                        if (!enabled && requested) {
                            stopNative("Bluetooth is off. Queued messages are retained.", keepRequested = true)
                        } else if (enabled && requested && !scanning && !advertising) {
                            activate()
                        }
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                        val next = !plugged && level >= 0 && scale > 0 && level * 100 / scale <= 15
                        if (next != lowBattery) {
                            lowBattery = next
                            if (scanning) restartScanner()
                            emitState(true)
                        }
                    }
                }
            }
        }
    }


    override fun capabilities(): Map<String, Any?> {
        val ble = app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val adapter = runCatching { manager?.adapter }.getOrNull()
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE)
        } else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val granted = permissions.all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
        val on = granted && runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        val capable = on && runCatching {
            adapter != null && adapter.isMultipleAdvertisementSupported && adapter.bluetoothLeAdvertiser != null
        }.getOrDefault(false)
        val location = Build.VERSION.SDK_INT >= 31 || runCatching {
            app.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
        }.getOrDefault(false)
        val reason = when {
            !ble || adapter == null -> "This device has no Bluetooth Low Energy adapter."
            !granted -> if (Build.VERSION.SDK_INT >= 31) "Grant Nearby devices permission to start the mesh."
                else "Grant location permission for Bluetooth discovery on Android 10/11."
            !on -> "Turn on Bluetooth to start the mesh."
            !location -> "Turn on Location services for BLE discovery on Android 10/11."
            !capable -> "This adapter cannot advertise BLE. Mesh hosting/relaying needs peripheral support."
            else -> null
        }
        return mapOf("supported" to (ble && adapter != null), "advertising" to capable,
            "bluetoothOn" to on, "permissionsGranted" to granted, "locationEnabled" to location,
            "canStart" to (reason == null), "reason" to reason)
    }

    override fun start() = onWorker {
        if (requested && (scanning || advertising || server != null)) return@onWorker
        requested = true
        activate()
    }

    override fun stop() = onWorker { stopNative("Mesh stopped. Queued messages are retained.", false) }

    override fun send(address: String, payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= ReliableFrames.MAX_PAYLOAD_BYTES)
        onWorker {
            val link = links[address]
            check(requested && link != null && link.ready && !link.closed) { "BLE peer is not subscribed/connected." }
            link.frames.send(payload)
        }
    }

    override fun disconnect(address: String): Unit = onWorker {
        links[address]?.let { closeLink(it, "Peer connection closed.", penalize = true) }
        Unit
    }

    private fun activate() {
        if (!requested) return
        val caps = capabilities()
        if (caps["canStart"] != true) {
            status = caps["reason"]?.toString() ?: "BLE cannot start."
            requested = false
            listener?.onFailure(null, status)
            emitState(true)
            return
        }
        stopNative("Starting real Bluetooth transport…", keepRequested = true)
        val activeEpoch = ++epoch
        localTag = ByteArray(4).also { SecureRandom().nextBytes(it) }
        status = "Starting BLE scanner, GATT server and advertiser…"
        try {
            val adapter = checkNotNull(manager?.adapter)
            scanner = checkNotNull(adapter.bluetoothLeScanner)
            advertiser = checkNotNull(adapter.bluetoothLeAdvertiser)
            val characteristic = BluetoothGattCharacteristic(TX_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            characteristic.addDescriptor(BluetoothGattDescriptor(CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            tx = characteristic
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(RX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            service.addCharacteristic(characteristic)
            server = manager?.openGattServer(app, serverCallbacks(activeEpoch))
            check(server != null && server!!.addService(service)) { "Could not create the mesh GATT service." }
            startScanner(activeEpoch)
            journal.cleanupTransfers(System.currentTimeMillis())
        } catch (error: Exception) {
            failStartup("BLE startup failed: ${error.message}")
        }
        emitState(true)
    }

    private fun startScanner(activeEpoch: Long) {
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handleResult(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::handleResult) }
            private fun handleResult(result: ScanResult) {
                post {
                    if (activeEpoch != epoch || !requested || !scanning) return@post
                    try {
                        val tag = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
                        if (tag == null || tag.size != 4) return@post
                        val address = result.device.address
                        if (!nearby.containsKey(address) && nearby.size >= MAX_NEARBY) {
                            val oldest = nearby.entries.minByOrNull { it.value.lastSeen }
                            if (oldest != null && !links.containsKey(oldest.key)) nearby.remove(oldest.key)
                            if (nearby.size >= MAX_NEARBY) return@post
                        }
                        nearby[address] = Seen(result.device, result.rssi, tag.copyOf(), now())
                        maybeConnect(address)
                        emitState()
                    } catch (error: Exception) { listener?.onFailure(null, "BLE scan result failed: ${error.message}") }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                post { if (activeEpoch == epoch && requested) failStartup("BLE scan failed (code $errorCode).") }
            }
        }
        scanCallback = callback
        val settings = ScanSettings.Builder().setScanMode(
            if (lowBattery) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_BALANCED).build()
        scanner!!.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()), settings, callback)
        scanning = true // Android accepted the real scan request; failures arrive through onScanFailed.
    }

    private fun restartScanner() {
        val old = scanCallback
        runCatching { if (old != null) scanner?.stopScan(old) }
        scanning = false
        scanCallback = null
        if (!requested) return
        try { startScanner(epoch) } catch (error: Exception) { failStartup("Could not restart BLE scan: ${error.message}") }
    }

    private fun startAdvertising(activeEpoch: Long) {
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                post {
                    if (activeEpoch != epoch || !requested) return@post
                    advertising = true
                    status = "BLE mesh enabled. Waiting for nearby compatible peers."
                    emitState(true)
                }
            }
            override fun onStartFailure(errorCode: Int) {
                post { if (activeEpoch == epoch && requested) failStartup("BLE advertising failed (code $errorCode).") }
            }
        }
        advertiseCallback = callback
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true).setTimeout(0).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addManufacturerData(MANUFACTURER_ID, localTag).setIncludeDeviceName(false).build()
        advertiser!!.startAdvertising(settings, data, callback)
    }

    private fun maybeConnect(address: String) {
        if (!requested || !scanning || links.containsKey(address) || links.size >= MAX_LINKS) return
        val seen = nearby[address] ?: return
        if (now() - seen.lastSeen > 30_000 || (backoff[address]?.until ?: 0) > now()) return
        // Only the lower ephemeral tag initiates; both devices still advertise/scan.
        if (compareTags(localTag, seen.tag) >= 0) return
        val link = Link(seen.device, true)
        links[address] = link
        try {
            link.gatt = seen.device.connectGatt(app, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
            check(link.gatt != null) { "Android rejected the GATT connection request." }
        } catch (error: Exception) { closeLink(link, "BLE connect failed: ${error.message}", true) }
        emitState(true)
    }

    private fun serverCallbacks(activeEpoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(result: Int, service: BluetoothGattService) {
            post {
                if (activeEpoch != epoch || !requested || service.uuid != SERVICE_UUID) return@post
                if (result != BluetoothGatt.GATT_SUCCESS) { failStartup("Adding the mesh GATT service failed ($result)."); return@post }
                serverReady = true
                try { startAdvertising(activeEpoch) } catch (error: Exception) { failStartup("Could not advertise: ${error.message}") }
                emitState(true)
            }
        }
        override fun onConnectionStateChange(device: BluetoothDevice, result: Int, newState: Int) {
            post {
                if (activeEpoch != epoch || !requested) return@post
                val address = runCatching { device.address }.getOrNull() ?: return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && result == BluetoothGatt.GATT_SUCCESS) {
                    val existing = links[address]
                    if (existing != null || links.size >= MAX_LINKS) {
                        if (existing?.client != false) runCatching { server?.cancelConnection(device) }
                    } else links[address] = Link(device, false)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    links[address]?.takeIf { !it.client }?.let { closeLink(it, "BLE peer disconnected ($result).", true, false) }
                }
                emitState(true)
            }
        }
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            post { if (activeEpoch == epoch) links[device.address]?.takeIf { !it.client }?.let { updateMtu(it, mtu) } }
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray) {
            val copy = value.copyOf()
            post {
                if (activeEpoch != epoch) return@post
                val link = links[device.address]?.takeIf { !it.client && it.ready }
                val result = when {
                    preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    characteristic.uuid != RX_UUID || link == null -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    copy.size !in ReliableFrames.ACK_BYTES..ReliableFrames.MAX_ATT_PAYLOAD -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                if (result == BluetoothGatt.GATT_SUCCESS && link != null) link.frames.accept(copy)
                if (responseNeeded) runCatching { server?.sendResponse(device, requestId, result, 0, null) }
            }
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray) {
            val copy = value.copyOf()
            post {
                if (activeEpoch != epoch) return@post
                val link = links[device.address]?.takeIf { !it.client }
                val enabled = copy.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val disabled = copy.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                val valid = !preparedWrite && offset == 0 && descriptor.uuid == CCCD_UUID &&
                    descriptor.characteristic.uuid == TX_UUID && link != null && (enabled || disabled)
                if (responseNeeded) runCatching {
                    server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS
                        else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
                if (valid) {
                    if (enabled) markReady(link) else closeLink(link, "Peer disabled mesh notifications.", true)
                }
            }
        }
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int,
            offset: Int, descriptor: BluetoothGattDescriptor) {
            post {
                if (activeEpoch != epoch) return@post
                val value = if (links[device.address]?.ready == true) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                runCatching { server?.sendResponse(device, requestId,
                    if (offset == 0 && descriptor.uuid == CCCD_UUID) BluetoothGatt.GATT_SUCCESS
                    else BluetoothGatt.GATT_INVALID_OFFSET, 0, if (offset == 0) value else null) }
            }
        }
        override fun onNotificationSent(device: BluetoothDevice, result: Int) {
            post {
                if (activeEpoch != epoch) return@post
                val pending = serverWrite ?: return@post
                if (pending.link.address != device.address) return@post
                serverWrite = null
                if (result != BluetoothGatt.GATT_SUCCESS) closeLink(pending.link, "BLE notification failed ($result).", true)
                drainServer()
            }
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, result: Int, newState: Int) {
            post {
                val link = clientLink(gatt) ?: return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && result == BluetoothGatt.GATT_SUCCESS) {
                    link.stage = Stage.MTU
                    val requestedMtu = runCatching { gatt.requestMtu(247) }.getOrDefault(false)
                    if (!requestedMtu) discover(link)
                    else handler.postDelayed({ if (!link.closed && link.stage == Stage.MTU)
                        closeLink(link, "MTU negotiation callback timed out; reconnect to retry.", true) }, OP_TIMEOUT)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || result != BluetoothGatt.GATT_SUCCESS) {
                    closeLink(link, "BLE connection ended ($result).", true)
                }
            }
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, result: Int) {
            post {
                val link = clientLink(gatt) ?: return@post
                if (result == BluetoothGatt.GATT_SUCCESS) updateMtu(link, mtu)
                if (link.stage == Stage.MTU) discover(link)
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, result: Int) {
            post {
                val link = clientLink(gatt) ?: return@post
                if (result != BluetoothGatt.GATT_SUCCESS) { closeLink(link, "GATT service discovery failed ($result).", true); return@post }
                try {
                    val service = checkNotNull(gatt.getService(SERVICE_UUID)) { "Mesh service is missing." }
                    val rx = checkNotNull(service.getCharacteristic(RX_UUID)) { "Mesh RX characteristic is missing." }
                    val notify = checkNotNull(service.getCharacteristic(TX_UUID)) { "Mesh TX characteristic is missing." }
                    val descriptor = checkNotNull(notify.getDescriptor(CCCD_UUID)) { "Mesh subscription descriptor is missing." }
                    check(rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 &&
                        notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) { "Peer has incompatible GATT properties." }
                    link.rx = rx
                    check(gatt.setCharacteristicNotification(notify, true)) { "Could not enable mesh notifications." }
                    link.stage = Stage.SUBSCRIBING
                    link.nativeBusy = true
                    link.nativeAt = now()
                    val accepted = if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                    check(accepted) { "Android rejected the mesh subscription." }
                } catch (error: Exception) { closeLink(link, "Could not subscribe to peer: ${error.message}", true) }
            }
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, result: Int) {
            post {
                val link = clientLink(gatt) ?: return@post
                if (descriptor.uuid != CCCD_UUID || link.stage !in listOf(Stage.SUBSCRIBING, Stage.READY)) return@post
                link.nativeBusy = false
                if (result != BluetoothGatt.GATT_SUCCESS) { closeLink(link, "GATT subscription failed ($result).", true); return@post }
                markReady(link)
                drainClient(link)
            }
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) receive(gatt, characteristic.uuid, characteristic.value?.copyOf() ?: byteArrayOf())
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(gatt, characteristic.uuid, value.copyOf())
        }
        private fun receive(gatt: BluetoothGatt, uuid: UUID, bytes: ByteArray) {
            post {
                val link = clientLink(gatt) ?: return@post
                if (uuid != TX_UUID || link.stage !in listOf(Stage.SUBSCRIBING, Stage.READY)) return@post
                // An actual TX notification also proves the CCCD subscription took effect.
                if (!link.ready) markReady(link)
                link.frames.accept(bytes)
            }
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, result: Int) {
            post {
                val link = clientLink(gatt) ?: return@post
                if (characteristic.uuid != RX_UUID) return@post
                link.nativeBusy = false
                if (result != BluetoothGatt.GATT_SUCCESS) { closeLink(link, "GATT frame write failed ($result).", true); return@post }
                drainClient(link) // Native write success is not the protocol ACK.
            }
        }
    }

    private fun clientLink(gatt: BluetoothGatt): Link? {
        val link = runCatching { links[gatt.device.address] }.getOrNull()
        return link?.takeIf { it.client && it.gatt === gatt && !it.closed }
    }

    private fun discover(link: Link) {
        if (link.closed || link.stage == Stage.DISCOVERING || link.ready) return
        link.stage = Stage.DISCOVERING
        try { check(link.gatt?.discoverServices() == true) { "Android rejected discovery." } }
        catch (error: Exception) { closeLink(link, "Could not discover peer services: ${error.message}", true) }
    }

    private fun updateMtu(link: Link, mtu: Int) {
        if (link.closed || mtu < 23) return
        link.mtu = mtu
        link.frames.setPayloadSize((mtu - 3).coerceIn(20, 244))
    }

    private fun markReady(link: Link) {
        if (link.closed || link.ready) return
        link.ready = true
        link.stage = Stage.READY
        backoff.remove(link.address)
        listener?.onPeerReady(link.address)
        emitState(true)
    }

    private fun writeFrame(link: Link, bytes: ByteArray) {
        check(!link.closed && link.ready) { "BLE peer is not ready for a frame." }
        if (link.client) {
            check(link.controls.size + link.data.size < MAX_NATIVE_QUEUE) { "Native GATT write queue exceeded its limit." }
            (if (ReliableFrames.isAck(bytes)) link.controls else link.data).addLast(bytes.copyOf())
            drainClient(link)
        } else {
            check(serverControl.size + serverData.size < MAX_NATIVE_QUEUE) { "Native notification queue exceeded its limit." }
            val write = ServerWrite(link, bytes.copyOf())
            (if (ReliableFrames.isAck(bytes)) serverControl else serverData).addLast(write)
            drainServer()
        }
    }

    private fun drainClient(link: Link) {
        if (link.closed || !link.ready || link.nativeBusy) return
        val bytes = if (!link.controls.isEmpty()) link.controls.removeFirst()
            else if (!link.data.isEmpty()) link.data.removeFirst() else return
        try {
            val gatt = checkNotNull(link.gatt)
            val characteristic = checkNotNull(link.rx)
            link.nativeBusy = true
            link.nativeAt = now()
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            check(accepted) { "Android rejected a GATT frame write." }
        } catch (error: Exception) { closeLink(link, "BLE write failed: ${error.message}", true) }
    }

    private fun drainServer() {
        if (serverWrite != null) return
        var next: ServerWrite? = null
        while (next == null && (!serverControl.isEmpty() || !serverData.isEmpty())) {
            val candidate = if (!serverControl.isEmpty()) serverControl.removeFirst() else serverData.removeFirst()
            if (!candidate.link.closed && candidate.link.ready) next = candidate
        }
        val write = next ?: return
        try {
            val activeServer = checkNotNull(server)
            val characteristic = checkNotNull(tx)
            serverWrite = write
            serverWriteAt = now()
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                activeServer.notifyCharacteristicChanged(write.link.device, characteristic, false, write.bytes) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = write.bytes
                @Suppress("DEPRECATION")
                activeServer.notifyCharacteristicChanged(write.link.device, characteristic, false)
            }
            check(accepted) { "Android rejected a peer-specific notification." }
        } catch (error: Exception) {
            serverWrite = null
            closeLink(write.link, "BLE notify failed: ${error.message}", true)
            drainServer()
        }
    }

    private fun closeLink(link: Link, reason: String, penalize: Boolean, cancel: Boolean = true) {
        if (link.closed) return
        link.closed = true
        if (links[link.address] === link) links.remove(link.address)
        link.controls.clear()
        link.data.clear()
        serverControl.removeAll { it.link === link }
        serverData.removeAll { it.link === link }
        if (serverWrite?.link === link) serverWrite = null
        // closed is set before frames.close, so its error callback cannot recurse.
        link.frames.close(reason)
        runCatching { if (link.client) { link.gatt?.disconnect(); link.gatt?.close() }
            else if (cancel) server?.cancelConnection(link.device) }
        if (penalize) {
            val previous = backoff[link.address]?.failures ?: 0
            val failures = (previous + 1).coerceAtMost(6)
            backoff[link.address] = Backoff(failures, now() + (2_000L shl failures).coerceAtMost(120_000L))
            while (backoff.size > 128) backoff.remove(backoff.keys.first())
            listener?.onFailure(link.address, reason)
        }
        listener?.onPeerDisconnected(link.address, reason)
        emitState(true)
        drainServer()
    }

    private fun failStartup(reason: String) {
        stopNative(reason, false)
        listener?.onFailure(null, reason)
    }

    private fun stopNative(reason: String, keepRequested: Boolean) {
        requested = keepRequested
        ++epoch
        runCatching { scanCallback?.let { scanner?.stopScan(it) } }
        runCatching { advertiseCallback?.let { advertiser?.stopAdvertising(it) } }
        scanning = false
        advertising = false
        serverReady = false
        scanCallback = null
        advertiseCallback = null
        scanner = null
        advertiser = null
        serverControl.clear()
        serverData.clear()
        serverWrite = null
        links.values.toList().forEach { closeLink(it, reason, false) }
        runCatching { server?.clearServices(); server?.close() }
        server = null
        tx = null
        status = reason
        emitState(true)
    }

    private val ticker = object : Runnable {
        override fun run() {
            try {
                val time = now()
                for (link in links.values.toList()) {
                    if (!link.ready && time - link.startedAt >= SETUP_TIMEOUT) closeLink(link, "BLE setup/subscription timed out.", true)
                    else if (link.nativeBusy && time - link.nativeAt >= OP_TIMEOUT) closeLink(link, "Android GATT operation timed out.", true)
                    else if (link.ready) link.frames.tick(time)
                }
                serverWrite?.takeIf { time - serverWriteAt >= OP_TIMEOUT }?.let {
                    serverWrite = null
                    closeLink(it.link, "Android notification completion timed out.", true)
                }
                nearby.entries.removeAll { time - it.value.lastSeen > 120_000 && !links.containsKey(it.key) }
                if (requested) nearby.keys.toList().forEach(::maybeConnect)
                if (requested) emitState()
            } catch (error: Exception) { listener?.onFailure(null, "BLE maintenance failed: ${error.message}") }
            finally { handler.postDelayed(this, 1_000) }
        }
    }

    init {
        post {
            try {
                val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                }
                // These are protected system broadcasts; Bluetooth is sent by a privileged UID.
                ContextCompat.registerReceiver(app, radioReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            } catch (error: Exception) {
                listener?.onFailure(null, "Could not observe Bluetooth/battery state: ${error.message}")
            }
            handler.post(ticker)
        }
    }


    private fun emitState(force: Boolean = false) {
        val time = now()
        if (!force && time - lastStateAt < 1_000) return
        lastStateAt = time
        val peers = LinkedHashMap<String, Map<String, Any?>>()
        nearby.forEach { (address, seen) -> peers[address] = mapOf("address" to address,
            "rssi" to seen.rssi, "state" to "nearby", "lastSeen" to seen.lastSeen) }
        links.forEach { (address, link) -> peers[address] = mapOf("address" to address,
            "rssi" to nearby[address]?.rssi, "state" to if (link.ready) "authenticating" else "connecting",
            "mtu" to link.mtu, "lastSeen" to (nearby[address]?.lastSeen ?: time)) }
        listener?.onState(mapOf("advertising" to advertising, "scanning" to scanning,
            "serverReady" to serverReady, "running" to (advertising && scanning && serverReady),
            "status" to if (lowBattery && scanning) "$status Low battery: discovery uses low-power scanning." else status,
            "batteryLow" to lowBattery, "connections" to links.values.count { it.ready },
            "peers" to peers.values.toList()))
    }

    private fun post(block: () -> Unit) {
        handler.post {
            try { block() }
            catch (error: SecurityException) { failStartup("Bluetooth permission was revoked. Grant Nearby devices permission and start again.") }
            catch (error: Exception) { failStartup("BLE callback failed: ${error.message}") }
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()
    private fun compareTags(a: ByteArray, b: ByteArray): Int {
        for (i in 0..3) { val result = (a[i].toInt() and 255).compareTo(b[i].toInt() and 255); if (result != 0) return result }
        return 0
    }
    private fun <T> onWorker(block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        val task = FutureTask(block)
        check(handler.post(task)) { "BLE worker stopped." }
        return try { task.get(10, TimeUnit.SECONDS) }
        catch (error: Exception) { throw IllegalStateException("BLE operation could not complete: ${error.cause?.message ?: error.message}", error) }
    }
}
