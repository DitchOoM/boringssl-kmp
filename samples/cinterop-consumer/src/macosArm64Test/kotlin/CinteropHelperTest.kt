@file:OptIn(ExperimentalForeignApi::class)

package consumer.test

import com.ditchoom.boringssl.boringssl_ffi_sha256
import com.ditchoom.boringssl.boringssl_ffi_version_number
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves `boringssl.cinterop(target)` delivers a REAL, callable K/N binding from a genuine external
 * consumer that resolves the PUBLISHED provision plugin — with no hand-written `.def`. SHA256("abc")
 * runs through the provisioned macosArm64 libcrypto.a. Identical to the linuxX64 variant.
 */
class CinteropHelperTest {
    @Test
    fun canonical_cinterop_sha256_abc() {
        val input = "abc".encodeToByteArray()
        val digest = UByteArray(32)
        input.usePinned { pin ->
            digest.usePinned { pout ->
                boringssl_ffi_sha256(pin.addressOf(0).reinterpret(), input.size.convert(), pout.addressOf(0))
            }
        }
        val hex = digest.joinToString("") { it.toString(16).padStart(2, '0') }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }

    @Test
    fun version_number_is_live() {
        assertTrue(boringssl_ffi_version_number() != 0uL, "OPENSSL_VERSION_NUMBER should be non-zero")
    }
}
