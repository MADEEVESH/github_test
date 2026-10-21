package dev.offlinechat.offlinechat.mesh

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** Canonical v2 binary encoding. This is framing, not a cryptographic primitive. */
object EnvelopeCodec {
    const val VERSION = 2
    const val MAX_PLAINTEXT_BYTES = 1024
    const val MAX_PACKET_BYTES = 32 * 1024
    const val MAX_IDENTITY_BYTES = 2048
    const val MAX_CIPHERTEXT_BYTES = 2048
    const val MAX_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    private const val IDENTITY_MAGIC = 0x4f434932 // OCI2
    private const val PACKET_MAGIC = 0x4f434d32 // OCM2
    private const val ENVELOPE_MAGIC = 0x4f434532 // OCE2
    private val ID = Regex("[0-9a-f]{64}")

    fun validId(id: String): Boolean = ID.matches(id)

    fun checkedName(name: String): String {
        require(name.isNotBlank() && name.codePointCount(0, name.length) <= 32) {
            "Display name must contain between 1 and 32 characters."
        }
        require(name.none { it.code < 32 || it.code == 127 }) { "Display name contains control characters." }
        require(name.toByteArray(StandardCharsets.UTF_8).size <= 128) { "Display name is too long." }
        // Reject unpaired UTF-16 surrogates instead of silently replacing them.
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(name))
        return name
    }

    fun fingerprint(signingKey: ByteArray, encryptionKey: ByteArray): String =
        digest(bytes("offlinechat.identity.keys.v2") { out ->
            field(out, signingKey, 512)
            field(out, encryptionKey, 512)
        }).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun identityUnsigned(identity: PublicIdentity): ByteArray {
        validateIdentityShape(identity)
        return bytes("offlinechat.identity.signature.v2") { out ->
            out.write(hex(identity.id))
            field(out, checkedName(identity.name).toByteArray(StandardCharsets.UTF_8), 128)
            field(out, identity.signingKey, 512)
            field(out, identity.encryptionKey, 512)
        }
    }

    fun encodeIdentity(identity: PublicIdentity): ByteArray {
        validateIdentityShape(identity)
        require(identity.signature.size in 1..128) { "Invalid identity signature size." }
        return raw { out ->
            out.writeInt(IDENTITY_MAGIC)
            out.writeByte(VERSION)
            out.write(hex(identity.id))
            field(out, identity.name.toByteArray(StandardCharsets.UTF_8), 128)
            field(out, identity.signingKey, 512)
            field(out, identity.encryptionKey, 512)
            field(out, identity.signature, 128)
        }.also { require(it.size <= MAX_IDENTITY_BYTES) }
    }

    fun decodeIdentity(data: ByteArray): PublicIdentity = parse(data, MAX_IDENTITY_BYTES) { input ->
        require(input.readInt() == IDENTITY_MAGIC && input.readUnsignedByte() == VERSION) { "Unsupported identity version." }
        readIdentityFields(input)
    }

    fun envelopeHeader(envelope: SealedEnvelope): ByteArray {
        validateEnvelopeShape(envelope, requireBody = false)
        return bytes("offlinechat.envelope.header.v2") { out ->
            val uuid = UUID.fromString(envelope.id)
            out.writeLong(uuid.mostSignificantBits)
            out.writeLong(uuid.leastSignificantBits)
            field(out, encodeIdentity(envelope.sender), MAX_IDENTITY_BYTES)
            out.write(hex(envelope.recipientId))
            out.writeLong(envelope.createdAt)
            out.writeLong(envelope.expiresAt)
            out.writeByte(envelope.maxHops)
            out.writeByte(envelope.kind.ordinal)
        }
    }

    fun envelopeUnsigned(envelope: SealedEnvelope): ByteArray {
        validateEnvelopeShape(envelope, requireBody = true)
        return bytes("offlinechat.envelope.signature.v2") { out ->
            field(out, envelopeHeader(envelope), 4096)
            field(out, envelope.ciphertext, MAX_CIPHERTEXT_BYTES)
        }
    }

    fun encodeEnvelope(envelope: SealedEnvelope): ByteArray {
        validateEnvelopeShape(envelope, requireBody = true)
        require(envelope.signature.size in 1..128) { "Invalid envelope signature size." }
        return raw { out ->
            out.writeInt(ENVELOPE_MAGIC)
            out.writeByte(VERSION)
            val uuid = UUID.fromString(envelope.id)
            out.writeLong(uuid.mostSignificantBits)
            out.writeLong(uuid.leastSignificantBits)
            field(out, encodeIdentity(envelope.sender), MAX_IDENTITY_BYTES)
            out.write(hex(envelope.recipientId))
            out.writeLong(envelope.createdAt)
            out.writeLong(envelope.expiresAt)
            out.writeByte(envelope.maxHops)
            out.writeByte(envelope.kind.ordinal)
            field(out, envelope.ciphertext, MAX_CIPHERTEXT_BYTES)
            field(out, envelope.signature, 128)
        }
    }

    fun decodeEnvelope(data: ByteArray): SealedEnvelope = parse(data, 8192) { input ->
        require(input.readInt() == ENVELOPE_MAGIC && input.readUnsignedByte() == VERSION) { "Unsupported envelope version." }
        val id = UUID(input.readLong(), input.readLong()).toString()
        val sender = decodeIdentity(field(input, MAX_IDENTITY_BYTES))
        val recipient = readId(input)
        val created = input.readLong()
        val expires = input.readLong()
        val hops = input.readUnsignedByte()
        val kind = input.readUnsignedByte()
        require(kind in EnvelopeKind.entries.indices) { "Invalid envelope kind." }
        SealedEnvelope(id, sender, recipient, created, expires, hops, EnvelopeKind.entries[kind],
            field(input, MAX_CIPHERTEXT_BYTES), field(input, 128)).also {
            validateEnvelopeShape(it, requireBody = true)
            require(it.signature.isNotEmpty()) { "Missing envelope signature." }
        }
    }

    fun encodePacket(packet: RoutedEnvelope): ByteArray = raw { out ->
        require(packet.route.size <= packet.envelope.maxHops && packet.route.size <= 16) { "Hop budget exceeded." }
        out.writeInt(PACKET_MAGIC)
        out.writeByte(VERSION)
        field(out, encodeEnvelope(packet.envelope), 8192)
        out.writeByte(packet.route.size)
        for (hop in packet.route) {
            field(out, encodeIdentity(hop.from), MAX_IDENTITY_BYTES)
            out.write(hex(hop.toId))
            require(hop.index in 1..16 && hop.previous.size == 32 && hop.signature.size in 1..128) { "Invalid route field." }
            out.writeByte(hop.index)
            out.write(hop.previous)
            field(out, hop.signature, 128)
        }
    }.also { require(it.size <= MAX_PACKET_BYTES) { "Envelope exceeds the mesh packet limit." } }

    fun decodePacket(data: ByteArray): RoutedEnvelope = parse(data, MAX_PACKET_BYTES) { input ->
        require(input.readInt() == PACKET_MAGIC && input.readUnsignedByte() == VERSION) { "Unsupported mesh version." }
        val envelope = decodeEnvelope(field(input, 8192))
        val count = input.readUnsignedByte()
        require(count <= envelope.maxHops && count <= 16) { "Hop budget exceeded." }
        val hops = ArrayList<HopProof>(count)
        repeat(count) {
            val from = decodeIdentity(field(input, MAX_IDENTITY_BYTES))
            val to = readId(input)
            val index = input.readUnsignedByte()
            require(index in 1..16) { "Invalid hop index." }
            val previous = ByteArray(32).also(input::readFully)
            val signature = field(input, 128)
            require(signature.isNotEmpty()) { "Missing route signature." }
            hops.add(HopProof(from, to, index, previous, signature))
        }
        RoutedEnvelope(envelope, hops)
    }

    fun routeSeed(envelopeDigest: ByteArray): ByteArray = digest(bytes("offlinechat.route.start.v2") { it.write(envelopeDigest) })

    fun hopUnsigned(envelopeDigest: ByteArray, hop: HopProof): ByteArray = bytes("offlinechat.route.edge.v2") { out ->
        require(envelopeDigest.size == 32 && hop.previous.size == 32 && hop.index in 1..16)
        out.write(envelopeDigest)
        field(out, encodeIdentity(hop.from), MAX_IDENTITY_BYTES)
        out.write(hex(hop.toId))
        out.writeByte(hop.index)
        out.write(hop.previous)
    }

    fun hopDigest(envelopeDigest: ByteArray, hop: HopProof): ByteArray = digest(raw { out ->
        field(out, hopUnsigned(envelopeDigest, hop), 4096)
        field(out, hop.signature, 128)
    })

    fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun validateIdentityShape(identity: PublicIdentity) {
        require(validId(identity.id)) { "Invalid identity fingerprint." }
        checkedName(identity.name)
        require(identity.signingKey.size in 1..512 && identity.encryptionKey.size in 1..512) { "Invalid public key size." }
    }

    fun validateEnvelopeShape(envelope: SealedEnvelope, requireBody: Boolean) {
        val id = UUID.fromString(envelope.id)
        require(id.toString() == envelope.id && id.version() == 4 && id.variant() == 2) { "Invalid unique message ID." }
        validateIdentityShape(envelope.sender)
        require(validId(envelope.recipientId) && envelope.recipientId != envelope.sender.id) { "Invalid recipient identity." }
        require(envelope.createdAt > 0 && envelope.expiresAt > envelope.createdAt &&
            envelope.expiresAt - envelope.createdAt <= MAX_TTL_MILLIS) { "Invalid message lifetime." }
        require(envelope.maxHops in 1..16) { "Invalid signed hop budget." }
        if (requireBody) require(envelope.ciphertext.size in 1..MAX_CIPHERTEXT_BYTES) { "Invalid encrypted body length." }
    }

    private fun readIdentityFields(input: DataInputStream): PublicIdentity {
        val identity = PublicIdentity(readId(input), decodeUtf8(field(input, 128)), field(input, 512), field(input, 512), field(input, 128))
        validateIdentityShape(identity)
        require(identity.signature.isNotEmpty()) { "Missing identity signature." }
        return identity
    }

    private fun readId(input: DataInputStream): String = ByteArray(32).also(input::readFully)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hex(id: String): ByteArray {
        require(validId(id)) { "Invalid identity fingerprint." }
        return ByteArray(32) { id.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun field(out: DataOutputStream, value: ByteArray, max: Int) {
        require(value.size <= max) { "Field exceeds its limit." }
        out.writeInt(value.size)
        out.write(value)
    }

    private fun field(input: DataInputStream, max: Int): ByteArray {
        val length = input.readInt()
        require(length in 0..max && length <= input.available()) { "Invalid or truncated field length." }
        return ByteArray(length).also(input::readFully)
    }

    private fun bytes(domain: String, write: (DataOutputStream) -> Unit): ByteArray = raw {
        field(it, domain.toByteArray(StandardCharsets.US_ASCII), 128)
        write(it)
    }

    private fun raw(write: (DataOutputStream) -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(write)
        return buffer.toByteArray()
    }

    private fun <T> parse(data: ByteArray, max: Int, read: (DataInputStream) -> T): T {
        require(data.size in 1..max) { "Invalid or oversized binary document." }
        val input = DataInputStream(ByteArrayInputStream(data))
        return read(input).also { require(input.available() == 0) { "Trailing binary data is not permitted." } }
    }
}
