# `:boringssl-build`

Plain (non-KMP) Gradle project that cross-compiles the **one canonical BoringSSL** (pinned in
`gradle/libs.versions.toml` as `boringssl`) per triple and packages the result as
`boringssl-<ver>-<triple>.tar.gz` alongside `.sha256`, `SHA256SUMS`, and `provenance.json`.

## Where the binaries go

- **`libs/**` is gitignored** — no built binaries are ever committed (RFC §8 standing directive:
  *no committed binaries*). They are produced fresh in CI.
- The tarballs are published to **GitHub Releases**, not Maven Central (RFC §3 channel split: heavy
  static blobs are unmetered on Releases for public repos; only the small plugin / FFM JAR / AAR / BOM
  go to Central). Never LFS, never GitHub Packages for the blobs.
- Every artifact carries a `sha256` + `provenance.json`; the provision plugin bakes the checksums in
  (no TOFU) and fetches by stable direct asset URL.

## Status: STUB (migration step 1)

The cmake cross-compile is **not implemented here yet**. `buildBoringSslLinuxX64` /
`buildBoringSslLinuxArm64` only log their intent. **Step 2** (RFC §10) fills in the real
`boringssl.native-build` task factory:

- Linux first (the only proven-from-code path): build **both** `libssl.a` + `libcrypto.a`, glibc-floor
  flags (`-U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -fno-stack-protector`, the `__isoc23_strtoull` compat
  TU `ar`-merged), arm64 via `aarch64-linux-gnu-gcc -mno-outline-atomics`.
- Then Apple (per-SDK cmake incl. the arm64 iOS-simulator asm-SDK-tag fix) and Android
  (NDK r27, `arm64-v8a` + `x86_64` only per §5 Rule D / D7).
- tvOS/watchOS only after the step-7 spike proves the cross-compile.
