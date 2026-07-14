#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────────
# In-container BoringSSL build for one linux triple (RFC §8a). Runs inside the manylinux2014 image
# (glibc 2.17) so the produced archives reference only Kotlin/Native-floor-safe libc symbols.
#
# Invoked by :boringssl-build's build task as:
#   docker run ... <image> /work/boringssl-build/docker/build-boringssl.sh <triple>
# with CFLAGS + CC passed via env. The repo root is bind-mounted at /work.
#
# Args: $1 = konan triple id (linuxX64 | linuxArm64)
# Env : CFLAGS (default -fPIC), CC (default cc)
# ─────────────────────────────────────────────────────────────────────────────────────────────────
set -euo pipefail

TRIPLE="${1:?usage: build-boringssl.sh <triple>}"
CFLAGS="${CFLAGS:--fPIC}"
CC="${CC:-cc}"

SRC="/work/boringssl-build/build/boringssl/src"
BUILD="/work/boringssl-build/build/boringssl/build-${TRIPLE}"
OUT="/work/boringssl-build/libs/boringssl/${TRIPLE}"
COMMIT_MARKER=$(basename "$(ls "$SRC"/.. 2>/dev/null)")  # informational only

[ -f "$SRC/CMakeLists.txt" ] || { echo "BoringSSL source missing at $SRC (run fetchBoringSsl)"; exit 1; }

echo "== cmake configure ($TRIPLE, CFLAGS=$CFLAGS) =="
rm -rf "$BUILD"; mkdir -p "$BUILD"; cd "$BUILD"
cmake -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_SHARED_LIBS=OFF \
      -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
      -DCMAKE_C_FLAGS="$CFLAGS" \
      -DCMAKE_CXX_FLAGS="$CFLAGS" \
      -G "Unix Makefiles" "$SRC"

echo "== make ssl crypto ($TRIPLE) =="
make -j"$(nproc)" ssl crypto

mkdir -p "$OUT/lib" "$OUT/include"
find . -name libssl.a    -exec cp {} "$OUT/lib/" \;
find . -name libcrypto.a -exec cp {} "$OUT/lib/" \;

# __isoc23_* compat shim, ar-merged into libcrypto.a. Redundant on glibc 2.17 (no C23 redirect) but
# kept as belt-and-suspenders so a host-built (-PnoContainer) archive is also K/N-linkable. Declaring
# the real entry points via __asm__ (no <stdlib.h>) avoids the redirect => no self-recursion.
cat > isoc23.c <<'EOF'
extern unsigned long long strtoull(const char*,char**,int) __asm__("strtoull");
extern long long          strtoll (const char*,char**,int) __asm__("strtoll");
extern unsigned long      strtoul (const char*,char**,int) __asm__("strtoul");
extern long               strtol  (const char*,char**,int) __asm__("strtol");
unsigned long long __isoc23_strtoull(const char*n,char**e,int b){return strtoull(n,e,b);}
long long          __isoc23_strtoll (const char*n,char**e,int b){return strtoll (n,e,b);}
unsigned long      __isoc23_strtoul (const char*n,char**e,int b){return strtoul (n,e,b);}
long               __isoc23_strtol  (const char*n,char**e,int b){return strtol  (n,e,b);}
EOF
$CC -c -fPIC -O2 isoc23.c -o isoc23.o
ar r "$OUT/lib/libcrypto.a" isoc23.o

# Headers (BoringSSL: src/include/openssl/*.h; older layouts: include/).
if [ -d "$SRC/src/include" ]; then cp -a "$SRC/src/include/." "$OUT/include/"; else cp -a "$SRC/include/." "$OUT/include/"; fi

echo "built ($TRIPLE) on $(getconf GNU_LIBC_VERSION) → $OUT/lib"
