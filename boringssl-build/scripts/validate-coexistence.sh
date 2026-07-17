#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────────
# validate-coexistence.sh — the standing D8 COEXISTENCE gate (RFC §12 D8).
#
# Proves the canonical UNPREFIXED bundle and the content-addressed b<hash8> PREFIXED variant can live
# in ONE process image with no symbol collision — the property that lifts D3's "one unprefixed copy"
# to "one copy per distinct commit" (the mechanism proven in spikes/d8-alias-shim, promoted here to a
# per-PR gate over the REAL released tarballs). Only meaningful when boringsslPrefix="true" in the
# catalog produced the `-b<hash8>` tarball; CI skips this job entirely while the flag is "false".
#
# Checks (all fatal):
#   1. exactly one canonical + one -b<hash8> variant tarball; checksums match their .sha256 sidecars
#   2. both extract; the variant carries the alias adapter contract (adapter/aliases.elf.ld with
#      PROVIDE entries + adapter/boringssl_prefix_symbols.h)
#   3. symbol split: canonical libcrypto.a defines a PLAIN SHA256_Init; the variant defines ONLY the
#      <prefix>_ twin, and the two archives' strong defined-global sets are fully DISJOINT — any
#      shared name would collide under the consumer's --whole-archive alias link (the D8 invariant)
#   4. ONE ELF binary links BOTH copies: plain SHA256 → canonical, <prefix>_SHA256 → variant
#   5. nm on that binary: EXACTLY ONE unprefixed SHA256_Init (canonical's) + the prefixed twin
#   6. RUN it: the SHA-256("abc") KAT through BOTH copies must match the reference vector
#   7. alias-mode link (the provision plugin's actual mechanism, per the spike): a PLAIN-symbol
#      consumer + --whole-archive variant + the PROVIDE adapter — NO canonical archive — links and
#      its KAT passes, proving plain names bind to the prefixed copy through the adapter
#
# Usage:  validate-coexistence.sh <triple> <dist-dir>      (ELF triples only, e.g. linuxX64)
#   <dist-dir> holds boringssl-<ver>-<triple>.tar.gz AND boringssl-<ver>-<triple>-b<hash8>.tar.gz
#   (each with its .sha256 sidecar).
# ─────────────────────────────────────────────────────────────────────────────────────────────────
set -euo pipefail

TRIPLE="${1:?usage: validate-coexistence.sh <triple> <dist-dir>}"
DIST="${2:?usage: validate-coexistence.sh <triple> <dist-dir>}"
CC="${CC:-cc}"

# nm across arches: prefer llvm-nm (arch-agnostic); fall back to binutils nm (also reads cross-arch).
NM="$(command -v llvm-nm || command -v nm)"

fail() { echo "::error::validate-coexistence($TRIPLE): $*"; exit 1; }
ok()   { echo "  ✓ $*"; }

case "$TRIPLE" in
  linux*) ;;
  *) fail "only ELF (linux*) triples are supported — the Mach-O coexistence gate is a follow-up" ;;
esac

echo "== validate-coexistence: $TRIPLE (nm=$NM) =="

# ── 1. locate + checksum-verify both bundles ─────────────────────────────────────────────────────
shopt -s nullglob
canonicals=("$DIST"/boringssl-*-"$TRIPLE".tar.gz)
variants=("$DIST"/boringssl-*-"$TRIPLE"-b*.tar.gz)
[ "${#canonicals[@]}" -eq 1 ] || fail "expected exactly one canonical tarball in $DIST, found ${#canonicals[@]}"
[ "${#variants[@]}" -eq 1 ]   || fail "expected exactly one -b<hash8> variant tarball in $DIST, found ${#variants[@]} (is boringsslPrefix=\"true\"?)"
CANON="${canonicals[0]}"
VARIANT="${variants[0]}"

PREFIX="$(basename "$VARIANT" .tar.gz)"; PREFIX="${PREFIX##*-}"
[[ "$PREFIX" =~ ^b[0-9a-f]{8}$ ]] || fail "variant alias '$PREFIX' is not b<hash8> (from $(basename "$VARIANT"))"

for f in "$CANON" "$VARIANT"; do
  [ -f "$f.sha256" ] || fail "missing checksum sidecar $f.sha256"
  ( cd "$(dirname "$f")" && sha256sum -c --status "$(basename "$f").sha256" ) \
    || fail "checksum mismatch for $(basename "$f")"
done
ok "checksums verified (canonical + $PREFIX variant)"

# ── 2. extract; variant adapter contract ─────────────────────────────────────────────────────────
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/canonical" "$WORK/variant"
tar xzf "$CANON" -C "$WORK/canonical"
tar xzf "$VARIANT" -C "$WORK/variant"

[ -f "$WORK/canonical/lib/libcrypto.a" ] || fail "canonical lib/libcrypto.a missing"
[ -f "$WORK/variant/lib/libcrypto.a" ]   || fail "variant lib/libcrypto.a missing"
ADAPTER="$WORK/variant/adapter/aliases.elf.ld"
[ -s "$ADAPTER" ] || fail "variant adapter/aliases.elf.ld missing or empty"
[ -s "$WORK/variant/adapter/boringssl_prefix_symbols.h" ] || fail "variant adapter/boringssl_prefix_symbols.h missing or empty"
alias_count="$(grep -c 'PROVIDE(' "$ADAPTER" || true)"
[ "$alias_count" -gt 0 ] || fail "adapter/aliases.elf.ld carries no PROVIDE entries"
ok "both bundles extract; alias adapter present ($alias_count PROVIDE entries)"

# ── 3. symbol split across the two archives (the D8 no-collision invariant) ──────────────────────
# Capture nm output ONCE, then grep — piping nm into `grep -q` trips SIGPIPE under `set -o pipefail`.
canon_syms="$("$NM" "$WORK/canonical/lib/libcrypto.a" 2>/dev/null || true)"
grep -qE '(^| )T SHA256_Init$' <<<"$canon_syms" \
  || fail "canonical libcrypto.a does not export a plain SHA256_Init (D3)"
variant_syms="$("$NM" "$WORK/variant/lib/libcrypto.a" 2>/dev/null || true)"
grep -qE "(^| )T ${PREFIX}_SHA256_Init$" <<<"$variant_syms" \
  || fail "variant libcrypto.a does not export ${PREFIX}_SHA256_Init — not a $PREFIX-prefixed build?"
if grep -qE '(^| )T SHA256_Init$' <<<"$variant_syms"; then
  fail "variant libcrypto.a ALSO defines a plain SHA256_Init — it would collide with the canonical copy"
fi
# Whole-surface collision predicate, not just the SHA256_Init sample: the STRONG defined-global sets
# of the two archives must be DISJOINT. A partially-prefixed variant (e.g. an unprefixed asm symbol
# on a future variantCommit) would slip past step 4's lazy link — the canonical copy already defines
# the plain name, so the offending variant member is never extracted — yet an alias-mode consumer
# (--whole-archive, BoringSslProvisionPlugin) extracts EVERY member and collides. Weak (W/V/w/v)
# definitions merge without error and are excluded.
"$NM" -g --defined-only "$WORK/canonical/lib/libcrypto.a" 2>/dev/null \
  | awk 'NF>=3 && $2 ~ /^[A-Za-z]$/ && $2 !~ /^[WwVv]$/ {print $3}' | sort -u > "$WORK/canon_globals.txt"
"$NM" -g --defined-only "$WORK/variant/lib/libcrypto.a" 2>/dev/null \
  | awk 'NF>=3 && $2 ~ /^[A-Za-z]$/ && $2 !~ /^[WwVv]$/ {print $3}' | sort -u > "$WORK/variant_globals.txt"
collisions="$(comm -12 "$WORK/canon_globals.txt" "$WORK/variant_globals.txt")"
if [ -n "$collisions" ]; then
  n="$(wc -l <<<"$collisions" | tr -d ' ')"
  fail "canonical and variant libcrypto.a BOTH define $n strong global(s) — e.g. $(head -3 <<<"$collisions" | tr '\n' ' ')— a --whole-archive alias-mode link would collide"
fi
ok "symbol split: canonical=plain, variant=${PREFIX}_ only ($(wc -l < "$WORK/variant_globals.txt" | tr -d ' ') variant globals, all disjoint from canonical)"

# ── 4. link ONE binary against BOTH copies ───────────────────────────────────────────────────────
# The consumer calls SHA-256 twice: through the plain name (lazily pulls the CANONICAL archive
# member) and through the <prefix>_ name (lazily pulls the VARIANT member). Because the variant is
# fully prefixed (incl. asm), the two extracted member sets share no symbol — the link itself is the
# collision check (a duplicate strong definition would fail it).
cat > "$WORK/coexist.c" <<EOF
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <openssl/sha.h>
/* The variant's content-addressed twin of the one-shot SHA256 entry point. */
extern uint8_t *${PREFIX}_SHA256(const uint8_t *data, size_t len, uint8_t *out);

/* SHA-256("abc") reference vector, as bytes. */
static const uint8_t kRef[32] = {
    0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea, 0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
    0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c, 0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad,
};

int main(void) {
    const uint8_t msg[3] = {'a', 'b', 'c'};
    uint8_t via_canonical[32] = {0};
    uint8_t via_variant[32] = {0};
    SHA256(msg, sizeof(msg), via_canonical);
    ${PREFIX}_SHA256(msg, sizeof(msg), via_variant);
    if (memcmp(via_canonical, kRef, sizeof(kRef)) != 0) { fprintf(stderr, "canonical KAT MISMATCH\n"); return 1; }
    if (memcmp(via_variant, kRef, sizeof(kRef)) != 0)   { fprintf(stderr, "variant (${PREFIX}) KAT MISMATCH\n"); return 1; }
    printf("coexistence KAT OK: canonical + ${PREFIX} copies both match SHA-256(\"abc\")\n");
    return 0;
}
EOF
"$CC" -O2 -I "$WORK/canonical/include" "$WORK/coexist.c" \
  "$WORK/canonical/lib/libcrypto.a" "$WORK/variant/lib/libcrypto.a" \
  -lpthread -ldl -o "$WORK/coexist" \
  || fail "linking canonical + variant into one binary FAILED (symbol collision?)"
ok "one ELF binary links BOTH copies"

# ── 5. exactly one unprefixed copy in the binary ─────────────────────────────────────────────────
bin_syms="$("$NM" "$WORK/coexist" 2>/dev/null || true)"
plain_count="$(grep -cE '(^| )T SHA256_Init$' <<<"$bin_syms" || true)"
prefixed_count="$(grep -cE "(^| )T ${PREFIX}_SHA256_Init$" <<<"$bin_syms" || true)"
[ "$plain_count" -eq 1 ]    || fail "expected EXACTLY ONE unprefixed SHA256_Init in the binary, found $plain_count"
[ "$prefixed_count" -eq 1 ] || fail "expected the ${PREFIX}_SHA256_Init twin in the binary, found $prefixed_count"
ok "nm: exactly one unprefixed SHA256_Init + one ${PREFIX}_SHA256_Init"

# ── 6. run the KAT through both copies ───────────────────────────────────────────────────────────
"$WORK/coexist" || fail "coexistence KAT binary failed"
ok "KAT ran through BOTH copies"

# ── 7. alias-mode link: plain consumer + --whole-archive variant + the PROVIDE adapter ───────────
# Reproduces the spike's proven mechanism (spikes/d8-alias-shim/run.sh step 6) and the provision
# plugin's alias linkerOpts: the consumer references ONLY plain names, the variant archive is
# whole-archived (so every ${PREFIX}_ alias target is defined), and the PROVIDE script is an
# ordinary linker INPUT (additive; not -T). No canonical archive is on the link line — plain names
# can bind ONLY through the adapter, so a malformed or wrongly-named adapter entry fails here
# instead of at every alias-mode consumer's link.
cat > "$WORK/aliasmode.c" <<EOF
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <openssl/sha.h>

/* SHA-256("abc") reference vector, as bytes. */
static const uint8_t kRef[32] = {
    0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea, 0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
    0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c, 0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad,
};

int main(void) {
    const uint8_t msg[3] = {'a', 'b', 'c'};
    uint8_t out[32] = {0};
    SHA256(msg, sizeof(msg), out);
    if (memcmp(out, kRef, sizeof(kRef)) != 0) { fprintf(stderr, "alias-mode KAT MISMATCH\n"); return 1; }
    printf("alias-mode KAT OK: plain SHA256 bound to the ${PREFIX} copy via the adapter\n");
    return 0;
}
EOF
"$CC" -O2 -I "$WORK/variant/include" "$WORK/aliasmode.c" \
  -Wl,--whole-archive "$WORK/variant/lib/libcrypto.a" -Wl,--no-whole-archive \
  "$ADAPTER" -lpthread -ldl -o "$WORK/aliasmode" \
  || fail "alias-mode link (plain consumer + whole-archive variant + adapter) FAILED — adapter contract broken?"
"$WORK/aliasmode" || fail "alias-mode KAT failed — the adapter did not bind plain names to the ${PREFIX}_ copy"
ok "alias adapter binds plain SHA256 to the ${PREFIX} copy (whole-archive + PROVIDE script)"

echo "== validate-coexistence: $TRIPLE OK (canonical + $PREFIX coexist; $alias_count aliases) =="
