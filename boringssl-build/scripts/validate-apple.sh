#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────────
# validate-apple.sh — binary-shaped validation of one Apple triple's released bundle (RFC §8 / D2).
#
# The Apple analog of validate-artifacts.sh. Runs on macOS (Mach-O `nm`). Every Apple triple ships a
# K/N cinterop tarball (headers + libssl.a + libcrypto.a); the two macOS triples ALSO ship an FFM
# libboringsslffi.dylib for the boringssl-jvm MRJAR. There is NO glibc floor on Apple — the deployment
# target (baked into each object's LC_BUILD_VERSION) is the portability floor.
#
# Usage:  validate-apple.sh <triple> <dist-dir>
#   <triple>    konan id, e.g. macosArm64 | iosArm64 | tvosX64 | watchosX64
#   <dist-dir>  directory holding boringssl-<ver>-<triple>.tar.gz(+.sha256) and, for macOS triples,
#               libboringsslffi-<ver>-<triple>.dylib(+.sha256)
#
# Checks (all fatal):
#   1. tarball (+ dylib for macOS) checksums match their .sha256 sidecars   (directive #4: provenance)
#   2. tarball extracts; required openssl headers + libssl.a/libcrypto.a present; exactly one libcrypto
#   3. tarball is LEAN — carries NO shared library (the dylib ships in the MRJAR, not the K/N bundle)
#   4. the static archives export _SHA256_Init UN-mangled (plain name)      (D3 one-unprefixed-copy)
#   5. (macOS only) the dylib exports EXACTLY the curated _boringssl_ffi_* surface and NOTHING else —
#      every BoringSSL symbol is hidden                                     (D3 single-copy tripwire)
#   6. records the archive/dylib sizes
# ─────────────────────────────────────────────────────────────────────────────────────────────────
set -euo pipefail

TRIPLE="${1:?usage: validate-apple.sh <triple> <dist-dir>}"
DIST="${2:?usage: validate-apple.sh <triple> <dist-dir>}"

# Mach-O nm: prefer Xcode's nm; llvm-nm also reads Mach-O. Both print exported globals via `nm -gU`.
NM="$(command -v nm || command -v llvm-nm)"

fail() { echo "::error::validate-apple($TRIPLE): $*"; exit 1; }
ok()   { echo "  ✓ $*"; }

echo "== validate-apple: $TRIPLE (nm=$NM) =="

# macOS triples ship an FFM dylib; iOS/tvOS/watchOS do not (Apple non-macOS consumers cinterop — D2).
case "$TRIPLE" in
  macos*) HAS_DYLIB=1 ;;
  *)      HAS_DYLIB=0 ;;
esac

# ── locate the bundle files ──────────────────────────────────────────────────────────────────────
shopt -s nullglob
tarballs=("$DIST"/boringssl-*-"$TRIPLE".tar.gz)
[ "${#tarballs[@]}" -eq 1 ] || fail "expected exactly one tarball in $DIST, found ${#tarballs[@]}"
TARBALL="${tarballs[0]}"

DYLIB=""
if [ "$HAS_DYLIB" -eq 1 ]; then
  dylibs=("$DIST"/libboringsslffi-*-"$TRIPLE".dylib)
  [ "${#dylibs[@]}" -eq 1 ] || fail "expected exactly one libboringsslffi .dylib in $DIST, found ${#dylibs[@]}"
  DYLIB="${dylibs[0]}"
fi

# ── 1. checksums (directive #4) ──────────────────────────────────────────────────────────────────
check_sha() {
  local f="$1"
  [ -f "$f.sha256" ] || fail "missing checksum sidecar $f.sha256"
  ( cd "$(dirname "$f")" && shasum -a 256 -c --status "$(basename "$f").sha256" ) \
    || fail "checksum mismatch for $(basename "$f")"
}
check_sha "$TARBALL"
[ -n "$DYLIB" ] && check_sha "$DYLIB"
ok "checksums verified (tarball$([ -n "$DYLIB" ] && echo " + dylib"))"

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
crypto_count="$(find "$WORK" -name 'libcrypto.a' | wc -l | tr -d ' ')"
[ "$crypto_count" -eq 1 ] || fail "expected exactly one libcrypto.a in the tarball, found $crypto_count"
ok "libssl.a + exactly one libcrypto.a present"

if find "$WORK" -name '*.so' -o -name '*.dylib' | grep -q .; then
  fail "tarball carries a shared library — the K/N bundle must be lean (the dylib ships in the MRJAR)"
fi
ok "tarball is lean (no shared library)"

# ── 4. un-mangled static symbols (D3) ────────────────────────────────────────────────────────────
# Capture nm output once, then grep (avoids SIGPIPE under pipefail). On Mach-O the symbol carries a
# leading underscore (_SHA256_Init); a symbol-prefixed build would rename it, so requiring the exact
# plain name is the prefix-mangling tripwire — the one-unprefixed-copy invariant.
crypto_syms="$("$NM" "$WORK/lib/libcrypto.a" 2>/dev/null || true)"
if ! grep -qE '(^| )T _SHA256_Init$' <<<"$crypto_syms"; then
  fail "libcrypto.a does not export a plain _SHA256_Init — symbol set unexpected or prefix-mangled"
fi
ok "static archives export un-mangled (plain) BoringSSL symbols"

# ── 5. (macOS only) the dylib exports ONLY the curated shim; BoringSSL is hidden (D3 tripwire) ────
if [ -n "$DYLIB" ]; then
  # `nm -gU` = global (exported), defined-only. Assert the INVARIANT, not a frozen list: every exported
  # symbol must be a _boringssl_ffi_* shim symbol, and there must be at least one.
  exported="$("$NM" -gU "$DYLIB" 2>/dev/null | awk '{print $NF}' | sort -u)"
  [ -n "$exported" ] || fail "libboringsslffi.dylib exports no symbols — shim not linked?"
  leaked="$(grep -vE '^_boringssl_ffi_' <<<"$exported" || true)"
  if [ -n "$leaked" ]; then
    fail "libboringsslffi.dylib exports non-shim symbols (BoringSSL leaking?):"$'\n'"$leaked"
  fi
  if grep -qE '^_SHA256_Init$' <<<"$exported"; then
    fail "libboringsslffi.dylib exports BoringSSL's SHA256_Init — symbols are NOT hidden (collision risk)"
  fi
  ok "libboringsslffi.dylib exports only _boringssl_ffi_* ($(wc -l <<<"$exported" | tr -d ' ') symbols; BoringSSL hidden)"
fi

# ── 6. size record ───────────────────────────────────────────────────────────────────────────────
tar_bytes="$(stat -f%z "$TARBALL" 2>/dev/null || stat -c%s "$TARBALL")"
echo "  · tarball size: $tar_bytes bytes ($(( tar_bytes / 1024 )) KiB)"
if [ -n "$DYLIB" ]; then
  dy_bytes="$(stat -f%z "$DYLIB" 2>/dev/null || stat -c%s "$DYLIB")"
  echo "  · libboringsslffi.dylib size: $dy_bytes bytes ($(( dy_bytes / 1024 )) KiB)"
fi

echo "== validate-apple: $TRIPLE OK =="
