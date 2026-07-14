#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────────
# validate-artifacts.sh — binary-shaped validation of one triple's released bundle (RFC §8).
#
# Runs the arch-INDEPENDENT guards (nm reads any arch's symbols; extraction/headers/checksums are
# host-neutral). The FFM SymbolLookup RUNTIME smoke needs an arch-matching JVM and is driven
# separately by CI (`:boringssl-jvm:jvm21Test` on the native runner) — not here.
#
# Usage:  validate-artifacts.sh <triple> <dist-dir>
#   <triple>    konan id, e.g. linuxX64 | linuxArm64
#   <dist-dir>  directory holding boringssl-<ver>-<triple>.tar.gz(+.sha256) and
#               libboringsslffi-<ver>-<triple>.so(+.sha256)
#
# Checks (all fatal):
#   1. tarball + .so checksums match their .sha256 sidecars           (directive #4: sha256 provenance)
#   2. tarball extracts; required openssl headers + libssl.a/libcrypto.a present
#   3. tarball is LEAN — carries NO shared library (the .so ships in the MRJAR, not the K/N bundle)
#   4. the static archives export their symbols UN-mangled (plain names) (D3 one-unprefixed-copy)
#   5. the .so exports EXACTLY the curated boringssl_ffi_* surface and NOTHING else — every BoringSSL
#      symbol is hidden                                                (D3 single-copy tripwire)
#   6. records the .so size (Android per-ABI budget is enforced on the static subset in step 7)
# ─────────────────────────────────────────────────────────────────────────────────────────────────
set -euo pipefail

TRIPLE="${1:?usage: validate-artifacts.sh <triple> <dist-dir>}"
DIST="${2:?usage: validate-artifacts.sh <triple> <dist-dir>}"

# nm across arches: prefer llvm-nm (arch-agnostic); fall back to binutils nm (also reads cross-arch).
NM="$(command -v llvm-nm || command -v nm)"

fail() { echo "::error::validate-artifacts($TRIPLE): $*"; exit 1; }
ok()   { echo "  ✓ $*"; }

echo "== validate-artifacts: $TRIPLE (nm=$NM) =="

# ── locate the bundle files ──────────────────────────────────────────────────────────────────────
shopt -s nullglob
tarballs=("$DIST"/boringssl-*-"$TRIPLE".tar.gz)
sos=("$DIST"/libboringsslffi-*-"$TRIPLE".so)
[ "${#tarballs[@]}" -eq 1 ] || fail "expected exactly one tarball in $DIST, found ${#tarballs[@]}"
[ "${#sos[@]}" -eq 1 ]      || fail "expected exactly one libboringsslffi .so in $DIST, found ${#sos[@]}"
TARBALL="${tarballs[0]}"
SO="${sos[0]}"

# ── 1. checksums (directive #4) ──────────────────────────────────────────────────────────────────
for f in "$TARBALL" "$SO"; do
  [ -f "$f.sha256" ] || fail "missing checksum sidecar $f.sha256"
  ( cd "$(dirname "$f")" && sha256sum -c --status "$(basename "$f").sha256" ) \
    || fail "checksum mismatch for $(basename "$f")"
done
ok "checksums verified (tarball + .so)"

# ── 2/3. extract, headers, archives, lean-tarball ────────────────────────────────────────────────
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
tar xzf "$TARBALL" -C "$WORK"

for h in include/openssl/base.h include/openssl/crypto.h include/openssl/ssl.h include/openssl/sha.h; do
  [ -f "$WORK/$h" ] || fail "required header missing from tarball: $h"
done
ok "required openssl headers present"

for a in lib/libcrypto.a lib/libssl.a; do
  [ -f "$WORK/$a" ] || fail "required static archive missing from tarball: $a"
done
# "exactly one libcrypto" — the bundle must not carry duplicate crypto archives.
crypto_count="$(find "$WORK" -name 'libcrypto.a' | wc -l | tr -d ' ')"
[ "$crypto_count" -eq 1 ] || fail "expected exactly one libcrypto.a in the tarball, found $crypto_count"
ok "libssl.a + exactly one libcrypto.a present"

if find "$WORK" -name '*.so' -o -name '*.dylib' | grep -q .; then
  fail "tarball carries a shared library — the K/N bundle must be lean (.so ships in the MRJAR)"
fi
ok "tarball is lean (no shared library)"

# ── 4. un-mangled static symbols (D3) ────────────────────────────────────────────────────────────
# Capture nm output ONCE, then grep the string — piping nm into `grep -q` trips SIGPIPE under
# `set -o pipefail` (grep closes the pipe on first match; nm exits 141; the pipeline reads as failed).
# SHA256_Init is a stable, always-present BoringSSL symbol; it must appear under its PLAIN name. A
# symbol-prefixed build would rename it to <prefix>_SHA256_Init, so requiring the exact plain symbol
# is itself the prefix-mangling tripwire — that is the one-unprefixed-copy invariant.
crypto_syms="$("$NM" "$WORK/lib/libcrypto.a" 2>/dev/null || true)"
if ! grep -qE '(^| )T (_)?SHA256_Init$' <<<"$crypto_syms"; then
  fail "libcrypto.a does not export a plain SHA256_Init — symbol set unexpected or prefix-mangled"
fi
ok "static archives export un-mangled (plain) BoringSSL symbols"

# ── 5. the .so exports ONLY the curated shim; BoringSSL is hidden (D3 single-copy tripwire) ───────
so_dynsyms="$("$NM" -D "$SO" 2>/dev/null || true)"
exported="$(awk '$2=="T"{print $3}' <<<"$so_dynsyms" | sort -u)"
expected=$'boringssl_ffi_sha256\nboringssl_ffi_version_number'
if [ "$exported" != "$expected" ]; then
  fail "libboringsslffi.so exports an unexpected symbol set (BoringSSL leaking?):"$'\n'"$exported"
fi
# Belt-and-suspenders: a known BoringSSL internal must NOT be dynamically exported.
if grep -qE ' T (_)?SHA256_Init$' <<<"$so_dynsyms"; then
  fail "libboringsslffi.so exports BoringSSL's SHA256_Init — symbols are NOT hidden (collision risk)"
fi
ok "libboringsslffi.so exports only boringssl_ffi_* (BoringSSL hidden)"

# ── 6. size record ───────────────────────────────────────────────────────────────────────────────
so_bytes="$(stat -c%s "$SO" 2>/dev/null || stat -f%z "$SO")"
echo "  · libboringsslffi.so size: $so_bytes bytes ($(( so_bytes / 1024 )) KiB)"

echo "== validate-artifacts: $TRIPLE OK =="
