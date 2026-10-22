package dev.offlinechat.offlinechat.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** A fresh mutually signed challenge per BLE link, followed by replay-protected controls.
 * Control bytes are signed as transmitted; parsing JSON never changes the signed bytes.
 * BLE pairing and an advertised name are not treated as identity verification.
 */
class PeerSession(
    private val crypto: MeshCrypto,
    private val emit: (ByteArray) -> Unit,
    private val onAuthenticated: (PublicIdentity) -> Unit,
    private val onControl: (ByteArray) -> Unit,
) {
    private val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private var remoteNonce: ByteArray? = null
    var peer: PublicIdentity? = null
        private set
    var authenticated = false
        private set
    private var begun = false
    private var sentSequence = 0L
    private var receivedSequence = 0L
    private var transcript: ByteArray? = null

    fun begin() {
        check(!begun) { "Peer session was already started." }
        begun = true
        emit(wire(HELLO) { out ->
            val qr = crypto.exportIdentity().toByteArray(Charsets.UTF_8)
            require(qr.size in 1..MAX_IDENTITY_BYTES)
            out.writeInt(qr.size); out.write(qr); out.write(nonce)
        })
    }

    fun accept(bytes: ByteArray) {
        require(begun && bytes.size in 6..MAX_WIRE_BYTES) { "Invalid peer session frame size." }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readUnsignedByte() == 2) { "Unsupported peer session version." }
            when (input.readUnsignedByte()) {
                HELLO -> {
                    require(peer == null && !authenticated) { "Unexpected repeated identity challenge." }
                    val size = input.readInt()
                    require(size in 1..MAX_IDENTITY_BYTES && input.available() == size + 32) { "Malformed identity challenge." }
                    val qr = ByteArray(size).also(input::readFully)
                    val identity = crypto.parseIdentity(MeshRouter.strictUtf8(qr))
                    require(identity.id != crypto.identity().id) { "A device cannot connect to its own identity." }
                    remoteNonce = ByteArray(32).also(input::readFully)
                    peer = identity
                    transcript = challenge(identity)
                    val proof = crypto.sign(transcript!!)
                    require(proof.size == 64)
                    emit(wire(PROOF) { it.write(proof) })
                }
                PROOF -> {
                    val identity = peer ?: error("Identity challenge must precede proof.")
                    require(!authenticated && input.available() == 64) { "Invalid or repeated session proof." }
                    val signature = ByteArray(64).also(input::readFully)
                    require(crypto.verify(identity, transcript!!, signature)) { "Peer could not prove identity key possession." }
                    authenticated = true
                    onAuthenticated(identity)
                }
                CONTROL -> {
                    val identity = peer ?: error("No peer identity.")
                    require(authenticated) { "Authenticate before exchanging mesh controls." }
                    val sequence = input.readLong()
                    require(sequence == receivedSequence + 1 && sequence > 0) { "Replayed or reordered mesh control." }
                    val length = input.readInt()
                    require(length in 1..MAX_CONTROL_BYTES && input.available() == length + 64) { "Invalid mesh control length." }
                    val payload = ByteArray(length).also(input::readFully)
                    val signature = ByteArray(64).also(input::readFully)
                    require(crypto.verify(identity, controlBytes(sequence, payload), signature)) { "Invalid mesh control signature." }
                    receivedSequence = sequence
                    onControl(payload)
                }
                else -> error("Unsupported mesh control type.")
            }
            require(input.available() == 0) { "Trailing peer session bytes." }
        }
    }

    fun sendControl(payload: ByteArray) {
        check(authenticated) { "Peer session is not authenticated." }
        require(payload.size in 1..MAX_CONTROL_BYTES)
        check(sentSequence < Long.MAX_VALUE)
        val sequence = ++sentSequence
        val signature = crypto.sign(controlBytes(sequence, payload))
        require(signature.size == 64)
        emit(wire(CONTROL) { out ->
            out.writeLong(sequence); out.writeInt(payload.size); out.write(payload); out.write(signature)
        })
    }

    private fun challenge(identity: PublicIdentity): ByteArray = binary { out ->
        out.writeUTF("OfflineChat peer challenge v2")
        if (crypto.identity().id < identity.id) {
            out.writeUTF(crypto.identity().id); out.write(nonce)
            out.writeUTF(identity.id); out.write(remoteNonce!!)
        } else {
            out.writeUTF(identity.id); out.write(remoteNonce!!)
            out.writeUTF(crypto.identity().id); out.write(nonce)
        }
    }

    private fun controlBytes(sequence: Long, payload: ByteArray) = binary { out ->
        out.writeUTF("OfflineChat signed control v2")
        out.write(MessageDigest.getInstance("SHA-256").digest(transcript!!))
        out.writeLong(sequence); out.writeInt(payload.size); out.write(payload)
    }

    private fun wire(type: Int, block: (DataOutputStream) -> Unit) = binary { out ->
        out.writeInt(MAGIC); out.writeByte(2); out.writeByte(type); block(out)
    }

    companion object {
        const val MAX_CONTROL_BYTES = 48 * 1024
        const val MAX_WIRE_BYTES = MAX_CONTROL_BYTES + 82
        private const val MAX_IDENTITY_BYTES = 4096
        private const val MAGIC = 0x4f4d5332
        private const val HELLO = 1
        private const val PROOF = 2
        private const val CONTROL = 3
        private fun binary(block: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use(block)
        }.toByteArray()
    }
}
