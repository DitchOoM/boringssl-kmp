package com.ditchoom.boringssl

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Known-answer tests for the stateless primitives exposed by the shim, computed through the FFM
 * downcalls into BoringSSL. Vectors are the published standards ones (FIPS 180, RFC 4231/5869/7748),
 * so a green run proves both the binding marshalling AND that we linked the real BoringSSL.
 */
class BoringSslCryptoTest {
    @Test
    fun sha512AbcMatchesFipsVector() {
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            BoringSsl.sha512("abc".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun hmacSha256MatchesRfc4231Case2() {
        // key = "Jefe", data = "what do ya want for nothing?"
        val mac = BoringSsl.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", mac.hex())
    }

    @Test
    fun hkdfSha256MatchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = "000102030405060708090a0b0c".unhex()
        val info = "f0f1f2f3f4f5f6f7f8f9".unhex()
        val okm = BoringSsl.hkdfSha256(length = 42, secret = ikm, salt = salt, info = info)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.hex(),
        )
    }

    @Test
    fun x25519MatchesRfc7748Vector() {
        val alicePrivate = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".unhex()
        val bobPublic = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".unhex()
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            BoringSsl.x25519(alicePrivate, bobPublic).hex(),
        )
    }

    @Test
    fun x25519KeyPairAgreementIsSymmetric() {
        val a = BoringSsl.x25519KeyPair()
        val b = BoringSsl.x25519KeyPair()
        // Both sides derive the same shared secret from the other's public key.
        assertContentEquals(
            BoringSsl.x25519(a.privateKey, b.publicKey),
            BoringSsl.x25519(b.privateKey, a.publicKey),
        )
        assertEquals(32, a.publicKey.size)
        assertEquals(32, a.privateKey.size)
    }

    @Test
    fun randomBytesAreSizedAndNotConstant() {
        assertEquals(0, BoringSsl.randomBytes(0).size)
        val a = BoringSsl.randomBytes(32)
        val b = BoringSsl.randomBytes(32)
        assertEquals(32, a.size)
        assertFalse(a.all { it == 0.toByte() }, "32 random bytes should not be all zero")
        assertFalse(a.contentEquals(b), "two RNG draws should differ")
    }

    @Test
    fun sha256AndSha512HaveExpectedLengths() {
        assertTrue(BoringSsl.sha256(ByteArray(0)).size == 32)
        assertTrue(BoringSsl.sha512(ByteArray(0)).size == 64)
    }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
