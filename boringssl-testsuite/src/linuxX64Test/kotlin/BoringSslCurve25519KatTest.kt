@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.boringssl.smoke.test

import com.ditchoom.boringssl.smoke.cryptoonly.bssl_kat_ed25519
import com.ditchoom.boringssl.smoke.cryptoonly.bssl_kat_x25519
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Curve25519 surface-contract KATs — part of the Profile 1 crypto-only gate. Ed25519 signing is
 * deterministic, so RFC 8032 TEST 1 asserts the exact public key AND signature bytes (plus a
 * tamper-reject); X25519 asserts the exact RFC 7748 §5.2 shared-secret bytes plus a keypair-based
 * two-party agreement roundtrip. Probes live in boringsslsmoke_cryptoonly.def, link libcrypto.a
 * ALONE, and return 0 on success or a distinct code naming the failing step.
 */
class BoringSslCurve25519KatTest {
    @Test
    fun ed25519_sign_verify_matches_rfc8032_test1() {
        val code = bssl_kat_ed25519()
        assertEquals(0, code, "Ed25519 (RFC 8032 TEST 1) KAT failed at probe step $code")
    }

    @Test
    fun x25519_shared_secret_matches_rfc7748_vector() {
        val code = bssl_kat_x25519()
        assertEquals(0, code, "X25519 (RFC 7748 §5.2) KAT failed at probe step $code")
    }
}
