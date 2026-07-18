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
#   7. every symbol in contracts/*.txt is DEFINED in libcrypto.a       (consumer symbol contracts)
#   8. archive-level single-copy set-membership: the owner-embedded archives carry exactly one definition
#      of each crypto entry point — 1 SHA256_Init, 1 defn per sha256_block_data_order ISA-variant NAME
#      (the SET of variant names, not raw nm lines), 1 SSL_CTX_new. This is the ARCHIVE-level tripwire for
#      the D10 owner-one-copy invariant (a malformed archive with a duplicated definition fails here); the
#      runtime co-link check in :boringssl-testsuite:linuxX64Test proves the archive links + runs
#   9. pin alignment: the artifact's provenance.json boringsslCommit == catalog `boringssl` AND its
#      quicheAbi == catalog `boringsslQuicheAbi` — owner==quiche==catalog can't drift  (D1/D10)
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
# Assert the INVARIANT, not a frozen list (the surface grows): every exported text symbol must be a
# boringssl_ffi_* shim symbol, and there must be at least one. Anything else = a BoringSSL leak.
so_dynsyms="$("$NM" -D "$SO" 2>/dev/null || true)"
exported="$(awk '$2=="T"{print $3}' <<<"$so_dynsyms" | sort -u)"
[ -n "$exported" ] || fail "libboringsslffi.so exports no text symbols — shim not linked?"
leaked="$(grep -vE '^boringssl_ffi_' <<<"$exported" || true)"
if [ -n "$leaked" ]; then
  fail "libboringsslffi.so exports non-shim symbols (BoringSSL leaking?):"$'\n'"$leaked"
fi
# Belt-and-suspenders: a known BoringSSL internal must NOT be dynamically exported.
if grep -qE ' T (_)?SHA256_Init$' <<<"$so_dynsyms"; then
  fail "libboringsslffi.so exports BoringSSL's SHA256_Init — symbols are NOT hidden (collision risk)"
fi
ok "libboringsslffi.so exports only boringssl_ffi_* ($(wc -l <<<"$exported") symbols; BoringSSL hidden)"

# ── 6. size record ───────────────────────────────────────────────────────────────────────────────
so_bytes="$(stat -c%s "$SO" 2>/dev/null || stat -f%z "$SO")"
echo "  · libboringsslffi.so size: $so_bytes bytes ($(( so_bytes / 1024 )) KiB)"

# ── 7. consumer symbol contracts — every closure symbol stays DEFINED across pin moves ───────────
# Each contracts/*.txt is a committed allowlist of raw libcrypto functions a downstream consumer's
# cinterop wrapper closure links against (see each file's header for provenance). Every entry must
# be DEFINED (T or W, optional leading underscore — the same tolerance as check 4) in the extracted
# libcrypto.a, so a canonical-pin move can never silently drop a symbol a consumer calls. Reuses
# the check-4 `crypto_syms` capture (see the SIGPIPE note there). Misses are collected across ALL
# contract files, printed together, and failed once.
CONTRACTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/contracts"
contract_files=("$CONTRACTS_DIR"/*.txt)
[ "${#contract_files[@]}" -ge 1 ] || fail "no symbol contracts found in $CONTRACTS_DIR"
# Normalize the capture ONCE into bare DEFINED (T/W) symbol names, leading underscore stripped (the
# same tolerance as check 4) — so each contract entry is matched EXACTLY (`grep -qxF`), never
# interpolated into a regex where a stray metachar ('.', '+') would silently widen the match.
defined_syms="$(awk '($2=="T"||$2=="W"){sub(/^_/,"",$3); print $3}' <<<"$crypto_syms" | sort -u)"
missing=""
for contract in "${contract_files[@]}"; do
  checked=0
  file_missing=0
  # `|| [ -n "$sym" ]` keeps a final line without a trailing newline from being silently dropped.
  while IFS= read -r sym || [ -n "$sym" ]; do
    sym="${sym%%#*}"              # strip comments
    sym="${sym//[[:space:]]/}"    # strip whitespace
    [ -n "$sym" ] || continue
    checked=$(( checked + 1 ))
    if ! grep -qxF "$sym" <<<"$defined_syms"; then
      missing+="  $(basename "$contract"): $sym"$'\n'
      file_missing=$(( file_missing + 1 ))
    fi
  done <"$contract"
  [ "$checked" -ge 1 ] || fail "$(basename "$contract") contains no symbols — empty contract"
  if [ "$file_missing" -eq 0 ]; then
    ok "$(basename "$contract"): all $checked closure symbols defined in libcrypto.a"
  fi
done
if [ -n "$missing" ]; then
  fail "symbol contract violated — consumer-closure symbols NOT defined in libcrypto.a:"$'\n'"$missing"
fi

# ── 8. archive-level single-copy set-membership (D10 owner one-copy invariant) ──────
# The canonical owner klib (:boringssl-canonical, embedArchive=true) EMBEDS this exact libcrypto.a /
# libssl.a, and every consumer co-links against the SAME copy (external cinterop, embedArchive=false).
# Guard the SOURCE archive here: it must carry exactly ONE definition of each crypto entry point.
# BoringSSL ships sha256_block_data_order in several micro-arch variants (…_shaext / _avx / _ssse3 /
# _hw / …) — count the SET of distinct variant NAMES, each DEFINED exactly once, NOT raw nm lines (many
# variant names is still ONE copy; a single name defined twice = a duplicated crypto copy = fail). This
# is the ARCHIVE-level tripwire (a malformed embed with a duplicated def fails here); the runtime
# co-link check (:boringssl-testsuite:linuxX64Test) separately proves the archive links + runs. Reuses
# the check-4 `crypto_syms` capture (see the SIGPIPE note there). Underscore-tolerant like checks 4/7.
crypto_defs="$(awk '$2=="T"{sub(/^_/,"",$3); print $3}' <<<"$crypto_syms")"
# 8a. SHA256_Init: exactly one definition.
sha_init_defs="$(grep -cxF 'SHA256_Init' <<<"$crypto_defs" || true)"
[ "$sha_init_defs" -eq 1 ] \
  || fail "libcrypto.a defines SHA256_Init $sha_init_defs times (expected exactly 1 — duplicate crypto copy?)"
# 8b. every sha256_block_data_order* ISA-variant NAME defined exactly once.
variant_names="$(grep -E '^sha256_block_data_order' <<<"$crypto_defs" | sort -u)"
[ -n "$variant_names" ] || fail "libcrypto.a defines no sha256_block_data_order variant — symbol set unexpected"
variant_dups=""
while IFS= read -r v; do
  [ -n "$v" ] || continue
  c="$(grep -cxF "$v" <<<"$crypto_defs" || true)"
  [ "$c" -eq 1 ] || variant_dups+="  $v defined $c times"$'\n'
done <<<"$variant_names"
[ -z "$variant_dups" ] \
  || fail "duplicate crypto ISA-variant definitions (a co-link would carry >1 copy):"$'\n'"$variant_dups"
# 8c. SSL_CTX_new: exactly one definition in libssl.a (the full owner carries libssl too).
ssl_syms="$("$NM" "$WORK/lib/libssl.a" 2>/dev/null || true)"
ssl_ctx_defs="$(awk '$2=="T"{sub(/^_/,"",$3); print $3}' <<<"$ssl_syms" | grep -cxF 'SSL_CTX_new' || true)"
[ "$ssl_ctx_defs" -eq 1 ] \
  || fail "libssl.a defines SSL_CTX_new $ssl_ctx_defs times (expected exactly 1)"
ok "single-copy set-membership: 1 SHA256_Init, $(grep -c . <<<"$variant_names") sha256_block_data_order variant name(s) each once, 1 SSL_CTX_new"

# ── 9. pin alignment — provenance ⇄ catalog (owner==quiche==catalog can't drift; D1/D10) ──────────
# The owner klib embeds THIS archive, so the artifact's provenance.json IS the owner's embedded-archive
# provenance. Assert the built pin matches the single-pin catalog: the recorded boringsslCommit equals
# the catalog `boringssl` value, and the recorded quicheAbi equals the catalog `boringsslQuicheAbi`
# anchor — so the canonical commit, the quiche ABI anchor, and the catalog can never silently drift
# (e.g. an artifact built from a stale checkout). Pure text extraction (no jq dependency); the catalog
# is read live from the checkout, so NO 40-hex literal is embedded in this script (single-pin directive).
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CATALOG="$REPO_ROOT/gradle/libs.versions.toml"
[ -f "$CATALOG" ] || fail "catalog not found at $CATALOG"
provs=("$DIST"/boringssl-*-"$TRIPLE".provenance.json)
[ "${#provs[@]}" -eq 1 ] || fail "expected exactly one provenance.json for $TRIPLE in $DIST, found ${#provs[@]}"
PROV="${provs[0]}"
catalog_commit="$(grep -E '^boringssl = "' "$CATALOG" | sed -E 's/.*"([0-9a-f]{40})".*/\1/')"
catalog_quiche="$(grep -E '^boringsslQuicheAbi = "' "$CATALOG" | sed -E 's/^[^"]*"([^"]*)".*/\1/')"
prov_commit="$(grep -E '"boringsslCommit"' "$PROV" | sed -E 's/.*"boringsslCommit"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/')"
prov_quiche="$(grep -E '"quicheAbi"' "$PROV" | sed -E 's/.*"quicheAbi"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/')"
[ -n "$catalog_commit" ] || fail "could not read the boringssl pin from $CATALOG"
[ -n "$prov_commit" ]    || fail "provenance.json ($PROV) has no boringsslCommit"
[ -n "$catalog_quiche" ] || fail "could not read boringsslQuicheAbi from $CATALOG"
[ -n "$prov_quiche" ]    || fail "provenance.json ($PROV) has no quicheAbi"
[ "$prov_commit" = "$catalog_commit" ] \
  || fail "provenance boringsslCommit ($prov_commit) != catalog boringssl ($catalog_commit) — pin drift"
[ "$prov_quiche" = "$catalog_quiche" ] \
  || fail "provenance quicheAbi ($prov_quiche) != catalog boringsslQuicheAbi ($catalog_quiche) — quiche anchor drift"
ok "pin alignment: commit=$prov_commit, quiche anchor='$prov_quiche' (provenance ⇄ catalog)"

echo "== validate-artifacts: $TRIPLE OK =="
