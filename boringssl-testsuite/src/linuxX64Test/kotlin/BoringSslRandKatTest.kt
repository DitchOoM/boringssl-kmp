@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.boringssl.smoke.test

import com.ditchoom.boringssl.smoke.cryptoonly.bssl_kat_rand_bytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * RAND surface-contract probe — part of the Profile 1 crypto-only gate. RAND_bytes must report
 * success and actually fill the buffer (an all-zero 32-byte draw would signal a broken entropy
 * path; probability 2^-256, never in practice — the one sanctioned RNG-dependent assertion besides
 * the sign/agree roundtrips). The probe lives in boringsslsmoke_cryptoonly.def, links libcrypto.a
 * ALONE, and returns 0 on success or a distinct code naming the failing step.
 */
class BoringSslRandKatTest {
    @Test
    fun rand_bytes_succeeds_and_fills_buffer() {
        val code = bssl_kat_rand_bytes()
        assertEquals(0, code, "RAND_bytes probe failed at step $code")
    }
}
