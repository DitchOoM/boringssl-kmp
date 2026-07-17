@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.boringssl.smoke.test

import com.ditchoom.boringssl.smoke.cryptoonly.bssl_kat_ecdh_p256
import com.ditchoom.boringssl.smoke.cryptoonly.bssl_kat_ecdsa_p256
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P-256 surface-contract KATs — part of the Profile 1 crypto-only gate. ECDSA verifies a fixed
 * known-good signature (RFC 6979 §A.2.5 "sample" r||s — BoringSSL signing is RANDOMIZED, so the
 * sign path is only round-tripped, never byte-asserted) and rejects a tampered digest. ECDH goes
 * through EC_POINT_mul — buffer's exact shared-secret shape — with a fixed-vector d×G leg checked
 * against the RFC 6979 public key plus a two-party agreement roundtrip. Probes live in
 * boringsslsmoke_cryptoonly.def, link libcrypto.a ALONE, and return 0 on success or a distinct
 * code naming the failing step.
 */
class BoringSslEcP256KatTest {
    @Test
    fun ecdsa_p256_verifies_known_good_signature_and_roundtrips() {
        val code = bssl_kat_ecdsa_p256()
        assertEquals(0, code, "ECDSA P-256 (RFC 6979 §A.2.5 verify + sign roundtrip) KAT failed at probe step $code")
    }

    @Test
    fun ecdh_p256_via_ec_point_mul_matches_vector_and_agrees() {
        val code = bssl_kat_ecdh_p256()
        assertEquals(0, code, "ECDH P-256 (EC_POINT_mul d×G vector + agreement) KAT failed at probe step $code")
    }
}
