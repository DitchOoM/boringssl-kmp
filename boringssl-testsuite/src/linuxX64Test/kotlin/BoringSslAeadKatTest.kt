@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.boringssl.smoke.test

import com.ditchoom.boringssl.smoke.cryptoonly.bssl_kat_aes_128_gcm
import com.ditchoom.boringssl.smoke.cryptoonly.bssl_kat_aes_256_gcm
import com.ditchoom.boringssl.smoke.cryptoonly.bssl_kat_chacha20_poly1305
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * EVP_AEAD surface-contract KATs — part of the Profile 1 crypto-only gate. Each probe seals with a
 * fixed key/nonce and asserts the EXACT ciphertext||tag from the reference vector (NIST GCM spec
 * test cases 2/14; RFC 8439 §2.8.2), round-trips open, and rejects a tampered byte. Probes live in
 * boringsslsmoke_cryptoonly.def, link libcrypto.a ALONE, and return 0 on success or a distinct
 * code naming the failing step (1 ctx, 2 seal, 3 length, 4 ct||tag mismatch, 5 open, 6 roundtrip
 * mismatch, 7 tampered input accepted).
 */
class BoringSslAeadKatTest {
    @Test
    fun aes_128_gcm_seal_open_matches_nist_vector() {
        val code = bssl_kat_aes_128_gcm()
        assertEquals(0, code, "AES-128-GCM (NIST GCM test case 2) KAT failed at probe step $code")
    }

    @Test
    fun aes_256_gcm_seal_open_matches_nist_vector() {
        val code = bssl_kat_aes_256_gcm()
        assertEquals(0, code, "AES-256-GCM (NIST GCM test case 14) KAT failed at probe step $code")
    }

    @Test
    fun chacha20_poly1305_seal_open_matches_rfc8439_vector() {
        val code = bssl_kat_chacha20_poly1305()
        assertEquals(0, code, "ChaCha20-Poly1305 (RFC 8439 §2.8.2) KAT failed at probe step $code")
    }
}
