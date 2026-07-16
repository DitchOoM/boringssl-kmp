/* ─────────────────────────────────────────────────────────────────────────────────────────────────
 * D8 alias-shim spike — the PLAIN-symbol consumer (RFC §12 D8).
 *
 * This is a stand-in for the hard consumer: quiche / boring-sys, whose Rust `extern "C"` FFI hardcodes
 * UNPREFIXED BoringSSL names and cannot see the C-preprocessor prefix trick. It is compiled against the
 * normal (unprefixed) public headers and therefore emits references to PLAIN symbols (`SHA256`,
 * `ChaCha20_ctr32`, …). It is then linked ONLY against the PREFIXED `libcrypto.a` plus a generated
 * alias table (`plain -> b<hash>_plain`). If the alias mechanics hold, every plain reference resolves
 * to the content-addressed `b<hash>_` implementation and the program runs correctly.
 *
 * It deliberately exercises BOTH kinds of symbol:
 *   • C symbols  — SHA256 (known-answer test).
 *   • ASM symbols — ChaCha20_ctr32 (EXECUTED: RFC 8439 zero-key keystream) and sha256_block_data_order
 *                   (referenced by plain name → forces the linker to resolve an asm symbol via alias).
 * ───────────────────────────────────────────────────────────────────────────────────────────────── */
#include <openssl/sha.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

/* asm-defined BoringSSL internals, referenced by PLAIN name (not in the public headers). Proving these
 * resolve is the whole point of "INCLUDING asm-defined symbols" in the spike brief. */
extern void sha256_block_data_order(void *state, const void *in, size_t num);
extern void ChaCha20_ctr32(uint8_t *out, const uint8_t *in, size_t in_len,
                           const uint32_t key[8], const uint32_t counter[4]);

int main(void) {
    /* (a) C symbol via plain alias: SHA256("abc") known-answer (FIPS 180-4). */
    unsigned char md[32];
    SHA256((const unsigned char *)"abc", 3, md);
    char hex[65];
    for (int i = 0; i < 32; i++) sprintf(hex + 2 * i, "%02x", md[i]);
    const char *want = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    printf("SHA256(abc)=%s\n", hex);
    if (strcmp(hex, want) != 0) { printf("FAIL: SHA256 mismatch\n"); return 1; }

    /* (b) ASM symbol via plain alias, EXECUTED: ChaCha20 keystream for the all-zero key+counter is the
     *     RFC 8439 §2.3.2 test vector, whose first four bytes are 76 b8 e0 ad. */
    uint8_t out[64];  memset(out, 0, sizeof out);
    uint8_t zero[64]; memset(zero, 0, sizeof zero);
    uint32_t key[8] = {0}; uint32_t counter[4] = {0};
    ChaCha20_ctr32(out, zero, sizeof out, key, counter);
    printf("ChaCha20_ctr32 keystream[0..3]=%02x%02x%02x%02x (asm, plain-aliased)\n",
           out[0], out[1], out[2], out[3]);
    if (!(out[0] == 0x76 && out[1] == 0xb8 && out[2] == 0xe0 && out[3] == 0xad)) {
        printf("FAIL: ChaCha20 asm keystream mismatch (expected 76b8e0ad...)\n");
        return 1;
    }

    /* (c) ASM symbol referenced by plain name at link time (address use forces alias resolution). */
    printf("&sha256_block_data_order=%p (asm, plain-aliased)\n", (void *)&sha256_block_data_order);

    printf("PASS: plain C + plain ASM symbols resolved to the prefixed impls via the alias table\n");
    return 0;
}
