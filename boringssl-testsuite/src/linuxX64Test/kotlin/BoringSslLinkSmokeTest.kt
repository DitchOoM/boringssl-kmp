@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.boringssl.smoke.test

import com.ditchoom.boringssl.smoke.bssl_smoke_sha256
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Link-smoke for the canonical BoringSSL bundle (RFC §8a). Statically links :boringssl-build's
 * container-built (glibc-2.17) libcrypto.a/libssl.a into this K/N linuxX64 test binary — against K/N's
 * bundled glibc-2.19 sysroot — and calls BoringSSL's SHA256. If the archive referenced an above-floor
 * libc symbol, the link would fail and this test would never build. So a green run proves the
 * floor-safe archive is genuinely K/N-linkable and correct, not merely symbol-present.
 */
class BoringSslLinkSmokeTest {
    @Test
    fun sha256_of_abc_matches_known_vector() {
        val input = "abc".encodeToByteArray()
        val digest = UByteArray(32)
        input.usePinned { pinnedIn ->
            digest.usePinned { pinnedOut ->
                bssl_smoke_sha256(
                    pinnedIn.addressOf(0).reinterpret(),
                    input.size.convert(),
                    pinnedOut.addressOf(0),
                )
            }
        }
        val hex = digest.joinToString("") { it.toString(16).padStart(2, '0') }
        // RFC 6234 / FIPS 180-4 test vector: SHA-256("abc").
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }
}
