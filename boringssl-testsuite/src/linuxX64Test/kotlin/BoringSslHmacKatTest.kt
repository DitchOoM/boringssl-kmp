@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.boringssl.smoke.test

import com.ditchoom.boringssl.smoke.cryptoonly.bssl_kat_hmac_sha256
import com.ditchoom.boringssl.smoke.cryptoonly.bssl_kat_hmac_sha512
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HMAC surface-contract KATs (RFC 4231 test case 1) — part of the Profile 1 crypto-only gate.
 * The probes live in boringsslsmoke_cryptoonly.def with byte-literal vectors, link libcrypto.a
 * ALONE (no libssl.a), and return 0 on success or a distinct code naming the failing step.
 */
class BoringSslHmacKatTest {
    @Test
    fun hmac_sha256_matches_rfc4231_case1() {
        val code = bssl_kat_hmac_sha256()
        assertEquals(0, code, "HMAC-SHA256 (RFC 4231 case 1) KAT failed at probe step $code")
    }

    @Test
    fun hmac_sha512_matches_rfc4231_case1() {
        val code = bssl_kat_hmac_sha512()
        assertEquals(0, code, "HMAC-SHA512 (RFC 4231 case 1) KAT failed at probe step $code")
    }
}
