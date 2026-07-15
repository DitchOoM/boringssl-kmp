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
 * Apple K/N link-smoke for the canonical BoringSSL bundle (RFC §8 / D2). Statically links
 * :boringssl-build's per-SDK libcrypto.a/libssl.a (built with CMAKE_OSX_DEPLOYMENT_TARGET as the floor)
 * into this K/N macosArm64 test binary and calls BoringSSL's SHA256. Unlike Linux there is no glibc
 * floor; the deployment target is the portability floor, and a successful K/N link + run proves the
 * archive is genuinely cinterop-linkable on Apple, not merely symbol-present. This is the RUNTIME
 * (not compile-faithful) proof for macOS — it executes on the macOS runner.
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
