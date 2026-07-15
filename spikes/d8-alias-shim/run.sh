#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────────
# D8 alias-shim spike driver (RFC §12 D8). Proves the content-addressed prefixing + generated
# link-time alias adapter end-to-end, on ELF (Linux) AND Mach-O (macOS), INCLUDING asm-defined symbols:
#
#   1. clone google/boringssl @ the CANONICAL pin (read from gradle/libs.versions.toml — the ONE
#      commit literal in the tree; no second copy, per the single-pin directive).
#   2. build libcrypto.a UNPREFIXED.
#   3. read_symbols.go → symbols.txt (the exported surface).
#   4. build libcrypto.a PREFIXED with BORINGSSL_PREFIX=b<hash8> (letter + 8 hex; a C identifier can't
#      start with a digit) — every symbol, incl. asm, becomes b<hash8>_<name>.
#   5. generate the plain -> b<hash8>_ alias table from the prefixed archive's defined symbols
#      (cross-checked against boringssl_prefix_symbols.h), materialized per-platform:
#         • Mach-O:  ld64  -alias_list   (lines: "_b<hash>_NAME _NAME")
#         • ELF:     ld    linker script (lines: "NAME = b<hash>_NAME;")
#   6. compile the PLAIN-symbol consumer (consumer.c) against the normal headers, link it against the
#      PREFIXED archive + the alias table, and RUN it.
#
# A green run means a plain-symbol consumer (quiche/boring-sys stand-in) binds to a content-addressed
# BoringSSL with no source changes — the mechanism D8 needs to lift D3's "one unprefixed copy" to
# "one copy per distinct commit."
#
# Usage: run.sh [macho|elf]   (default: auto-detect from uname)
# ─────────────────────────────────────────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"

PLATFORM="${1:-}"
if [ -z "$PLATFORM" ]; then
  case "$(uname -s)" in
    Darwin) PLATFORM=macho ;;
    Linux)  PLATFORM=elf ;;
    *) echo "unsupported host: $(uname -s)"; exit 2 ;;
  esac
fi

# The ONE commit literal lives in the catalog (single-pin directive) — read it, never hardcode.
COMMIT="$(grep -E '^boringssl = "' "$REPO_ROOT/gradle/libs.versions.toml" | head -1 | sed -E 's/.*"([0-9a-f]{40})".*/\1/')"
[ -n "$COMMIT" ] || { echo "could not read boringssl commit from the catalog"; exit 1; }
PREFIX="b${COMMIT:0:8}"

WORK="${WORK:-$HERE/build}"
SRC="$WORK/src"
B_PLAIN="$WORK/build-plain"
B_PREF="$WORK/build-prefixed"
OUT="$WORK/out-$PLATFORM"
mkdir -p "$WORK" "$OUT"

echo "── D8 alias-shim spike ──────────────────────────────────────────────"
echo "platform=$PLATFORM  commit=$COMMIT  prefix=$PREFIX"
echo "work=$WORK"
echo

# ── 1. Source (shallow, exact SHA; master-with-bazel layout keeps the CMake tree under src/) ──
if [ ! -f "$SRC/src/CMakeLists.txt" ]; then
  echo "[1/6] cloning google/boringssl @ $COMMIT"
  rm -rf "$SRC"; mkdir -p "$SRC"; ( cd "$SRC"
    git init -q
    git remote add origin https://github.com/google/boringssl.git
    git fetch -q --depth 1 origin "$COMMIT"
    git checkout -q FETCH_HEAD )
else
  echo "[1/6] reusing source at $SRC"
fi
CMAKE_SRC="$SRC/src"

cmake_build() {  # $1=builddir  $2..=extra cmake args
  local bdir="$1"; shift
  cmake -S "$CMAKE_SRC" -B "$bdir" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    "$@" >"$bdir.cmake.log" 2>&1
  cmake --build "$bdir" --target crypto >"$bdir.build.log" 2>&1
}

# ── 2. Unprefixed build ──
echo "[2/6] building libcrypto.a UNPREFIXED"
cmake_build "$B_PLAIN"
PLAIN_LIB="$B_PLAIN/crypto/libcrypto.a"
[ -f "$PLAIN_LIB" ] || { echo "unprefixed libcrypto.a missing"; exit 1; }

# ── 3. Symbol list ──
echo "[3/6] extracting the exported symbol surface (read_symbols.go)"
( cd "$CMAKE_SRC" && go run util/read_symbols.go -out "$WORK/symbols.txt" "$PLAIN_LIB" ) >/dev/null 2>&1
echo "      $(grep -vc '^#' "$WORK/symbols.txt") symbols"

# ── 4. Prefixed build ──
echo "[4/6] building libcrypto.a PREFIXED ($PREFIX)"
cmake_build "$B_PREF" -DBORINGSSL_PREFIX="$PREFIX" -DBORINGSSL_PREFIX_SYMBOLS="$WORK/symbols.txt"
PREF_LIB="$B_PREF/crypto/libcrypto.a"
[ -f "$PREF_LIB" ] || { echo "prefixed libcrypto.a missing"; exit 1; }
PREF_HDR="$B_PREF/symbol_prefix_include/boringssl_prefix_symbols.h"

# ── 5. Alias table (from the header's symbol set ∩ what the prefixed archive actually defines) ──
echo "[5/6] generating the plain -> ${PREFIX}_ alias table ($PLATFORM)"
# Names the header says to prefix.
grep -oE '^#define [A-Za-z_][A-Za-z0-9_]+ BORINGSSL_ADD_PREFIX' "$PREF_HDR" | awk '{print $2}' | sort -u > "$OUT/header_names.txt"
# Symbols actually defined (external, any defined type) in the prefixed archive.
nm -g --defined-only "$PREF_LIB" 2>/dev/null | awk 'NF>=3 && $2 ~ /^[A-Za-z]$/ {print $3}' | sort -u > "$OUT/defined_syms.txt"

ALIASES="$OUT/aliases"
: > "$ALIASES"
asm_in_table=0
while read -r NAME; do
  case "$PLATFORM" in
    macho)
      real="_${PREFIX}_${NAME}"
      grep -qxF "$real" "$OUT/defined_syms.txt" || continue
      printf '%s _%s\n' "$real" "$NAME" >> "$ALIASES"   # ld64: "<real> <alias>"
      ;;
    elf)
      real="${PREFIX}_${NAME}"
      grep -qxF "$real" "$OUT/defined_syms.txt" || continue
      printf '%s = %s;\n' "$NAME" "$real" >> "$ALIASES"  # ld script: "<alias> = <real>;"
      ;;
  esac
done < "$OUT/header_names.txt"
echo "      $(wc -l < "$ALIASES" | tr -d ' ') alias entries"
for s in sha256_block_data_order ChaCha20_ctr32; do
  grep -qE "(_|[= ])${s}\b" "$ALIASES" && { echo "      asm symbol present: $s"; asm_in_table=1; }
done
[ "$asm_in_table" = 1 ] || { echo "no asm symbols in the alias table — spike premise broken"; exit 1; }

# ── 6. Compile the PLAIN consumer, link against the PREFIXED archive + alias table, RUN ──
echo "[6/6] linking the plain-symbol consumer against the prefixed archive + alias table"
CC="${CC:-clang}"
"$CC" -I"$CMAKE_SRC/include" -c "$HERE/consumer.c" -o "$OUT/consumer.o"

echo "      consumer.o undefined PLAIN refs:"
nm -u "$OUT/consumer.o" | grep -E '(_|^| )(SHA256|ChaCha20_ctr32|sha256_block_data_order)$' | sed 's/^/        /'

case "$PLATFORM" in
  macho)
    "$CC" "$OUT/consumer.o" "$PREF_LIB" -Wl,-alias_list,"$ALIASES" -o "$OUT/consumer"
    ;;
  elf)
    # The alias linker script is passed as an ordinary linker INPUT (additive, not -T which replaces).
    "$CC" "$OUT/consumer.o" "$PREF_LIB" "$ALIASES" -lpthread -ldl -o "$OUT/consumer"
    ;;
esac

echo "── running the consumer ─────────────────────────────────────────────"
"$OUT/consumer"
echo "─────────────────────────────────────────────────────────────────────"
echo "D8 SPIKE ($PLATFORM): PASS — plain consumer bound to the ${PREFIX}-prefixed BoringSSL via aliases."
