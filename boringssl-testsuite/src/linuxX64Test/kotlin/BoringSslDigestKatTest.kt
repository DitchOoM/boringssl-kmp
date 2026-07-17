@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.boringssl.smoke.test

import com.ditchoom.boringssl.smoke.cryptoonly.bssl_kat_sha384_sha512
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SHA-2 surface-contract KATs — part of the Profile 1 crypto-only gate. SHA-384 and SHA-512 over
 * "abc" against the RFC 6234 / FIPS 180-4 vectors (SHA-256's "abc" vector is already asserted by
 * both link-smokes). The probe lives in boringsslsmoke_cryptoonly.def, links libcrypto.a ALONE,
 * and returns 0 on success or a distinct code naming the failing digest.
 */
class BoringSslDigestKatTest {
    @Test
    fun sha384_and_sha512_of_abc_match_known_vectors() {
        val code = bssl_kat_sha384_sha512()
        assertEquals(0, code, "SHA-384/SHA-512 (\"abc\") KAT failed at probe step $code")
    }
}
