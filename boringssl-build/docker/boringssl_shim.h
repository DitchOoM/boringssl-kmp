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
 * GROWING SURFACE. The current slice covers the stateless buffer-crypto / DTLS-key-schedule core
 * (digests, HMAC, HKDF, X25519, RNG) — each a self-contained, known-answer-testable primitive.
 * The stateful DTLS/QUIC needs (SSL_CTX_*, SSL_export_keying_material, EVP_AEAD_*, …) are added here
 * as real wrappers as those lanes light up — never bound directly against BoringSSL's macro surface.
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

/* ── digests ─────────────────────────────────────────────────────────────────────────────────── */
/* SHA-256 / SHA-512 of `len` bytes at `data` into the 32/64-byte `out`. Wrap BoringSSL SHA256/SHA512. */
void boringssl_ffi_sha256(const uint8_t *data, size_t len, uint8_t out[32]);
void boringssl_ffi_sha512(const uint8_t *data, size_t len, uint8_t out[64]);

/* ── HMAC ────────────────────────────────────────────────────────────────────────────────────── */
/* HMAC-SHA256 of `data` under `key` into the 32-byte `out`. Wraps HMAC(EVP_sha256(), …). */
void boringssl_ffi_hmac_sha256(const uint8_t *key, size_t key_len,
                               const uint8_t *data, size_t data_len, uint8_t out[32]);

/* ── HKDF ────────────────────────────────────────────────────────────────────────────────────── */
/* HKDF-SHA256 (RFC 5869 extract-then-expand) → `out_len` bytes in `out`. Returns 1 on success, 0 on
 * failure (e.g. out_len exceeds 255*HashLen). Wraps HKDF(…, EVP_sha256(), …). */
int boringssl_ffi_hkdf_sha256(uint8_t *out, size_t out_len,
                              const uint8_t *secret, size_t secret_len,
                              const uint8_t *salt, size_t salt_len,
                              const uint8_t *info, size_t info_len);

/* ── X25519 (RFC 7748) ───────────────────────────────────────────────────────────────────────── */
/* Generate a fresh key pair (uses BoringSSL's RNG). */
void boringssl_ffi_x25519_keypair(uint8_t out_public[32], uint8_t out_private[32]);
/* Diffie-Hellman: shared = private · peer_public. Returns 1 on success, 0 for an all-zero (small-order)
 * shared secret. Wraps X25519(). */
int boringssl_ffi_x25519(uint8_t out_shared[32], const uint8_t private_key[32],
                         const uint8_t peer_public[32]);

/* ── RNG ─────────────────────────────────────────────────────────────────────────────────────── */
/* Fill `out` with `len` cryptographically secure bytes. Returns 1 (BoringSSL RAND_bytes never fails). */
int boringssl_ffi_rand_bytes(uint8_t *out, size_t len);

#ifdef __cplusplus
}
#endif

#endif /* BORINGSSL_SHIM_H */
