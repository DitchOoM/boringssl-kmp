@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.boringssl.smoke.test

import com.ditchoom.boringssl.smoke.cryptoonly.bssl_smoke_crypto_probe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Crypto-only link-smoke — the standing CI gate for the provision plugin's `cryptoOnly` cinterop
 * (Profile 1). Statically links :boringssl-build's container-built libcrypto.a ALONE (NO libssl.a)
 * into this K/N linuxX64 test binary and exercises a representative slice of buffer's frozen crypto
 * closure — SHA256 + X25519 + HKDF — through one wrapper. If that closure ever came to depend on a
 * libssl symbol, the crypto-only link would fail and this test would never build; a green run proves
 * buffer's TLS-free subset is genuinely satisfiable from libcrypto.a on its own (and, like the
 * full-bundle smoke, against K/N's glibc-2.19 floor).
 */
class BoringSslCryptoOnlyLinkSmokeTest {
    @Test
    fun crypto_only_closure_links_and_sha256_matches_known_vector() {
        val input = "abc".encodeToByteArray()
        val digest = UByteArray(32)
        val status =
            input.usePinned { pinnedIn ->
                digest.usePinned { pinnedOut ->
                    bssl_smoke_crypto_probe(
                        pinnedIn.addressOf(0).reinterpret(),
                        input.size.convert(),
                        pinnedOut.addressOf(0),
                    )
                }
            }
        // X25519 + HKDF both linked crypto-only and succeeded.
        assertEquals(1, status)
        val hex = digest.joinToString("") { it.toString(16).padStart(2, '0') }
        // RFC 6234 / FIPS 180-4 test vector: SHA-256("abc").
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }
}
