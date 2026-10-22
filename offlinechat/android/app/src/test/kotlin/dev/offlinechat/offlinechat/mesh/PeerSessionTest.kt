package dev.offlinechat.offlinechat.mesh

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.ArrayDeque

/** Exercises real signed BLE-link handshakes and controls without radio simulation. */
class PeerSessionTest {
    private val alice = IdentityCrypto.forTesting("Alice")
    private val bob = IdentityCrypto.forTesting("Bob")

    @Test fun mutualFreshChallengeAuthenticatesBothIdentitiesAndExchangesControls() {
        val link = Link(alice, bob)
        link.authenticate()
        assertTrue(link.a.session.authenticated)
        assertTrue(link.b.session.authenticated)
        assertEquals(bob.identity().id, link.a.authenticated.single().id)
        assertEquals(alice.identity().id, link.b.authenticated.single().id)
        assertEquals(64, alice.sign("Ed25519 wire signature".toByteArray()).size)
        link.a.session.sendControl("inventory A".toByteArray())
        link.b.session.accept(link.a.outbound.removeFirst())
        link.b.session.sendControl("inventory B".toByteArray())
        link.a.session.accept(link.b.outbound.removeFirst())
        assertArrayEquals("inventory A".toByteArray(), link.b.controls.single())
        assertArrayEquals("inventory B".toByteArray(), link.a.controls.single())
    }

    @Test fun advertisedIdentityRequiresProofOfItsOwnPrivateKey() {
        val mallory = IdentityCrypto.forTesting("Mallory")
        val link = Link(alice, bob)
        link.begin()
        val fakeHello = replaceIdentity(link.a.outbound.removeFirst(), mallory.exportIdentity())
        link.b.session.accept(fakeHello)
        link.a.session.accept(link.b.outbound.removeFirst())
        // Alice's valid proof cannot authenticate the advertised Mallory identity.
        rejects { link.b.session.accept(link.a.outbound.removeFirst()) }
        assertFalse(link.b.session.authenticated)
        assertTrue(link.b.authenticated.isEmpty())
        assertTrue(link.b.controls.isEmpty())
    }

    @Test fun tamperedProofIsRejectedBeforeAnyAuthenticationCallback() {
        val link = Link(alice, bob)
        link.exchangeHellos()
        val proof = link.a.outbound.removeFirst()
        rejects { link.b.session.accept(flip(proof)) }
        assertFalse(link.b.session.authenticated)
        assertTrue(link.b.authenticated.isEmpty())
        link.b.session.accept(proof)
        assertTrue(link.b.session.authenticated)
        rejects { link.b.session.accept(proof) }
        assertEquals(1, link.b.authenticated.size)
    }

    @Test fun proofBindsBothFreshNonces() {
        val link = Link(alice, bob)
        link.begin()
        val helloA = link.a.outbound.removeFirst()
        val helloB = link.b.outbound.removeFirst()
        link.b.session.accept(flip(helloA)) // Change A's nonce but preserve the valid identity document.
        link.a.session.accept(helloB)
        rejects { link.b.session.accept(link.a.outbound.removeFirst()) }
        rejects { link.a.session.accept(link.b.outbound.removeFirst()) }
        assertFalse(link.a.session.authenticated)
        assertFalse(link.b.session.authenticated)
    }

    @Test fun controlsCannotPrecedeProofAndIdentityCannotBeReset() {
        val link = Link(alice, bob)
        link.begin()
        val helloA = link.a.outbound.removeFirst()
        val helloB = link.b.outbound.removeFirst()
        link.b.session.accept(helloA)
        rejects { link.b.session.accept(helloA) }
        rejects { link.b.session.sendControl("premature".toByteArray()) }
        link.a.session.accept(helloB)
        link.a.session.accept(link.b.outbound.removeFirst())
        link.a.session.sendControl("before Bob accepts Alice's proof".toByteArray())
        val aliceProof = link.a.outbound.removeFirst()
        val control = link.a.outbound.removeFirst()
        rejects { link.b.session.accept(control) }
        link.b.session.accept(aliceProof)
        link.b.session.accept(control)
        assertEquals(1, link.b.controls.size)
        rejects { link.b.session.begin() }
    }

    @Test fun signedControlRejectsTamperingReplayAndReordering() {
        val link = Link(alice, bob)
        link.authenticate()
        link.a.session.sendControl("one".toByteArray())
        link.a.session.sendControl("two".toByteArray())
        val one = link.a.outbound.removeFirst()
        val two = link.a.outbound.removeFirst()
        rejects { link.b.session.accept(two) }
        rejects { link.b.session.accept(flip(one)) }
        val changedPayload = one.copyOf().also { it[18] = (it[18].toInt() xor 1).toByte() }
        rejects { link.b.session.accept(changedPayload) }
        assertTrue(link.b.controls.isEmpty())
        link.b.session.accept(one)
        rejects { link.b.session.accept(one) }
        link.b.session.accept(two)
        assertEquals(listOf("one", "two"), link.b.controls.map { String(it) })
    }

    @Test fun capturedProofCannotAuthenticateFreshSessionWithSameIdentities() {
        val old = Link(alice, bob)
        old.exchangeHellos()
        val capturedProof = old.a.outbound.removeFirst()
        old.b.session.accept(capturedProof)
        val fresh = Link(alice, bob)
        fresh.exchangeHellos()
        rejects { fresh.b.session.accept(capturedProof) }
        assertFalse(fresh.b.session.authenticated)
        fresh.b.session.accept(fresh.a.outbound.removeFirst())
        assertTrue(fresh.b.session.authenticated)
    }

    @Test fun capturedControlCannotReplayIntoFreshAuthenticatedSession() {
        val old = Link(alice, bob)
        old.authenticate()
        old.a.session.sendControl("captured inventory".toByteArray())
        val captured = old.a.outbound.removeFirst()
        old.b.session.accept(captured)
        val fresh = Link(alice, bob)
        fresh.authenticate()
        rejects { fresh.b.session.accept(captured) }
        assertTrue(fresh.b.controls.isEmpty())
        fresh.a.session.sendControl("fresh inventory".toByteArray())
        fresh.b.session.accept(fresh.a.outbound.removeFirst())
        assertArrayEquals("fresh inventory".toByteArray(), fresh.b.controls.single())
    }

    @Test fun reflectionAndSelfIdentityAreRejected() {
        val link = Link(alice, bob)
        link.authenticate()
        link.a.session.sendControl("reflection".toByteArray())
        rejects { link.a.session.accept(link.a.outbound.removeFirst()) }
        assertTrue(link.a.controls.isEmpty())
        val self = Link(alice, alice)
        self.begin()
        rejects { self.b.session.accept(self.a.outbound.removeFirst()) }
        assertFalse(self.b.session.authenticated)
    }

    @Test fun frameBoundsVersionsAndTrailingBytesFailClosed() {
        val link = Link(alice, bob)
        rejects { link.b.session.accept(ByteArray(6)) }
        link.begin()
        val hello = link.a.outbound.removeFirst()
        rejects { link.b.session.accept(ByteArray(PeerSession.MAX_WIRE_BYTES + 1)) }
        rejects { link.b.session.accept(hello.copyOf().also { it[4] = 3 }) }
        rejects { link.b.session.accept(hello + byteArrayOf(0)) }
        rejects { link.b.session.accept(hello.copyOf(9)) }
        link.b.session.accept(hello)
        link.a.session.accept(link.b.outbound.removeFirst())
        val proof = link.a.outbound.removeFirst()
        rejects { link.b.session.accept(proof + byteArrayOf(0)) }
        assertFalse(link.b.session.authenticated)
        link.b.session.accept(proof)
        rejects { link.b.session.sendControl(byteArrayOf()) }
        rejects { link.b.session.sendControl(ByteArray(PeerSession.MAX_CONTROL_BYTES + 1)) }
    }

    private class Endpoint(crypto: MeshCrypto) {
        val outbound = ArrayDeque<ByteArray>()
        val authenticated = mutableListOf<PublicIdentity>()
        val controls = mutableListOf<ByteArray>()
        val session = PeerSession(crypto, { outbound.addLast(it) }, { authenticated.add(it) }, { controls.add(it) })
    }

    private class Link(aCrypto: MeshCrypto, bCrypto: MeshCrypto) {
        val a = Endpoint(aCrypto)
        val b = Endpoint(bCrypto)
        fun begin() { a.session.begin(); b.session.begin() }
        fun exchangeHellos() {
            begin()
            val aHello = a.outbound.removeFirst()
            val bHello = b.outbound.removeFirst()
            a.session.accept(bHello)
            b.session.accept(aHello)
        }
        fun authenticate() {
            exchangeHellos()
            a.session.accept(b.outbound.removeFirst())
            b.session.accept(a.outbound.removeFirst())
        }
    }

    private fun replaceIdentity(hello: ByteArray, qr: String): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(hello))
        val magic = input.readInt()
        val version = input.readUnsignedByte()
        val type = input.readUnsignedByte()
        val oldQr = ByteArray(input.readInt()).also(input::readFully)
        assertTrue(oldQr.isNotEmpty())
        val nonce = ByteArray(32).also(input::readFully)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                val encoded = qr.toByteArray(Charsets.UTF_8)
                it.writeInt(magic); it.writeByte(version); it.writeByte(type)
                it.writeInt(encoded.size); it.write(encoded); it.write(nonce)
            }
        }.toByteArray()
    }

    private fun flip(bytes: ByteArray) = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    private fun rejects(action: () -> Unit) { assertThrows(Exception::class.java) { action() } }
}
