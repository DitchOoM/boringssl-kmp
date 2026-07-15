#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────────
# In-container FFI shared-library link for one linux triple (RFC §4/§8a). Runs inside the SAME
# manylinux2014 image (glibc 2.17) as the archives, so the .so carries the same K/N-floor-safe libc
# dependency as the tarball's .a.
#
# This is the CHEAP half of the build: it depends only on the curated shim (boringssl_shim.{h,c} +
# the exports map) and the already-built static archives — it does NOT recompile BoringSSL. Splitting
# it out from build-archives.sh is what makes growing the shim surface a fast re-link instead of a
# full ~10-min BoringSSL rebuild (build.gradle.kts keys the two tasks on separate markers).
#
# Invoked by :boringssl-build's link task as:
#   docker run ... <image> /work/boringssl-build/docker/link-ffi.sh <triple>
# with CC passed via env. The repo root is bind-mounted at /work.
#
# Args: $1 = konan triple id (linuxX64 | linuxArm64)
# Env : CC (default cc)
# Requires: $OUT/lib/{libssl.a,libcrypto.a} + $OUT/include (produced by build-archives.sh first)
# Produces: $OUT/lib/libboringsslffi.so
# ─────────────────────────────────────────────────────────────────────────────────────────────────
set -euo pipefail

TRIPLE="${1:?usage: link-ffi.sh <triple>}"
CC="${CC:-cc}"

OUT="/work/boringssl-build/libs/boringssl/${TRIPLE}"
SHIM_DIR="/work/boringssl-build/docker"
WORK="/work/boringssl-build/build/boringssl/link-${TRIPLE}"

for a in libssl.a libcrypto.a; do
  [ -f "$OUT/lib/$a" ] || { echo "static archive missing: $OUT/lib/$a (run build-archives.sh first)"; exit 1; }
done

rm -rf "$WORK"; mkdir -p "$WORK"; cd "$WORK"

# ── libboringsslffi.so — the JVM/FFM shared library (RFC §4) ─────────────────────────────────────
# Link the curated shim + the pre-built static archives into ONE shared object. Only the shim's
# boringssl_ffi_* symbols are exported (version script); every BoringSSL symbol is hidden
# (--exclude-libs,ALL) so a second in-process BoringSSL (e.g. quiche_jni) cannot collide — the D3
# one-unprefixed-copy invariant.
echo "== link libboringsslffi.so ($TRIPLE) =="
$CC -c -fPIC -O2 -fvisibility=hidden -I"$OUT/include" "$SHIM_DIR/boringssl_shim.c" -o boringssl_shim.o
$CC -shared -fPIC -o "$OUT/lib/libboringsslffi.so" boringssl_shim.o \
    "$OUT/lib/libssl.a" "$OUT/lib/libcrypto.a" \
    -Wl,--exclude-libs,ALL -Wl,--version-script="$SHIM_DIR/boringssl_ffi_exports.map" \
    -Wl,--gc-sections -lpthread
strip --strip-unneeded "$OUT/lib/libboringsslffi.so"

echo "linked ($TRIPLE) → $OUT/lib/libboringsslffi.so"
echo "-- libboringsslffi.so exported symbols (expect only boringssl_ffi_*) --"
nm -D --defined-only "$OUT/lib/libboringsslffi.so" | awk '$2=="T"{print $3}' | sort
