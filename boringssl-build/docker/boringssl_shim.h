/* ─────────────────────────────────────────────────────────────────────────────────────────────────
 * boringssl_shim.h — the ONE curated FFI export surface for boringssl-kmp (RFC §4).
 *
 * BoringSSL exposes much of its API as function-like macros and `static inline` wrappers. Neither
 * jextract (JVM/FFM) nor a bare cinterop `.def` can bind those — they are invisible to a symbol
 * table. This header re-declares each needed entry point as a REAL exported function, and
 * boringssl_shim.c gives it a body that calls the underlying (possibly macro/inline) BoringSSL API.
 *
 * This is the single source of truth for the exported surface. `libboringsslffi.{so,dylib}` exports
 * EXACTLY these `boringssl_ffi_*` symbols (everything from BoringSSL stays hidden — the D3
 * one-unprefixed-copy invariant), and jextract/FFM binds over this header.
 *
 * SEED SURFACE ONLY. This first slice proves the pipeline (version probe + a hash). Consumers'
 * DTLS/QUIC needs (SSL_CTX_*, SSL_export_keying_material, EVP_AEAD_*, …) are added here as real
 * wrappers as the lanes light up — never bound directly against BoringSSL's macro surface.
 * ───────────────────────────────────────────────────────────────────────────────────────────────── */
#ifndef BORINGSSL_SHIM_H
#define BORINGSSL_SHIM_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* OPENSSL_VERSION_NUMBER is a macro in BoringSSL — re-exported here as a real function so FFM/cinterop
 * can read it. Doubles as a "the shared library loaded and links against BoringSSL" liveness probe. */
unsigned long boringssl_ffi_version_number(void);

/* SHA-256 of `len` bytes at `data` into the 32-byte buffer `out`. Wraps BoringSSL's SHA256(); a
 * self-contained, allocation-free downcall that validates the full FFM round-trip end to end. */
void boringssl_ffi_sha256(const uint8_t *data, size_t len, uint8_t out[32]);

#ifdef __cplusplus
}
#endif

#endif /* BORINGSSL_SHIM_H */
