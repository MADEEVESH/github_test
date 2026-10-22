package dev.offlinechat.offlinechat.mesh

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.PredefinedHybridParameters
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Real Tink primitives: no crypto or verification mocks. */
class IdentityCryptoTest {
    private val alice = IdentityCrypto.forTesting("Alice")
    private val bob = IdentityCrypto.forTesting("Bob")
    private val carol = IdentityCrypto.forTesting("Carol")
    private val now = 1_800_000_000_000L

    @Test fun recipientDecryptsButRelayAndSenderCannot() {
        val envelope = message()
        assertTrue(bob.verifyEnvelope(envelope))
        assertArrayEquals("secret for Carol".toByteArray(), carol.open(envelope))
        rejects { bob.open(envelope) }
        rejects { alice.open(envelope) }
        val second = message()
        assertFalse(envelope.ciphertext.contentEquals(second.ciphertext))
        assertFalse(String(alice.encodePacket(RoutedEnvelope(envelope)), StandardCharsets.ISO_8859_1).contains("secret for Carol"))
    }

    @Test fun envelopeAuthenticatesEveryImmutableField() {
        val envelope = message()
        val changes = listOf(
            envelope.copy(id = "9530ff22-c304-470c-988f-778dd8f592df"),
            envelope.copy(recipientId = bob.identity().id),
            envelope.copy(createdAt = now + 1),
            envelope.copy(expiresAt = envelope.expiresAt + 1),
            envelope.copy(maxHops = 7),
            envelope.copy(kind = EnvelopeKind.RECEIPT),
            envelope.copy(ciphertext = flip(envelope.ciphertext)),
            envelope.copy(signature = flip(envelope.signature)),
            envelope.copy(sender = envelope.sender.copy(name = "Impersonator")),
        )
        changes.forEach {
            assertFalse("Tampered field accepted: $it", carol.verifyEnvelope(it))
            rejects { carol.open(it) }
        }
    }

    @Test fun hpkeContextRejectsChangedHeaderEvenWithNewValidSignature() {
        val envelope = message()
        val changed = envelope.copy(maxHops = 2, signature = byteArrayOf())
        val signed = changed.copy(signature = alice.sign(EnvelopeCodec.envelopeUnsigned(changed)))
        assertTrue(carol.verifyEnvelope(signed))
        rejects { carol.open(signed) }
    }

    @Test fun selfSignedIdentityQrBindsBothKeysAndName() {
        val identity = alice.parseIdentity(alice.exportIdentity())
        assertEquals(alice.identity().id, identity.id)
        assertEquals("Alice", identity.name)
        assertArrayEquals(alice.identity().signingKey, identity.signingKey)
        assertArrayEquals(alice.identity().encryptionKey, identity.encryptionKey)
        rejects { alice.parseIdentity(qr(identity.copy(name = "Mallory"))) }
        rejects { alice.parseIdentity(qr(identity.copy(encryptionKey = bob.identity().encryptionKey))) }
        rejects { alice.parseIdentity(qr(identity.copy(signature = flip(identity.signature)))) }
    }

    @Test fun qrParserRejectsLinksOtherVersionsPaddingAndOversize() {
        listOf("https://example.com/identity", alice.exportIdentity().replace(":v2:", ":v1:"),
            alice.exportIdentity() + "=", "offlinechat:v2:" + "a".repeat(3000),
            "offlinechat:v2:", alice.exportIdentity() + "\n").forEach { rejects { bob.parseIdentity(it) } }
        val bytes = EnvelopeCodec.encodeIdentity(alice.identity()) + byteArrayOf(1)
        rejects { bob.parseIdentity("offlinechat:v2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)) }
    }

    @Test fun rejectsOtherHpkeParametersEvenWithAuthenticSelfSignature() {
        val alternative = KeysetHandle.generateNew(PredefinedHybridParameters.HPKE_P256_HKDF_SHA256_AES_128_GCM)
        val public = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(alternative.publicKeysetHandle)
        val original = alice.identity()
        val changed = original.copy(id = EnvelopeCodec.fingerprint(original.signingKey, public),
            encryptionKey = public, signature = byteArrayOf())
        val signed = changed.copy(signature = alice.sign(EnvelopeCodec.identityUnsigned(changed)))
        rejects { bob.parseIdentity(qr(signed)) }
        rejects { bob.seal(signed, EnvelopeKind.TEXT, byteArrayOf(1), now, 60, 1) }
    }

    @Test fun renamePreservesFingerprintAndUpdatesAuthenticName() {
        val before = alice.identity()
        alice.rename("Alice's phone")
        val after = bob.parseIdentity(alice.exportIdentity())
        assertEquals(before.id, after.id)
        assertEquals("Alice's phone", after.name)
        assertFalse(before.signature.contentEquals(after.signature))
        assertTrue(bob.verify(before, "hello".toByteArray(), alice.sign("hello".toByteArray())))
        rejects { alice.rename("x".repeat(33)) }
        rejects { alice.rename("bad\nname") }
        rejects { alice.rename("\uD800") }
    }

    @Test fun exposedIdentityArraysCannotMutatePrivateIdentityState() {
        val exported = alice.exportIdentity()
        val identity = alice.identity()
        identity.signingKey.fill(0)
        identity.encryptionKey.fill(0)
        identity.signature.fill(0)
        assertEquals(exported, alice.exportIdentity())
        assertTrue(carol.verifyEnvelope(message()))
    }

    @Test fun signedRouteAllowsAToBToCAndBindsAuthenticatedLastPeer() {
        val first = alice.appendHop(RoutedEnvelope(message()), bob.identity().id)
        assertTrue(bob.verifyRoute(first, bob.identity().id, alice.identity().id))
        val second = bob.appendHop(first, carol.identity().id)
        val decoded = carol.decodePacket(bob.encodePacket(second))
        assertTrue(carol.verifyRoute(decoded, carol.identity().id, bob.identity().id))
        assertFalse(carol.verifyRoute(decoded, carol.identity().id, alice.identity().id))
        assertFalse(bob.verifyRoute(decoded, bob.identity().id, bob.identity().id))
        assertArrayEquals("secret for Carol".toByteArray(), carol.open(decoded.envelope))
    }

    @Test fun incomingRouteCannotBeTruncatedOrReset() {
        val first = alice.appendHop(RoutedEnvelope(message()), bob.identity().id)
        val second = bob.appendHop(first, carol.identity().id)
        assertFalse(carol.verifyRoute(second.copy(route = first.route), carol.identity().id, bob.identity().id))
        assertFalse(bob.verifyRoute(first, bob.identity().id, carol.identity().id))
        assertFalse(carol.verifyRoute(second.copy(route = second.route.drop(1)), carol.identity().id, bob.identity().id))
        assertFalse(carol.verifyRoute(RoutedEnvelope(second.envelope), carol.identity().id, bob.identity().id))
        val reset = second.route.last().copy(index = 1, previous = EnvelopeCodec.routeSeed(bob.envelopeDigest(second.envelope)))
        val signed = reset.copy(signature = bob.sign(EnvelopeCodec.hopUnsigned(bob.envelopeDigest(second.envelope), reset)))
        assertFalse(carol.verifyRoute(second.copy(route = listOf(signed)), carol.identity().id, bob.identity().id))
    }

    @Test fun signedHopChainRejectsIndexDigestTargetAndSignatureTampering() {
        val first = alice.appendHop(RoutedEnvelope(message()), bob.identity().id)
        val second = bob.appendHop(first, carol.identity().id)
        val hop = second.route.last()
        val altered = listOf(hop.copy(index = 1), hop.copy(previous = flip(hop.previous)),
            hop.copy(toId = alice.identity().id), hop.copy(signature = flip(hop.signature)))
        altered.forEach { change ->
            assertFalse(carol.verifyRoute(second.copy(route = listOf(first.route.single(), change)), carol.identity().id, bob.identity().id))
        }
        // Even a legitimate relay signing an incorrect predecessor cannot replace the earlier proof.
        val wrong = hop.copy(previous = ByteArray(32))
        val signed = wrong.copy(signature = bob.sign(EnvelopeCodec.hopUnsigned(bob.envelopeDigest(second.envelope), wrong)))
        assertFalse(carol.verifyRoute(second.copy(route = listOf(first.route.single(), signed)), carol.identity().id, bob.identity().id))
    }

    @Test fun forwardingRejectsLoopsWrongCustodianAndExhaustedBudget() {
        val first = alice.appendHop(RoutedEnvelope(message()), bob.identity().id)
        rejects { bob.appendHop(first, alice.identity().id) }
        rejects { bob.appendHop(first, bob.identity().id) }
        rejects { carol.appendHop(first, alice.identity().id) }
        val oneHop = alice.appendHop(RoutedEnvelope(message(hops = 1)), bob.identity().id)
        rejects { bob.appendHop(oneHop, carol.identity().id) }
        rejects { alice.appendHop(RoutedEnvelope(message()), "not-an-id") }
    }

    @Test fun emptyRouteOnlyValidatesAtLocalOriginBeforeTransfer() {
        val packet = RoutedEnvelope(message())
        assertTrue(alice.verifyRoute(packet, alice.identity().id))
        assertFalse(bob.verifyRoute(packet, alice.identity().id))
        assertFalse(alice.verifyRoute(packet, alice.identity().id, alice.identity().id))
        assertFalse(carol.verifyRoute(packet, carol.identity().id))
    }

    @Test fun localHistoryIsAuthenticatedAndUsesSeparateDeviceKey() {
        val text = "local private history".toByteArray()
        val encrypted = alice.encryptLocal(text)
        assertArrayEquals(text, alice.decryptLocal(encrypted))
        rejects { alice.decryptLocal(flip(encrypted)) }
        rejects { bob.decryptLocal(encrypted) }
        rejects { alice.decryptLocal(byteArrayOf()) }
        alice.rename("Renamed")
        assertArrayEquals(text, alice.decryptLocal(encrypted))
    }

    @Test fun payloadAndLifetimeLimitsAreEnforcedBeforeEncryption() {
        val maximum = ByteArray(1024) { 65 }
        assertArrayEquals(maximum, carol.open(alice.seal(carol.identity(), EnvelopeKind.TEXT, maximum, now, 60, 16)))
        rejects { alice.seal(carol.identity(), EnvelopeKind.TEXT, ByteArray(1025), now, 60, 1) }
        rejects { alice.seal(carol.identity(), EnvelopeKind.TEXT, "🙂".repeat(257).toByteArray(), now, 60, 1) }
        rejects { alice.seal(carol.identity(), EnvelopeKind.TEXT, maximum, now, 59, 1) }
        rejects { alice.seal(carol.identity(), EnvelopeKind.TEXT, maximum, now, 604_801, 1) }
        rejects { alice.seal(carol.identity(), EnvelopeKind.TEXT, maximum, now, 60, 17) }
        rejects { alice.seal(carol.identity(), EnvelopeKind.TEXT, maximum, Long.MAX_VALUE, 60, 1) }
        rejects { alice.seal(alice.identity(), EnvelopeKind.TEXT, maximum, now, 60, 1) }
    }

    @Test fun boundedCodecRejectsTrailingTruncatedOversizeAndUnknownVersion() {
        val packet = alice.appendHop(RoutedEnvelope(message()), bob.identity().id)
        val encoded = alice.encodePacket(packet)
        rejects { bob.decodePacket(encoded + byteArrayOf(0)) }
        rejects { bob.decodePacket(encoded.copyOf(encoded.size - 1)) }
        rejects { bob.decodePacket(ByteArray(EnvelopeCodec.MAX_PACKET_BYTES + 1)) }
        rejects { bob.decodePacket(encoded.copyOf().also { it[4] = 3 }) }
        val decoded = bob.decodePacket(encoded)
        assertArrayEquals(alice.envelopeDigest(packet.envelope), bob.envelopeDigest(decoded.envelope))
        assertTrue(bob.verifyRoute(decoded, bob.identity().id, alice.identity().id))
    }

    @Test fun signaturesBindSessionBytesAndIdentity() {
        val session = "offlinechat.session:test challenges".toByteArray()
        val signature = alice.sign(session)
        assertTrue(bob.verify(alice.identity(), session, signature))
        assertFalse(bob.verify(alice.identity(), flip(session), signature))
        assertFalse(bob.verify(carol.identity(), session, signature))
        assertFalse(bob.verify(alice.identity().copy(name = "Wrong"), session, signature))
    }

    private fun message(hops: Int = 8): SealedEnvelope = alice.seal(carol.identity(), EnvelopeKind.TEXT,
        "secret for Carol".toByteArray(), now, 86_400, hops)

    private fun qr(identity: PublicIdentity): String = "offlinechat:v2:" + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(EnvelopeCodec.encodeIdentity(identity))

    private fun flip(bytes: ByteArray): ByteArray = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

    private fun rejects(action: () -> Unit) { assertThrows(Exception::class.java) { action() } }
}
