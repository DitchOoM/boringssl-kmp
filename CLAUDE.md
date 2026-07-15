# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## Project Overview

`com.ditchoom.boringssl:boringssl-kmp` — a **binary factory** that builds and delivers **one canonical,
single-pinned BoringSSL** across every platform `buffer`/`socket`/`webrtc-dtls` support, so those
consumers stop each building their own. It is **not** a klib library: it cross-compiles one pinned
commit across the matrix once per commit in CI, ships **checksum-pinned prebuilt bundles**, and a thin
published Gradle **provision plugin** downloads + verifies them into consumer builds. Consumers keep
their own binding `.def`/glue — we ship the ingredients, not the bindings.

**Read first, fully:**
1. `RFC_BORINGSSL_KMP.md` — the plan of record (the *what* and *why*). §3 (repo shape), §5 (Android
   policy), §7 (triples/distribution), §8 (CI & directives), §12 (locked decisions) are binding.

Current state: **migration step 1** (RFC §10) — the repo structure stands up from the `webrtc`
template, re-pointed for a binary-producing repo, and **configures cleanly** (`./gradlew projects`).
The real BoringSSL cmake cross-compile is **step 2** and is deliberately **stubbed** (see below).

**Canonical pin:** the `boringssl` version in `gradle/libs.versions.toml` (BoringSSL API 21, the tree
`boring-sys 4.22.0` vendors for quiche 0.29.2). That catalog entry is the **single 40-hex commit
literal in the whole tree** — everything else reads it from the catalog. The pin **follows quiche**
going forward.

## Standing directives (binary-repo — RFC §8, CI-grep-enforced)

These are non-negotiable and enforced by the CI grep-gate (`review.yaml` → grep step) + the adversarial
review gate:

1. **No committed binaries.** No `.a`/`.so`/`.dylib`/tarball is ever committed; `libs/**` is gitignored
   and built in CI. Bundles ship via **GitHub Releases** (not LFS, not Packages, not Central).
2. **Exactly ONE `boringssl` commit literal in the tree** — the catalog entry
   `boringssl = 44b3df6f…`. Every task/BOM/plugin reads the commit from the catalog; no second copy.
3. **No `BORINGSSL_PREFIX`** unless a catalog flag opts in (the one-unprefixed-copy invariant, D3).
   Single unprefixed copy forever; the `validate-artifacts` `nm` single-copy guard is the tripwire.
4. **Every artifact carries a sha256 + provenance.** Checksums are baked into the provision plugin
   (no TOFU); `.sha256` + `SHA256SUMS` + `provenance.json` accompany each tarball.
5. **Release notes state the canonical commit + quiche ABI anchor**
   (`boringsslQuicheAbi = "quiche 0.29.2 / boring-sys 4.22.0"`).
6. **The PR description states which platform lanes were runtime-validated vs compile-faithful**
   (Apple/tvOS/watchOS are only real on the macOS runner; kept from webrtc's CLAUDE.md).

Directives DROPPED from webrtc (no runtime data path lives here): the no-`ByteArray`,
injected-time/entropy/dispatch seams, sealed-error, pooled-buffer, and deterministic-fixture-per-bugfix
rules — those apply only to the provision plugin's tiny Kotlin surface, not the binary pipeline.

## Build commands

```bash
./gradlew projects              # list the module tree (the step-1 configuration smoke test)
./gradlew build                 # build all modules, all host-available targets
./gradlew apiCheck              # binary-compatibility validation (JVM dump) against checked-in .api files
./gradlew apiDump               # regenerate .api files after an intentional public-API change
./gradlew ktlintCheck           # lint  (ktlintFormat to auto-fix)
./gradlew detektAll             # multiplatform static analysis (non-blocking; sees Native actuals)
./gradlew :boringssl-build:buildBoringSslLinuxX64   # STUB today — real cmake lands in step 2
```

Requires **JDK 21** (enforced via toolchain). Apple targets build on macOS only.

## Module map (`RFC_BORINGSSL_KMP.md` §3)

```
boringssl-build       plain Gradle: per-triple cmake + packaging → GitHub Releases (libs/** gitignored) [not Central]
boringssl-provision   Gradle plugin: download + sha256-verify tarballs; boringsslDir(triple)  → Central + Plugin Portal
boringssl-jvm         FFM producer: MRJAR (shared lib + jextract bindings)                     → Central
boringssl-android     AAR producer: prefab (static .a + headers per ABI: arm64-v8a, x86_64)    → Central
boringssl-bom         BOM: pins coordinates + records canonical commit / quiche anchor          → Central
boringssl-testsuite   per-target link-smoke validation (apiCheck-excluded)                      [not published]
```

## Build logic — the convention plugin (no copy-paste)

KMP-shaped modules (`-jvm`, `-testsuite`) apply **one** convention plugin,
`build-logic/src/main/kotlin/boringssl.multiplatform-library.gradle.kts`. It owns the K/N target
matrix (RFC §7, 12 triples), the JDK-21 toolchain, Android (minSdk 24 per D7), jvm, ktlint, dokka,
kover, binary-compatibility validation (JVM dump only), Maven Central publishing, signing, and version
derivation. It is the webrtc convention **trimmed**: js/wasm, kotlinx-benchmark, allopen, and KSP are
dropped. `:boringssl-build` (plain Gradle), `:boringssl-provision` (Gradle plugin), `:boringssl-bom`
(java-platform), and `:boringssl-android` (hand-rolled `maven-publish` prefab-AAR producer — a prefab
AAR carries no Kotlin, and AGP's KMP-library DSL has no prefab-publishing surface) deliberately do NOT
apply it.

A module's own `build.gradle.kts` carries only its dependencies/specifics; per-module POM prose lives
in `<module>/gradle.properties` (`POM_NAME`, `POM_DESCRIPTION`); shared POM/developer/license fields
are in the root `gradle.properties`. Plugin versions are declared once in `gradle/libs.versions.toml`.

## What is STUBBED (step 1) → filled in step 2+ (RFC §10)

- **`:boringssl-build`** cmake cross-compile — `buildBoringSslLinux{X64,Arm64}` only log intent today.
  Step 2 authors the unified `boringssl.native-build` task factory (ports buffer-crypto + socket +
  quiche cmake args), Linux first.
- **`:boringssl-provision`** download/verify/extract — `boringsslDir(triple)` returns a cache path but
  fetches nothing. Step 2/3 adds the direct-asset-URL fetch, baked-in checksum verify, and extraction.
- **`:boringssl-jvm`** FFM `.so` + jextract bindings (`jvm21Main`), **`:boringssl-android`** prefab
  packaging, and **`:boringssl-testsuite`** link-smoke tests all arrive with the first Linux bundle.

## CI/CD (RFC §8 — placeholders in `.github/workflows/`)

- **PR** (`review.yaml`): grep-gate (binary-repo directives) → `build-linux` + `build-apple` +
  `build-android` → `validate-artifacts` (binary-shaped: tarball extracts, headers present, `nm` shows
  unprefixed symbols + exactly one `libcrypto`, wrapper-`.def` compile+link per triple, FFM
  `SymbolLookup`, AAR prefab metadata, quiche `ffi,qlog` link-smoke, per-ABI size vs the §5 budget).
- **Release** (`merged.yaml`): label-driven version bump → build → validate → publish (small artifacts
  to Central + Plugin Portal; tarballs to GitHub Releases) → tag + release. Both are **stubs** today.
