/* ─────────────────────────────────────────────────────────────────────────────────────────────────
 * android_kat.c — on-device known-answer tests for the Android BoringSSL archives (RFC §8).
 *
 * Built by :boringssl-build:buildAndroidKat<Abi> (NDK, statically linked against the per-ABI
 * libssl.a/libcrypto.a) and RUN on an Android emulator in CI (build-android.yaml). This is the Android
 * analog of :boringssl-jvm:jvm21Test — it EXECUTES the crypto on the target architecture and checks the
 * published standard vectors, so a green run proves the archives compute correctly on Android, not just
 * that they link. Same vectors as the JVM KATs (FIPS 180, RFC 4231/5869/7748).
 *
 * Exit 0 iff every case passes; nonzero + a diagnostic on the first failure.
 * ─────────────────────────────────────────────────────────────────────────────────────────────────*/
#include <openssl/sha.h>
#include <openssl/hmac.h>
#include <openssl/evp.h>
#include <openssl/hkdf.h>
#include <openssl/curve25519.h>
#include <openssl/rand.h>
#include <stdio.h>
#include <string.h>

static int fails = 0;

static void tohex(const unsigned char *b, size_t n, char *out) {
    static const char *h = "0123456789abcdef";
    for (size_t i = 0; i < n; i++) { out[2 * i] = h[b[i] >> 4]; out[2 * i + 1] = h[b[i] & 0xf]; }
    out[2 * n] = 0;
}

static void check(const char *name, const unsigned char *got, size_t n, const char *want) {
    char hex[256];
    tohex(got, n, hex);
    if (strcmp(hex, want) == 0) {
        printf("  ok   %s\n", name);
    } else {
        printf("  FAIL %s\n    got  %s\n    want %s\n", name, hex, want);
        fails++;
    }
}

int main(void) {
    printf("== android_kat ==\n");

    /* SHA-256("abc") — FIPS 180 */
    unsigned char h256[32];
    SHA256((const unsigned char *)"abc", 3, h256);
    check("sha256(abc)", h256, 32, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

    /* SHA-512("abc") — FIPS 180 */
    unsigned char h512[64];
    SHA512((const unsigned char *)"abc", 3, h512);
    check("sha512(abc)", h512, 64,
          "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
          "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f");

    /* HMAC-SHA256(key="Jefe", data="what do ya want for nothing?") — RFC 4231 case 2 */
    unsigned char mac[32];
    unsigned int maclen = 0;
    HMAC(EVP_sha256(), "Jefe", 4, (const unsigned char *)"what do ya want for nothing?", 28, mac, &maclen);
    check("hmac-sha256(rfc4231-2)", mac, 32, "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");

    /* HKDF-SHA256 — RFC 5869 case 1 */
    unsigned char ikm[22]; memset(ikm, 0x0b, sizeof ikm);
    unsigned char salt[13] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    unsigned char info[10] = {0xf0, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9};
    unsigned char okm[42];
    if (HKDF(okm, sizeof okm, EVP_sha256(), ikm, sizeof ikm, salt, sizeof salt, info, sizeof info) != 1) {
        printf("  FAIL hkdf-sha256: HKDF returned error\n"); fails++;
    } else {
        check("hkdf-sha256(rfc5869-1)", okm, 42,
              "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
    }

    /* X25519 — RFC 7748 */
    unsigned char alice_priv[32] = {
        0x77, 0x07, 0x6d, 0x0a, 0x73, 0x18, 0xa5, 0x7d, 0x3c, 0x16, 0xc1, 0x72, 0x51, 0xb2, 0x66, 0x45,
        0xdf, 0x4c, 0x2f, 0x87, 0xeb, 0xc0, 0x99, 0x2a, 0xb1, 0x77, 0xfb, 0xa5, 0x1d, 0xb9, 0x2c, 0x2a};
    unsigned char bob_pub[32] = {
        0xde, 0x9e, 0xdb, 0x7d, 0x7b, 0x7d, 0xc1, 0xb4, 0xd3, 0x5b, 0x61, 0xc2, 0xec, 0xe4, 0x35, 0x37,
        0x3f, 0x83, 0x43, 0xc8, 0x5b, 0x78, 0x67, 0x4d, 0xad, 0xfc, 0x7e, 0x14, 0x6f, 0x88, 0x2b, 0x4f};
    unsigned char shared[32];
    if (X25519(shared, alice_priv, bob_pub) != 1) {
        printf("  FAIL x25519: X25519 returned error\n"); fails++;
    } else {
        check("x25519(rfc7748)", shared, 32, "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742");
    }

    /* RAND_bytes: two 32-byte draws are sized, non-zero, and differ. */
    unsigned char r1[32], r2[32];
    if (RAND_bytes(r1, 32) != 1 || RAND_bytes(r2, 32) != 1) {
        printf("  FAIL rand: RAND_bytes returned error\n"); fails++;
    } else {
        int allzero = 1, equal = 1;
        for (int i = 0; i < 32; i++) { if (r1[i]) allzero = 0; if (r1[i] != r2[i]) equal = 0; }
        if (allzero || equal) { printf("  FAIL rand: draws all-zero or equal\n"); fails++; }
        else printf("  ok   rand(32) sized/nonzero/distinct\n");
    }

    if (fails) { printf("== android_kat: %d FAILURE(S) ==\n", fails); return 1; }
    printf("== android_kat: all vectors OK ==\n");
    return 0;
}
