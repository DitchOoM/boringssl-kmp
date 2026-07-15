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

## Status: Linux REAL; Apple/Android pending (RFC §10)

The **Linux** path is fully implemented: both `linuxX64` and `linuxArm64` build **inside the
manylinux2014 (glibc 2.17) container** so every archive is Kotlin/Native-floor-safe, and package
reproducibly. Apple (per-SDK cmake incl. the arm64 iOS-simulator asm-SDK-tag fix) and Android (NDK
r27, `arm64-v8a` + `x86_64` only per §5 Rule D / D7) arrive in later steps; tvOS/watchOS after the
step-7 spike proves the cross-compile.

### Two-stage build per triple (the `.a` / `.so` split)

Each triple builds in two separately-cached stages, so growing the FFI shim surface does **not**
recompile BoringSSL:

| Stage | Task | Produces | Recipe / marker keyed on |
| --- | --- | --- | --- |
| 1 — archives (expensive) | `buildBoringSslArchives<Triple>` | `libssl.a`, `libcrypto.a`, headers | commit + Dockerfile (`imageHash`) + `build-archives.sh` |
| 2 — FFI link (cheap) | `linkBoringSslFfi<Triple>` | `libboringsslffi.so` | commit + Dockerfile + shim (`boringssl_shim.*` + `boringssl_ffi_exports.map`) + `link-ffi.sh` |

`buildBoringSsl<Triple>` is the umbrella that runs both. A shim-only edit re-runs stage 2 alone (a
~seconds re-link); a new BoringSSL commit / toolchain / archive-recipe change re-runs stage 1 (and, via
the ffi marker, forces the relink too). `checkGlibcFloor<Triple>` gates the archives; `packageBoringSsl<Triple>`
tars the `.a` + headers (lean K/N bundle; the `.so` ships in the boringssl-jvm MRJAR) with sidecar
checksums + provenance. The corresponding in-container recipes live in `docker/build-archives.sh` and
`docker/link-ffi.sh`.
