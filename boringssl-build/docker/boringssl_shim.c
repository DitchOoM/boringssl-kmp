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
#include <openssl/sha.h>

#define BSSL_FFI_EXPORT __attribute__((visibility("default")))

BSSL_FFI_EXPORT unsigned long boringssl_ffi_version_number(void) {
    return (unsigned long) OPENSSL_VERSION_NUMBER;
}

BSSL_FFI_EXPORT void boringssl_ffi_sha256(const uint8_t *data, size_t len, uint8_t out[32]) {
    SHA256(data, len, out);
}
