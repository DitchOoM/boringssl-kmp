/* ─────────────────────────────────────────────────────────────────────────────────────────────────
 * boringssl_shim.c — bodies for the curated FFI surface declared in boringssl_shim.h (RFC §4).
 *
 * Every symbol is marked default-visibility; the translation unit is compiled `-fvisibility=hidden`
 * and the shared lib is linked `-Wl,--exclude-libs,ALL` + a `{ global: boringssl_ffi_*; local: *; }`
 * version script, so these are the ONLY symbols that leave `libboringsslffi.{so,dylib}`. BoringSSL's
 * own (unprefixed) symbols stay private — the D3 collision-safety invariant, verified by the
 * validate-artifacts single-copy `nm` guard.
 * ───────────────────────────────────────────────────────────────────────────────────────────────── */
#include "boringssl_shim.h"

#include <openssl/crypto.h>
#include <openssl/curve25519.h>
#include <openssl/digest.h>
#include <openssl/hkdf.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>
#include <openssl/sha.h>

#define BSSL_FFI_EXPORT __attribute__((visibility("default")))

BSSL_FFI_EXPORT unsigned long boringssl_ffi_version_number(void) {
    return (unsigned long) OPENSSL_VERSION_NUMBER;
}

BSSL_FFI_EXPORT void boringssl_ffi_sha256(const uint8_t *data, size_t len, uint8_t out[32]) {
    SHA256(data, len, out);
}

BSSL_FFI_EXPORT void boringssl_ffi_sha512(const uint8_t *data, size_t len, uint8_t out[64]) {
    SHA512(data, len, out);
}

BSSL_FFI_EXPORT void boringssl_ffi_hmac_sha256(const uint8_t *key, size_t key_len,
                                               const uint8_t *data, size_t data_len, uint8_t out[32]) {
    unsigned int out_len = 0;
    HMAC(EVP_sha256(), key, key_len, data, data_len, out, &out_len);
}

BSSL_FFI_EXPORT int boringssl_ffi_hkdf_sha256(uint8_t *out, size_t out_len,
                                              const uint8_t *secret, size_t secret_len,
                                              const uint8_t *salt, size_t salt_len,
                                              const uint8_t *info, size_t info_len) {
    return HKDF(out, out_len, EVP_sha256(), secret, secret_len, salt, salt_len, info, info_len);
}

BSSL_FFI_EXPORT void boringssl_ffi_x25519_keypair(uint8_t out_public[32], uint8_t out_private[32]) {
    X25519_keypair(out_public, out_private);
}

BSSL_FFI_EXPORT int boringssl_ffi_x25519(uint8_t out_shared[32], const uint8_t private_key[32],
                                         const uint8_t peer_public[32]) {
    return X25519(out_shared, private_key, peer_public);
}

BSSL_FFI_EXPORT int boringssl_ffi_rand_bytes(uint8_t *out, size_t len) {
    return RAND_bytes(out, len);
}
