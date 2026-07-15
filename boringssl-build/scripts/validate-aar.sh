#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────────────────────────
# validate-aar.sh — binary-shaped validation of the :boringssl-android prefab AAR (RFC §8).
#
# The Android analog of validate-artifacts.sh: it proves the SHIPPED artifact (the AAR) is well-formed
# and consumable, not just that :boringssl-build produced archives. Static + link-time checks (no
# emulator — the runtime KAT is a separate emulator job):
#   1. AAR unzips; AndroidManifest.xml + classes.jar + prefab/prefab.json present
#   2. prefab.json is schema_version 2, name "boringssl"
#   3. crypto + ssl modules present; ssl exports :crypto; each carries per-ABI abi.json + the .a
#   4. the static archives export a PLAIN SHA256_Init (D3 one-unprefixed-copy)
#   5. a consumer NDK-links against the EXTRACTED payload (ssl → crypto) for every ABI — link failure
#      (e.g. an above-Bionic-floor symbol, or a missing/corrupt archive) fails the build
#
# Usage:  validate-aar.sh <aar> <ndk-dir>
# ─────────────────────────────────────────────────────────────────────────────────────────────────
set -euo pipefail

AAR="${1:?usage: validate-aar.sh <aar> <ndk-dir>}"
NDK="${2:?usage: validate-aar.sh <aar> <ndk-dir>}"
ABIS=(arm64-v8a x86_64)
API=24

declare -A TRIPLE=([arm64-v8a]=aarch64-linux-android [x86_64]=x86_64-linux-android)

fail() { echo "::error::validate-aar: $*"; exit 1; }
ok()   { echo "  ✓ $*"; }

PREBUILT="$(echo "$NDK"/toolchains/llvm/prebuilt/*/ | awk '{print $1}')"
CLANG="${PREBUILT}bin/clang"
NM="${PREBUILT}bin/llvm-nm"
[ -x "$CLANG" ] || fail "NDK clang not found under $NDK"

echo "== validate-aar: $(basename "$AAR") (ndk=$NDK) =="
[ -f "$AAR" ] || fail "AAR not found: $AAR"

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
unzip -q "$AAR" -d "$W"

# ── 1. AAR shell ─────────────────────────────────────────────────────────────────────────────────
for f in AndroidManifest.xml classes.jar prefab/prefab.json; do
  [ -e "$W/$f" ] || fail "AAR missing $f"
done
ok "AAR shell present (manifest + classes.jar + prefab/)"

# ── 2. prefab.json ───────────────────────────────────────────────────────────────────────────────
python3 - "$W/prefab/prefab.json" <<'PY' || fail "prefab.json invalid"
import json, sys
d = json.load(open(sys.argv[1]))
assert d.get("schema_version") == 2, f"schema_version != 2: {d.get('schema_version')}"
assert d.get("name") == "boringssl", f"name != boringssl: {d.get('name')}"
PY
ok "prefab.json ok (schema_version 2, name boringssl)"

# ── 3. modules + per-ABI payload ─────────────────────────────────────────────────────────────────
declare -A LIBBASE=([crypto]=libcrypto [ssl]=libssl)
for m in crypto ssl; do
  mj="$W/prefab/modules/$m/module.json"
  [ -f "$mj" ] || fail "module $m: module.json missing"
  python3 - "$mj" "$m" <<'PY' || fail "module.json invalid"
import json, sys
d = json.load(open(sys.argv[1])); m = sys.argv[2]
assert d.get("library_name") == "lib"+m, f"{m}: library_name={d.get('library_name')}"
if m == "ssl":
    assert ":crypto" in d.get("export_libraries", []), "ssl must export :crypto"
PY
  for abi in "${ABIS[@]}"; do
    d="$W/prefab/modules/$m/libs/android.$abi"
    [ -f "$d/${LIBBASE[$m]}.a" ] || fail "module $m / $abi: ${LIBBASE[$m]}.a missing"
    python3 - "$d/abi.json" "$abi" "$API" <<'PY' || fail "abi.json invalid ($m/$abi)"
import json, sys
d = json.load(open(sys.argv[1]))
assert d["abi"] == sys.argv[2], f"abi={d['abi']}"
assert d["api"] == int(sys.argv[3]), f"api={d['api']}"
assert d["static"] is True and d["stl"] == "none", f"static/stl={d}"
PY
  done
done
ok "crypto + ssl modules well-formed (ssl→:crypto; per-ABI abi.json + .a)"

# ── 4. D3 un-mangled symbol ──────────────────────────────────────────────────────────────────────
for abi in "${ABIS[@]}"; do
  syms="$("$NM" "$W/prefab/modules/crypto/libs/android.$abi/libcrypto.a" 2>/dev/null || true)"
  grep -qE '(^| )T _?SHA256_Init$' <<<"$syms" || fail "$abi: libcrypto.a lacks a plain SHA256_Init (prefix-mangled?)"
done
ok "static archives export un-mangled SHA256_Init (D3)"

# ── 5. consumer link against the EXTRACTED payload (per ABI) ─────────────────────────────────────
cat > "$W/consumer.c" <<'EOF'
#include <openssl/ssl.h>
#include <openssl/sha.h>
int consume(void){ SSL_CTX *c=SSL_CTX_new(DTLS_method()); unsigned char o[32]; SHA256((unsigned char*)"abc",3,o); return (c!=0)+o[0]; }
EOF
for abi in "${ABIS[@]}"; do
  M="$W/prefab/modules"
  "$CLANG" --target="${TRIPLE[$abi]}$API" -fPIC -shared -o "$W/c-$abi.so" "$W/consumer.c" \
     -I "$M/ssl/include" -I "$M/crypto/include" \
     "$M/ssl/libs/android.$abi/libssl.a" "$M/crypto/libs/android.$abi/libcrypto.a" \
     -Wl,--no-undefined 2>"$W/e-$abi" \
     || { echo "--- link errors ($abi) ---"; head -10 "$W/e-$abi"; fail "$abi: consumer failed to link against the AAR payload"; }
done
ok "consumer NDK-links against the AAR payload (both ABIs, ssl→crypto)"

echo "== validate-aar: OK =="
