#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────────
# Build a CONTENT-ADDRESSED, symbol-PREFIXED BoringSSL variant (RFC §12 D8) + its plain->b<hash>_ alias
# adapter, for one triple. Productionizes the proven spike (spikes/d8-alias-shim) into the factory: it
# is the opt-in second coordinate that lets a different BoringSSL coexist in-process with quiche's
# canonical unprefixed copy.
#
# Steps (per the spike, now packaging real artifacts):
#   1. build libcrypto.a + libssl.a UNPREFIXED (skipped when the tree ships its own prefix headers)
#   2. read_symbols.go → symbols.txt (likewise skipped: see TREE_PREFIX below)
#   3. build PREFIXED with BORINGSSL_PREFIX=<prefix> (+ the same platform cmake args)
#   4. generate the alias adapter from the prefix header ∩ the archive's defined symbols:
#        • aliases.macho   (ld64 -alias_list:  "_<prefix>_NAME _NAME")
#        • aliases.elf.ld  (ld PROVIDE script: "PROVIDE(NAME = <prefix>_NAME);")
#      Both are emitted regardless of host so the bundle carries the adapter for its consumer's linker.
#   5. stage <outDir>/{lib,include,adapter} for the caller (:boringssl-build) to package as the
#      `-<alias>` tarball.
#
# Usage:
#   build-prefixed-variant.sh --src <boringssl-src-root> --out <outDir> --prefix b<hash8> \
#                             --format <macho|elf> [-- <extra cmake -D args...>]
# <boringssl-src-root> is the clone root. Two BoringSSL layouts are handled:
#   • main (2024+): CMakeLists.txt at the root, and the prefix map is CHECKED IN as
#     include/openssl/prefix_symbols{,_internal_c}.h — BORINGSSL_PREFIX alone prefixes the build.
#   • master-with-bazel (older pins): the prefix-aware cmake is at <root>/src and needs the
#     read_symbols.go → BORINGSSL_PREFIX_SYMBOLS two-pass build.
# Extra -D args after `--` carry the platform toolchain (Apple SDK / NDK / container CC).
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

if [ -f "$SRC_ROOT/src/CMakeLists.txt" ]; then
  CMAKE_SRC="$SRC_ROOT/src"   # master-with-bazel keeps the prefix-aware CMakeLists under src/
else
  CMAKE_SRC="$SRC_ROOT"
fi
[ -f "$CMAKE_SRC/CMakeLists.txt" ] || { echo "prefix-aware CMakeLists not found under $SRC_ROOT"; exit 1; }
# main layout: the prefix map ships in-tree, so no unprefixed pass / read_symbols.go is needed.
TREE_PREFIX=0
[ -f "$CMAKE_SRC/include/openssl/prefix_symbols.h" ] && TREE_PREFIX=1
WORK="$OUT/work"
B_PLAIN="$WORK/build-plain"
B_PREF="$WORK/build-prefixed"
mkdir -p "$OUT/lib" "$OUT/include" "$OUT/adapter" "$WORK"

echo "── prefixed variant: prefix=$PREFIX format=$FORMAT ──"

cmake_build() {  # $1=builddir  $2..=extra -D args
  local bdir="$1"; shift
  cmake -S "$CMAKE_SRC" -B "$bdir" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DBUILD_TESTING=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    "${CMAKE_EXTRA[@]}" "$@" >"$bdir.cmake.log" 2>&1
  cmake --build "$bdir" --target crypto ssl >"$bdir.build.log" 2>&1
}

# The archives land at the build root (main layout) or under crypto/ + ssl/ (older layout).
find_archive() { find "$1" -name "$2" -type f | head -1; }

if [ "$TREE_PREFIX" = 1 ]; then
  echo "[1/5] unprefixed build — skipped (prefix map is in-tree: include/openssl/prefix_symbols*.h)"
  echo "[2/5] symbol surface — skipped (same)"
  echo "[3/5] prefixed build ($PREFIX)"
  cmake_build "$B_PREF" -DBORINGSSL_PREFIX="$PREFIX"
  # The adapter's prefix header = the in-tree map (public + internal C names; the internal_S header
  # is the same names with the Mach-O user-label prefix). Concatenated so the bundle stays one file.
  PREF_HDR="$WORK/boringssl_prefix_symbols.h"
  cat "$CMAKE_SRC/include/openssl/prefix_symbols.h" "$CMAKE_SRC/include/openssl/prefix_symbols_internal_c.h" > "$PREF_HDR"
else
  echo "[1/5] unprefixed build"
  cmake_build "$B_PLAIN"
  PLAIN_CRYPTO="$(find_archive "$B_PLAIN" libcrypto.a)"; PLAIN_SSL="$(find_archive "$B_PLAIN" libssl.a)"
  [ -f "$PLAIN_CRYPTO" ] || { echo "unprefixed libcrypto.a missing"; exit 1; }

  echo "[2/5] symbol surface (read_symbols.go)"
  ( cd "$CMAKE_SRC" && go run util/read_symbols.go -out "$WORK/symbols.txt" "$PLAIN_CRYPTO" "$PLAIN_SSL" ) >/dev/null 2>&1
  echo "      $(grep -vc '^#' "$WORK/symbols.txt") symbols"

  echo "[3/5] prefixed build ($PREFIX)"
  cmake_build "$B_PREF" -DBORINGSSL_PREFIX="$PREFIX" -DBORINGSSL_PREFIX_SYMBOLS="$WORK/symbols.txt"
  PREF_HDR="$B_PREF/symbol_prefix_include/boringssl_prefix_symbols.h"
fi
PREF_CRYPTO="$(find_archive "$B_PREF" libcrypto.a)"; PREF_SSL="$(find_archive "$B_PREF" libssl.a)"
[ -f "$PREF_CRYPTO" ] || { echo "prefixed libcrypto.a missing"; exit 1; }
[ -f "$PREF_SSL" ] || { echo "prefixed libssl.a missing"; exit 1; }
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
# Adapter file names are the CONTRACT with the provision plugin's `cinterop(alias=…)` linkerOpts
# (BoringSslProvisionPlugin): `aliases.macho` (ld64 -alias_list) / `aliases.elf.ld` (GNU ld script).
case "$FORMAT" in
  macho) ADAPTER="$OUT/adapter/aliases.macho" ;;
  elf)   ADAPTER="$OUT/adapter/aliases.elf.ld" ;;
  *)     echo "unknown --format: $FORMAT (want macho|elf)"; exit 2 ;;
esac
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
# The spike's asm-coverage tripwire (spikes/d8-alias-shim/run.sh step 5): prefixing must reach
# asm-defined symbols too, and the adapter must alias them — their absence means a partially-prefixed
# variant whose plain asm globals would collide with the canonical copy under --whole-archive.
asm_in_table=0
# Newer trees suffix the per-ISA asm entry points (sha256_block_data_order_hw, ChaCha20_ctr32_neon, …).
for s in sha256_block_data_order ChaCha20_ctr32; do
  grep -qE "(_|[= ])${s}(_[A-Za-z0-9_]+)?\b" "$ADAPTER" && { echo "      asm symbol present: $s"; asm_in_table=1; }
done
[ "$asm_in_table" = 1 ] || { echo "no asm symbols in the alias adapter — partially-prefixed variant?"; exit 1; }

echo "[5/5] staged variant → $OUT (lib/ include/ adapter/)"
rm -rf "$WORK"   # drop the ~large intermediate cmake trees; keep only the staged artifacts
echo "prefixed-variant OK: $PREFIX ($FORMAT)"
