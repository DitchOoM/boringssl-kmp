@file:OptIn(ExperimentalForeignApi::class)

package com.ditchoom.boringssl.smoke.test

import com.ditchoom.boringssl.canonical.ext.crypto.canon_ext_crypto_probe
import com.ditchoom.boringssl.canonical.ext.ssl.canon_ext_ssl_ctx_new_ok
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One-copy co-link tripwire for the :boringssl-canonical OWNER klib (RFC §12 D3).
 *
 * The linuxX64Test binary links the :boringssl-canonical dependency (the OWNER — it EMBEDS the ONE
 * canonical libssl.a + libcrypto.a into its `-cinterop` klib's `included/`) alongside TWO EXTERNAL,
 * headers-only cinterops that embed NO archive of their own:
 *   - `canon_ext_crypto_probe`  — buffer's crypto closure (SHA256 + X25519 + HKDF), and
 *   - `canon_ext_ssl_ctx_new_ok` — a libssl entry point (SSL_CTX_new(TLS_method())).
 *
 * Both external surfaces resolve their symbols at final link against the SINGLE archive the owner
 * supplies. A green run proves the design end-to-end: a full-bundle owner serves BOTH a crypto-closure
 * consumer AND a libssl consumer from one embedded copy, and the whole thing co-links + runs. (The
 * `nm` single-unprefixed-copy assertion over this same binary is the CI script's job.)
 */
class BoringSslCanonicalOwnerOneCopyTest {
    @Test
    fun canonical_owner_supplies_one_archive_for_external_crypto_and_ssl() {
        val input = "abc".encodeToByteArray()
        val digest = UByteArray(32)
        val status =
            input.usePinned { pinnedIn ->
                digest.usePinned { pinnedOut ->
                    canon_ext_crypto_probe(
                        pinnedIn.addressOf(0).reinterpret(),
                        input.size.convert(),
                        pinnedOut.addressOf(0),
                    )
                }
            }
        // X25519 + HKDF both resolved against the owner's embedded archive and succeeded.
        assertEquals(1, status)
        val hex = digest.joinToString("") { it.toString(16).padStart(2, '0') }
        // RFC 6234 / FIPS 180-4 test vector: SHA-256("abc").
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)

        // The libssl surface resolves against the SAME single embedded copy.
        assertEquals(1, canon_ext_ssl_ctx_new_ok())
    }
}
