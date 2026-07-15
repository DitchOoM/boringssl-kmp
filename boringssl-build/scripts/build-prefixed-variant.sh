#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────────
# Build a CONTENT-ADDRESSED, symbol-PREFIXED BoringSSL variant (RFC §12 D8) + its plain->b<hash>_ alias
# adapter, for one triple. Productionizes the proven spike (spikes/d8-alias-shim) into the factory: it
# is the opt-in second coordinate that lets a different BoringSSL coexist in-process with quiche's
# canonical unprefixed copy.
#
# Steps (per the spike, now packaging real artifacts):
#   1. build libcrypto.a + libssl.a UNPREFIXED (src/CMakeLists.txt — the prefix-aware cmake)
#   2. read_symbols.go → symbols.txt
#   3. build PREFIXED with BORINGSSL_PREFIX=<prefix> (+ the same platform cmake args)
#   4. generate the alias adapter from boringssl_prefix_symbols.h ∩ the archive's defined symbols:
#        • aliases.macho   (ld64 -alias_list:  "_<prefix>_NAME _NAME")
#        • aliases.elf.ld  (ld PROVIDE script: "PROVIDE(NAME = <prefix>_NAME);")
#      Both are emitted regardless of host so the bundle carries the adapter for its consumer's linker.
#   5. stage <outDir>/{lib,include,adapter} for the caller (:boringssl-build) to package as the
#      `-<alias>` tarball.
#
# Usage:
#   build-prefixed-variant.sh --src <boringssl-src-root> --out <outDir> --prefix b<hash8> \
#                             --format <macho|elf> [-- <extra cmake -D args...>]
# <boringssl-src-root> is the clone root (master-with-bazel layout); the prefix-aware cmake is at
# <root>/src. Extra -D args after `--` carry the platform toolchain (Apple SDK / NDK / container CC).
# ─────────────────────────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SRC_ROOT="" OUT="" PREFIX="" FORMAT=""
CMAKE_EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --src) SRC_ROOT="$2"; shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --prefix) PREFIX="$2"; shift 2 ;;
    --format) FORMAT="$2"; shift 2 ;;
    --) shift; CMAKE_EXTRA=("$@"); break ;;
    *) echo "unknown arg: $1"; exit 2 ;;
  esac
done
[ -n "$SRC_ROOT" ] && [ -n "$OUT" ] && [ -n "$PREFIX" ] && [ -n "$FORMAT" ] || { echo "missing required args"; exit 2; }

CMAKE_SRC="$SRC_ROOT/src"   # master-with-bazel keeps the prefix-aware CMakeLists under src/
[ -f "$CMAKE_SRC/CMakeLists.txt" ] || { echo "prefix-aware CMakeLists not found at $CMAKE_SRC"; exit 1; }
WORK="$OUT/work"
B_PLAIN="$WORK/build-plain"
B_PREF="$WORK/build-prefixed"
mkdir -p "$OUT/lib" "$OUT/include" "$OUT/adapter" "$WORK"

echo "── prefixed variant: prefix=$PREFIX format=$FORMAT ──"

cmake_build() {  # $1=builddir  $2..=extra -D args
  local bdir="$1"; shift
  cmake -S "$CMAKE_SRC" -B "$bdir" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    "${CMAKE_EXTRA[@]}" "$@" >"$bdir.cmake.log" 2>&1
  cmake --build "$bdir" --target crypto ssl >"$bdir.build.log" 2>&1
}

echo "[1/5] unprefixed build"
cmake_build "$B_PLAIN"
PLAIN_CRYPTO="$B_PLAIN/crypto/libcrypto.a"; PLAIN_SSL="$B_PLAIN/ssl/libssl.a"
[ -f "$PLAIN_CRYPTO" ] || { echo "unprefixed libcrypto.a missing"; exit 1; }

echo "[2/5] symbol surface (read_symbols.go)"
( cd "$CMAKE_SRC" && go run util/read_symbols.go -out "$WORK/symbols.txt" "$PLAIN_CRYPTO" "$PLAIN_SSL" ) >/dev/null 2>&1
echo "      $(grep -vc '^#' "$WORK/symbols.txt") symbols"

echo "[3/5] prefixed build ($PREFIX)"
cmake_build "$B_PREF" -DBORINGSSL_PREFIX="$PREFIX" -DBORINGSSL_PREFIX_SYMBOLS="$WORK/symbols.txt"
PREF_CRYPTO="$B_PREF/crypto/libcrypto.a"; PREF_SSL="$B_PREF/ssl/libssl.a"
PREF_HDR="$B_PREF/symbol_prefix_include/boringssl_prefix_symbols.h"
[ -f "$PREF_CRYPTO" ] || { echo "prefixed libcrypto.a missing"; exit 1; }
cp "$PREF_CRYPTO" "$OUT/lib/libcrypto.a"
cp "$PREF_SSL" "$OUT/lib/libssl.a"
# Public headers are unchanged (plain names); ship them so a consumer using the adapter compiles normally.
INC_SRC="$CMAKE_SRC/include"; [ -d "$INC_SRC" ] || INC_SRC="$SRC_ROOT/include"
cp -R "$INC_SRC/." "$OUT/include/"
cp "$PREF_HDR" "$OUT/adapter/boringssl_prefix_symbols.h"

echo "[4/5] alias adapter ($FORMAT, from the prefix header ∩ defined symbols)"
grep -oE '^#define [A-Za-z_][A-Za-z0-9_]+ BORINGSSL_ADD_PREFIX' "$PREF_HDR" | awk '{print $2}' | sort -u > "$WORK/header_names.txt"
# nm reflects the HOST object format (Mach-O symbols carry a leading underscore; ELF do not), so only the
# adapter matching --format is generatable here — each platform's variant is built on its own host.
nm -g --defined-only "$OUT/lib/libcrypto.a" "$OUT/lib/libssl.a" 2>/dev/null | awk 'NF>=3 && $2 ~ /^[A-Za-z]$/ {print $3}' | sort -u > "$WORK/defined_syms.txt"
ADAPTER="$OUT/adapter/aliases.$FORMAT"
: > "$ADAPTER"
while read -r NAME; do
  case "$FORMAT" in
    macho) grep -qxF "_${PREFIX}_${NAME}" "$WORK/defined_syms.txt" && printf '_%s_%s _%s\n' "$PREFIX" "$NAME" "$NAME" >> "$ADAPTER" ;;
    elf)   grep -qxF "${PREFIX}_${NAME}"  "$WORK/defined_syms.txt" && printf 'PROVIDE(%s = %s_%s);\n' "$NAME" "$PREFIX" "$NAME" >> "$ADAPTER" ;;
  esac
done < "$WORK/header_names.txt"
COUNT=$(wc -l < "$ADAPTER" | tr -d ' ')
echo "      $FORMAT aliases: $COUNT"
[ "$COUNT" -gt 0 ] || { echo "adapter is EMPTY — nm/format mismatch (building $FORMAT on the wrong host?)"; exit 1; }

echo "[5/5] staged variant → $OUT (lib/ include/ adapter/)"
rm -rf "$WORK"   # drop the ~large intermediate cmake trees; keep only the staged artifacts
echo "prefixed-variant OK: $PREFIX ($FORMAT)"
