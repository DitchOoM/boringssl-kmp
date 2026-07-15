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

## Status: Linux + Android archives REAL; Apple pending (RFC §10)

The **Linux** path is fully implemented: both `linuxX64` and `linuxArm64` build **inside the
manylinux2014 (glibc 2.17) container** so every archive is Kotlin/Native-floor-safe, and package
reproducibly.

The **Android** static-archive path is implemented too: `buildBoringSslAndroid` cross-compiles
`arm64-v8a` + `x86_64` (only — armeabi-v7a dropped per §5 Rule D / D7) with the **NDK** toolchain
against `ANDROID_PLATFORM=android-24` (matches the convention plugin's `minSdk`), and
`checkBoringSslAndroid` runs an NDK **whole-archive link-smoke** against Bionic-24 (the Android analog
of `checkGlibcFloor`), the D3 plain-`SHA256_Init` check, **and** the §5 ≤2.5 MiB/ABI budget gate
(`checkAndroidSize<Abi>` — a `--gc-sections`+strip link of a representative DTLS+crypto surface; ~1.1
MB/ABI at the pin). No manylinux container, no `__isoc23` shim, no `.so` — Android consumers use JNI +
a prefab AAR of the static `.a`. **`:boringssl-android` packages these archives into the prefab AAR**
(`crypto`/`ssl` prefab modules, per-ABI `abi.json`) and publishes it; it depends on
`checkBoringSslAndroid` so an over-budget or non-linking archive can never be shipped.

Apple (per-SDK cmake incl. the arm64 iOS-simulator asm-SDK-tag fix) arrives later; tvOS/watchOS after
the step-7 spike proves the cross-compile.

### Android tasks

| Task | Does |
| --- | --- |
| `buildBoringSslAndroid<Abi>` / `buildBoringSslAndroid` | NDK cross-compile the per-ABI static `libssl.a`/`libcrypto.a` + headers into `libs/boringssl/android/<abi>/` |
| `linkSmokeAndroid<Abi>` / `checkBoringSslAndroid` | whole-archive link against Bionic-24 (`-Wl,--no-undefined`) + D3 plain-symbol check + §5 budget gate |
| `checkAndroidSize<Abi>` | `--gc-sections`+strip link of a representative DTLS+crypto surface; fails past 2.5 MiB/ABI |
| `buildAndroidKat<Abi>` / `buildAndroidKat` | NDK-build the on-device KAT executable (run on an emulator in CI — the runtime parity check vs `:boringssl-jvm:jvm21Test`) |

**Android verification parity with Linux.** Linux runs the crypto at runtime per-arch (`jvm21Test` on
each native runner); Android matches with a **runtime KAT on both ABIs** (`build-android.yaml` →
`android_kat.c`, same FIPS/RFC vectors as the JVM KATs): **x86_64** on a KVM-accelerated Android
emulator (full Bionic dynamic runtime), and **arm64-v8a** as a static binary under `qemu-aarch64` on the
x86_64 runner (executes the aarch64 crypto incl. arm64 asm — no arm64 runner needed). Plus
**`validate-aar.sh`** (the Android analog of `validate-artifacts.sh`: prefab schema check + a consumer
NDK-link against the shipped AAR's extracted payload for both ABIs). A native arm64 *emulator* lane
(full Bionic-on-arm64) remains a possible future upgrade.

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
