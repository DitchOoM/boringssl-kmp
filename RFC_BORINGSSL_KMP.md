# RFC: `boringssl-kmp` — one canonical BoringSSL for DitchOoM

**Status:** draft · **Owner:** rbehera · **Last updated:** 2026-07-14

A single repo whose sole job is to build and deliver **one pinned BoringSSL** across every
platform that `buffer` or `socket` supports, so `buffer-crypto`, `socket`, and `webrtc-dtls`
stop each building their own. This RFC is the plan of record; it supersedes the scratch design
output that produced it.

---

## 1. Why

BoringSSL is compiled **three times today, at three different pinned commits**:

| Path | Where | Commit | Platforms it builds BoringSSL for |
|---|---|---|---|
| 1 | `buffer/buffer-crypto` (inline Gradle task) | `63893acb` | linux x64/arm64 only (Apple → CryptoKit/CommonCrypto; JVM/Android → JCA) |
| 2 | base `socket` (`LinuxSockets.def`) | `f1c75347` | linux x64/arm64 only |
| 3 | `socket-quic-quiche` → **quiche** via `boring-sys`/`boring-crate` | quiche's own pin | linux, macOS x64/arm64, iOS (device + 2 sims), Android arm64-v8a/armeabi-v7a/x86_64, + JVM JNI |

So BoringSSL already lands on Linux, macOS, iOS, and Android — but as three uncoordinated
copies. On Apple/Android it is quiche's private boring-crate build; only Linux uses an external
prebuilt. Consolidating **unifies three builds into one canonical, single-pinned-commit source**,
removes the redundant per-target recompiles, kills the duplicate-`libcrypto` SIGSEGV hazard, and
directly satisfies `webrtc-dtls` W4 (which needs BoringSSL FFM/JNI/cinterop backends).

## 2. Architecture: a binary factory, not a klib library

`boringssl-kmp` **cross-compiles one pinned commit across the matrix once per commit in CI** and
ships **checksum-pinned prebuilt bundles**. A thin published Gradle **provision plugin** downloads
and verifies them into consumer builds. **Consumers keep their own binding `.def`/glue** — we ship
the ingredients, not the bindings (see §4).

Rejected alternative: publishing cinterop klibs that embed `staticLibraries`. Its "single copy is
structurally guaranteed by Gradle dedup" claim is **false across the quiche boundary** — dedup only
covers the `boringssl-kmp` subgraph, so a binary linking `socket-quic-quiche` (its own boring-crate
BoringSSL) **plus** a boringssl-kmp klib gets two `libcrypto`s. Single-copy is therefore enforced by
**grep + `nm` + linux-first link ordering**, not asserted structurally.

## 3. Repo shape

`DitchOoM/boringssl-kmp`, group `com.ditchoom.boringssl`. Copied from the `webrtc` template
(convention plugin in `build-logic/`, label-driven release CI, `Versioning.kt`), then re-pointed for
a binary-producing repo.

| Module | Kind | Publishes | Role |
|---|---|---|---|
| `build-logic/` | includeBuild convention | — | From webrtc; **drop** js/wasm + kotlinx-benchmark; add a `boringssl.native-build` convention holding the unified cmake task factory (ports buffer-crypto + socket + quiche cmake args into one place). |
| `:boringssl-build` | plain Gradle (non-KMP) | GitHub Releases | Per-triple cmake tasks + packaging → `boringssl-<ver>-<triple>.tar.gz` (+ `.sha256`, `SHA256SUMS`, `provenance.json`). `libs/**` gitignored. |
| `:boringssl-provision` | Gradle plugin | **Central + Plugin Portal** | `id("com.ditchoom.boringssl.provision")`. Downloads + sha256-verifies tarballs into `~/.gradle/caches/ditchoom-boringssl/<ver>/<triple>/`; exposes `boringsslDir(triple) → {include, lib}`. **The entire native-consumer surface.** |
| `:boringssl-jvm` | FFM producer | **Central** | Multi-release JAR; shared `libboringsslffi.{so,dylib}` under `META-INF/native/<os>-<arch>/`; **jextract-generated FFM bindings** (§4). |
| `:boringssl-android` | AAR producer | **Central** | Prefab AAR: static `.a` + headers per ABI (for consumer JNI shims to link). |
| `:boringssl-bom` | BOM | **Central** | Pins all coordinates + records the canonical commit and its quiche ABI anchor. |
| `:boringssl-testsuite` | validation | — | Per-target link-smoke (SHA256 / AEAD / `SSL_CTX` / `DTLSv1`); wired into `validate-artifacts`. |

**Channel split (decisive):** heavy static tarballs → **GitHub Releases** (unmetered for public
repos, don't count toward repo size, ≤ a few MB each). Only small artifacts (plugin, FFM JAR, AAR,
BOM) → **Maven Central**. **Never** LFS (metered) or Packages (metered) for the blobs. Precedent for
the Central native bundles: `netty-tcnative-boringssl-static` is this exact pattern.

**GitHub-limit note:** the provision plugin fetches by **stable direct asset URL**
(`.../releases/download/<tag>/<asset>`), never the `/releases/latest` API — direct downloads bypass
the 60-req/hr unauthenticated API limit that a shared CI IP would otherwise hit. Checksums are baked
into the plugin (no API round-trip), with a mirror/base-URL override. Actions minutes are free for
public repos (watch only macOS runner concurrency → Apple triples queue, they don't fail).

## 4. FFI division of labor (the part that makes it callable from Kotlin)

Shipping `.a`/`.so` alone is a *dependency provider*, not a Kotlin API. Each world binds to C
differently. The unifying idea: **one curated `boringssl_shim.h`** defines the exported surface
(each needed function/macro as a real symbol) and feeds all three mechanisms.

| World | Binary consumed | Binding mechanism | Shim needed? | Who writes it |
|---|---|---|---|---|
| **K/N** (linux, apple) | static `.a` | cinterop `.def` → **auto-generated** bindings | `boringssl_shim.h` (`static inline` wrappers force symbol materialization for macros) | consumer's `.def` (per-consumer symbol set) |
| **Desktop JVM** | shared `.so`/`.dylib` | **FFM** via **jextract** codegen over `boringssl_shim.h` | no native shim — pure Kotlin/Java downcalls; needs the shared lib at runtime | `boringssl-jvm` generates + ships bindings (API is platform-agnostic → **write-once**) |
| **Android** | static `.a` | **JNI** (no FFM on ART) | **yes** — a small hand-written C shim → `lib*_jni.so` per ABI linking the `.a` | consumer (DTLS/QUIC module) |
| **JS / wasmJs** | — | WebCrypto | — | out of scope |

Notes that bite:
- **jextract skips function-like macros and `static inline`.** BoringSSL is macro-heavy, so those
  must be re-exposed as real functions in `boringssl_shim.h` and jextract run over *that* — the same
  discipline as the K/N `.def` wrappers. Filter with `--include-function`/`--include-struct`.
- **FFM API level:** final in JDK 22; **preview in JDK 21** (which the repos pin). The FFM/jextract
  code lives in a **`jvm21Main`** source set (`JvmTarget.JVM_21`) — socket already established this
  pattern (`src/jvm21Main/kotlin`). Regenerate on JDK bump.
- **JNI is the only hand-written native glue.** Keep its marshalling **zero-copy**
  (`DirectByteBuffer` + `GetDirectBufferAddress`, never `GetByteArrayElements`) to honor standing
  directive #1 (no `ByteArray` copies in hot paths). Build the shim with `-fvisibility=hidden`
  `-Wl,--exclude-libs,ALL` so BoringSSL's unprefixed symbols stay **private** — the collision fix,
  already proven in socket's quiche build.

## 5. Android app-size policy (STANDING RULE)

App size is a first-class constraint on Android. BoringSSL is **not** linkable from the platform
(the system copy is private, ABI-unstable, linker-blocked since Android 7), so anything that needs it
must bundle it — which is why bundling is **opt-in per use case**, never default.

**Rule A — bundle BoringSSL on Android only where JCA cannot serve.**

| Android consumer | BoringSSL bundled? | Rationale |
|---|---|---|
| `buffer-crypto` (hashes, AEAD, curve25519, HKDF) | **No — use JCA/conscrypt** | Fully covered by the platform provider at zero app-size cost. This is already how buffer-crypto works today; the consolidation must **not** regress it onto BoringSSL. |
| `webrtc-dtls` (DTLS 1.2/1.3 + SRTP) | **Yes** | JSSE `SSLEngine` does not expose `SSL_export_keying_material` (the DTLS-SRTP exporter WebRTC requires). |
| `socket-quic` (QUIC/quiche) | **Yes** | quiche is its own stack; not a JCA surface. |

**Rule B — one shared BoringSSL on Android, never one per consumer.** Point quiche at the canonical
BoringSSL (§6) so that a device running both `webrtc-dtls` and `socket-quic` carries **one** copy,
not two. This is both the app-size win and the duplicate-symbol fix.

**Rule C — ship via Android App Bundle** so each user downloads only their device ABI's slice, and
build the bundled copy lean: `-fvisibility=hidden -ffunction-sections -fdata-sections
-Wl,--gc-sections`, linking only symbols the shim references.

**Size budget (tracked, not aspirational):** target **≤ 2.5 MB stripped per ABI** for the
DTLS/QUIC-subset BoringSSL, measured once the shim surface is fixed. Because of App Bundle ABI
splitting, that is the per-device download delta — **once**, shared across DTLS + QUIC — not ×3.
`validate-artifacts` records the measured per-ABI size each release; a regression past budget fails CI.

**Rule D — raise minSdk, drop 32-bit ARM.** The only Android consumers that bundle BoringSSL are the
DTLS/QUIC modules, and their UDP `DatagramChannel` transport seam does not target ancient Android, so
`minSdk 21` is moot for them. Raise it (target level TBD — see D7) and build the Android BoringSSL for
**`arm64-v8a` + `x86_64` only**, dropping `armeabi-v7a` (32-bit ARM — the fiddliest cross-compile,
extra AAR weight, effectively dead hardware for a QUIC/WebRTC datapath). This is orthogonal to the
BoringSSL-version question (it does **not** unlock DTLS 1.3 — that's D1a) but it trims the native ABI
matrix from 3 to 2 and lets the NDK target a newer `ANDROID_PLATFORM`.

## 6. quiche uses the canonical BoringSSL (does not package its own)

The mechanism already runs on **linux**: socket builds quiche `--no-default-features --features
ffi,qlog` (no `boringssl-boring-crate`), which makes quiche emit **no** BoringSSL (undefined symbols),
then whole-archives the external `libcrypto.a`/`libssl.a` at final link (`--exclude-libs,ALL` keeps
them private; unversioned soname patched). Extend the same technique per triple:

- **Android:** build quiche `ffi,qlog` for `aarch64-linux-android` etc., link the canonical `.a`
  (hidden) into the single `libquiche_jni.so`.
- **Apple K/N:** same, resolved via `-force_load` of `libquiche.a` + the canonical `.a`.

quiche 0.29 removed its vendored `deps/boringssl` submodule, so the external path is effectively the
intended way to avoid a redundant build — but there is **no cmake fallback**, so the link plumbing
must be *authored* per triple, not merely toggled.

**Hard constraint:** BoringSSL has no stable ABI, so the canonical commit must be **ABI-compatible
with the quiche release** in use. The canonical pin therefore **follows quiche** (recorded as
`boringsslQuicheAbi` in the catalog + BOM), and the flip is **gated per platform** on a CI quiche
`ffi,qlog` link-smoke + a boring-sys commit-equality check. boring-crate stays as fallback until each
triple is CI-proven.

## 7. Distribution per platform (named shapes)

| Consumer path | Artifact | How consumed |
|---|---|---|
| K/N cinterop (buffer-crypto linux; base socket linux+apple; webrtc-dtls) | `boringssl-<ver>-<triple>.tar.gz` (headers + `libcrypto.a`+`libssl.a`) | `apply(plugin="com.ditchoom.boringssl.provision")`; cinterop `-libraryPath`/`-staticLibrary` + `includeDirs` off `boringsslDir(triple)`. ~10-line swap for today's ~150-line inline task. |
| JVM (FFM) | `com.ditchoom.boringssl:boringssl-jvm` MRJAR (shared lib + jextract bindings) | webrtc-dtls FFM backend loads the extracted lib, calls the generated downcalls |
| Android (JNI) | `com.ditchoom.boringssl:boringssl-android` prefab AAR (static `.a` + headers per ABI) | consumer's JNI shim links at NDK build time (§4/§5) |
| quiche (end-state) | same per-triple archives, via the provision plugin | `ffi,qlog` external link (§6) |

**Triples (12 K/N):** linuxX64, linuxArm64, macosX64, macosArm64, iosArm64, iosSimulatorArm64,
iosX64, tvosArm64, tvosSimulatorArm64, tvosX64, watchosSimulatorArm64, watchosX64. `watchosArm64`
(arm64_32) omitted; Windows/mingw not a target. **tvOS/watchOS BoringSSL cross-compile is unproven
anywhere — gated behind a spike (§10), and trails since no current consumer needs it.**

## 8. CI & standing directives

Keep the webrtc workflow skeleton (`review` → grep-gate → `build-linux` + `build-apple` →
`validate-artifacts`; `merged` label-driven release; `native-deps-freshness` +
`weekly-dependency-update` retargeted to watch the quiche ABI anchor). Add a `build-android` lane.

`build-linux` is a **native-runner matrix**: `linuxX64` on `ubuntu-24.04`, `linuxArm64` on
`ubuntu-24.04-arm` (GitHub's free public ARM64 runners). Each triple builds its native-arch
manylinux2014 image with the **legacy builder** — so the canonical CI path needs **no qemu and no
buildx** (native arm64 is also far faster than emulation). qemu+buildx is only the local-dev fallback
for cross-building arm64 on an x64 box.

`validate-artifacts` becomes **binary-shaped** (the socket #188 lesson for binaries): per triple, from
first release — tarball extracts; required headers present; `nm`/`llvm-nm` shows required symbols
**unprefixed** and **exactly one `libcrypto`**; a throwaway wrapper-`.def` compile+link per K/N triple;
FFM `SymbolLookup` resolves on JVM; AAR prefab metadata well-formed; a quiche `ffi,qlog` link-smoke
against the linux tarball; **per-ABI size recorded vs the §5 budget**; and the **glibc-floor gate** ↓.

### 8a. The Linux glibc floor (K/N portability — evidence-backed)

Kotlin/Native links linux consumer binaries against a **bundled, deliberately old glibc sysroot**:
**glibc 2.19 (linuxX64) / 2.25 (linuxArm64)** (`~/.konan/dependencies/*-glibc-2.19-*`). Any symbol our
archives reference that the floor libc lacks fails the *consumer's* K/N link. Two real offenders were
found building on a modern host (glibc 2.39) — this is the "works on CI, breaks for a consumer" trap:

| Symbol | Modern host (≥2.38/2.33) | Floor (2.19) | Source |
|---|---|---|---|
| `__isoc23_strtoull` (+ strtol/strtoul/strtoll) | present (C23 redirect) | absent | glibc 2.38 `<stdlib.h>` rewrite |
| `stat` | `stat@GLIBC_2.33` | absent (only legacy `__xstat`) | glibc 2.33 stat-symbol unification; BoringSSL `by_dir.c` |

**Canonical fix — build on an old glibc, don't shim.** Verified: building the same `stat()` at `-O2`
on glibc **2.31** emits `__xstat` (in the floor); on glibc 2.39 it emits `stat` (not in the floor). So
the **release build runs in an old-glibc container** — `manylinux2014` (glibc **2.17**) is the target;
this eliminates *both* offenders with zero shims. The `__isoc23_*` `ar`-merge shim stays as
belt-and-suspenders for dev-box builds but is **not** the guarantee. The JVM FFM `.so` wants the same
manylinux base (it's `dlopen`ed against the host glibc). Android is exempt — it's **bionic via the NDK**
(floor = `minSdk`/NDK API level, D7=24), no glibc, no `__isoc23`/`stat` issue.

**The gate (implemented in `:boringssl-build:checkGlibcFloor<Triple>`, wired ahead of packaging):** an
offender is precisely a symbol the *build* glibc provides but the *floor* glibc does not
(`external_undefined ∩ host_libc − floor_libc`) — not a hand-maintained denylist, so it catches any
future version-gated symbol while ignoring libgcc/compiler builtins. Fails the build on any offender.
Alpine/musl is **out of scope**: K/N has no musl target, so `linuxX64/Arm64` binaries are glibc-only
regardless of BoringSSL (a consumer on Alpine needs `gcompat`).

**Directives kept from webrtc's CLAUDE.md:** "PR states which lanes were runtime-validated vs
compile-faithful" (Apple/tvOS/watchOS only real on the mac runner). **Dropped** (no runtime data
path here): no-`ByteArray`, injected time/entropy/dispatch seams, sealed errors, pooled buffers,
deterministic-fixture-per-bugfix — scoped only to the provision plugin's tiny Kotlin surface.
**New binary-repo directives (CI-grep-enforced):** no committed binaries; exactly ONE `boringssl`
commit literal; no `BORINGSSL_PREFIX` unless catalog-flagged (the one-unprefixed-copy invariant);
every artifact carries sha256 + provenance; release notes state the canonical commit + quiche anchor.

## 9. Separate build-system repo — split decision

- **Now:** publish the narrow `boringssl-provision` plugin from `boringssl-kmp` day one (no
  copy-paste option for a download+verify plugin). Keep `build-logic/` as a copied includeBuild.
- **Later, not never:** extract a `DitchOoM/ditchoom-build-system` repo (`com.ditchoom.build.*`
  convention plugin + shared catalog) once 4th-copy drift is concrete pain. The webrtc convention is
  **not cleanly extractable as-is** (hardwired benchmark source sets, KSP-for-buffer-codec, a
  webrtc-shaped matrix) — extraction requires parameterizing those first.

## 10. Migration (ordered; reconciles the commits; breaks no release)

**Canonical pin = `44b3df6f03d85c901767250329c571db405122d5`** — RESOLVED, see §12/D1. This is
google/boringssl at **`BORINGSSL_API_VERSION 21`** (committed 2023-05-08), the exact tree
`boring`/`boring-sys 4.22.0` vendors, which is quiche 0.29.2's TLS backend. **Both existing pins were
wrong for the quiche ABI:** socket's `f1c75347` is API **16**, buffer's `63893acb` is API **42** —
socket's linux external path works only because the narrow C-FFI subset it exercises happens to be
stable across 16→21, luck that won't necessarily hold on Apple/Android. **Retire both.** buffer-crypto
moves *down* to API 21 (verify at step 4 its surface — `EVP_aead_*`, `EVP_AEAD_CTX_seal/open`,
`ED25519_*`, `X25519_*`, `EC_POINT_oct2point`, `EC_POINT_mul`, `BN_bn2bin_padded`, `HKDF`,
`EVP_DigestSign/Verify` — all long predate API 21, low risk). Going forward the canonical commit
**follows the quiche release** (resolve via cloudflare/boring's `boring-sys/deps/boringssl` gitlink at
the crate's published commit; record in `COMPAT.md` + BOM).

Two caveats carried by this pin:
- **DTLS 1.2 only.** API-21 BoringSSL has `TLS1_3_VERSION` but **no `DTLS1_3_VERSION`** — webrtc-dtls
  gets DTLS **1.2** at this pin (the WebRTC baseline; DTLS 1.3 is gated on a future quiche/boring bump).
  This is the price of the single-copy invariant: the newest consumer is bounded by the oldest
  constraint (quiche). See §12/D1a.
- **boring-sys patches.** boring-sys applies three patches on top (`boring-pq`, `rpk`,
  `underscore-wildcards`) — feature additions (post-quantum, raw public keys, wildcard SAN), which
  should add symbols without changing the ABI quiche's Rust bindings depend on. Plain google/boringssl
  @ 44b3df6f is therefore expected to satisfy the external `ffi,qlog` link; **the `validate-artifacts`
  quiche link-smoke must prove it**, and if it fails, apply the same three patches in `boringssl-build`.

1. Stand up `boringssl-kmp` from the webrtc template; drop js/wasm/benchmark; add `boringssl.native-build`. Set `boringssl = 44b3df6f03d85c901767250329c571db405122d5`, `boringsslApiVersion = 21`, `boringsslQuicheAbi = "quiche 0.29.2 / boring-sys 4.22.0"`.
2. **Port the Linux path first** (only proven-from-code path): build **both** `libssl.a`+`libcrypto.a`, arm64 via `aarch64-linux-gnu-gcc` `-mno-outline-atomics`, `__isoc23` compat shim `ar`-merged (belt-and-suspenders). **DONE + verified** (commits `3e741af`/this step) — both linux triples build, cross-compile, package reproducibly, and the `checkGlibcFloor` gate is wired ahead of packaging. **Open item surfaced by the gate:** host-glibc-2.39 builds reference `stat`/`__isoc23` (above the K/N floor), so the **canonical build must run in an old-glibc container (`manylinux2014`, glibc 2.17)** — see §8a; wiring that container into `:boringssl-build` + CI is the immediate remaining task before consumer-link (step 4). Then author `boringssl-jvm` linux FFM `.so` + jextract bindings over `boringssl_shim.h`. Green `validate-artifacts`.
3. Ship **0.0.1**: linux tarballs + `boringssl-jvm` (linux slots) + BOM; bake checksums into the plugin; wire the `merged.yaml` Release short-circuit (skip compile if a Release for the commit exists).
4. Migrate **buffer-crypto (linux only)**: delete inline task, apply the provision plugin, keep `boringsslcrypto.def` **verbatim**. Apple stays CryptoKit/CommonCrypto **untouched**. This is the one real commit change (`63893acb → f1c75347`) — run the wider-surface symbol tests. Release buffer.
5. Migrate **base `:socket:` (linux)**: swap `LinuxSockets.def` to the provisioned archives (keep `-luring/-lpthread/-ldl`). `nm`-assert one `libcrypto`.
6. **⚠️ Fix the quiche coupling (do NOT skip).** `socket-quic-quiche` **hardcodes** `rootProject/libs/boringssl/linux-$arch/lib/libssl.a` for its `usesExternalBssl` gate and `dependsOn` the base-socket build task. Removing the base task empties that path and silently flips quiche to boring-crate. So steps 5/6 must **materialize the provisioned tarball into that exact path** (or repoint quiche's `-L`, header dirs, and task-deps). After this, buffer + socket + quiche share **one** `libcrypto` on linux. Release socket.
7. Add **Apple + Android** lanes (`macos-14`, NDK r27): per-SDK cmake incl. the arm64 iOS-simulator asm-SDK-tag fix. **Spike tvOS/watchOS before promising those five triples.** Publish the feature-complete matrix.
8. **quiche external flip (GATED, one platform at a time)** — §6.
9. **webrtc-dtls W4** consumes the provision plugin (cinterop, linux+apple), `boringssl-android` AAR (JNI), `boringssl-jvm` MRJAR (FFM), pinned via the BOM. Satisfies the `build-linux.yaml` W4 marker **by dependency, not build steps.**

## 11. Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Duplicate unprefixed BoringSSL SIGSEGV during migration | High | Linux-first collapses the three linux copies before Apple work; `nm` single-copy guard; Apple stays on quiche's copy until §6; Android shims hidden-static |
| quiche coupling missed (hardcoded root path + task-dep) | High | Step 6 explicit — materialize into the exact path or repoint |
| tvOS/watchOS cross-compile unproven | Med-High | Time-boxed spike (step 7); ship later if a triple resists — blocks no linux/JVM/iOS consumer |
| boring-sys ABI drift on a quiche bump | Med | `native-deps-freshness` compares pinned vs expected commit daily; `COMPAT.md` + BOM; link-smoke gates the flip |
| JVM/Android two-copy (FFM lib + `quiche_jni` in one process) | Med | End-state: both share the provisioned archive. Interim: hidden symbols / require quiche's external path. **Decision D3.** |
| Prebuilt-binary trust / Releases availability | Med | sha256 baked into plugin (no TOFU) + `SHA256SUMS` + provenance; mirror/base-URL override |

## 12. Decisions (all resolved 2026-07-14)

1. **D1 — quiche ABI anchor (gates everything): ✅ RESOLVED.** socket pins **quiche 0.29.2** →
   `boring`/`boring-sys` **4.22.0** → google/boringssl **`44b3df6f03d85c901767250329c571db405122d5`**
   (`BORINGSSL_API_VERSION 21`, 2023-05-08) + 3 cloudflare feature patches. **Canonical pin =
   `44b3df6f…`.** Neither old pin matched (f1c75347=API16, 63893acb=API42). Commit follows quiche going forward.
1a. **D1a — DTLS version / can we update quiche's BoringSSL? (follows from D1).** You cannot swap a
   newer BoringSSL under `boring-sys 4.22.0` — its Rust bindings are pinned to API 21 (the
   `BORING_BSSL_PATH` override only substitutes a *same-API* build). Updating BoringSSL means moving
   `boring-sys` forward, and the ladder is **gated by quiche**:
   - `boring 4.x` is already maxed at **4.22.0 / API 21** (`44b3df6f`, 2023-05-08, DTLS 1.2 only) —
     socket is already on the newest 4.x.
   - `boring 5.1.0` vendors BoringSSL **`91a66a59…`** (2025-11-04, **API 37, has `DTLS1_3_VERSION`**).
   - **But quiche upstream — incl. latest `0.29.3` — still pins `boring = "4.3"` (caps at 4.x).** quiche
     has not adopted boring 5.x, so riding it means **forking/patching quiche** onto boring 5.x (real
     port: quiche's own code + `build.rs` must compile against boring 5.x's API).

   Three paths:
   - **(1) Stay API 21 / DTLS 1.2 (recommended default):** one shared copy, single-copy invariant
     intact; DTLS 1.3 arrives free when quiche adopts boring 5.x (canonical pin follows).
   - **(2) Decouple:** webrtc-dtls + buffer-crypto pin the newer `91a66a59…` (API 37, DTLS 1.3) while
     socket-quic stays API 21 for quiche. Gets DTLS 1.3 now, but **breaks single-copy** — any binary/
     process loading both socket-quic and webrtc-dtls then has two BoringSSLs → requires
     `BORINGSSL_PREFIX` on one (escalates D3) or a guarantee they never co-load.
   - **(3) Fork quiche onto boring 5.x:** unifies everything at API 37 + DTLS 1.3 *and* keeps
     single-copy, at the cost of owning a quiche fork until upstream moves.

   **✅ LOCKED: option (1).** DTLS 1.2 baseline — the WebRTC field floor; media protection is SRTP
   (unaffected by DTLS version), the 1-RTT handshake win is dominated by ICE setup, and browser peers
   negotiate down to 1.2 anyway. No WebRTC feature is gated on 1.3. DTLS 1.3 arrives free when quiche
   adopts boring 5.x and the canonical pin follows. Do not fork quiche or break single-copy for it.
2. **D2 — Apple posture: ✅ COEXIST.** BoringSSL is opt-in on Apple only where mandatory (webrtc-dtls,
   quiche-external end-state); buffer-crypto keeps CryptoKit/CommonCrypto. Avoids shipping a multi-MB
   `libcrypto` into every iOS app.
3. **D3 — prefix policy: ✅ UNPREFIXED + single-copy forever.** Matches every current `.def`. Add
   `BORINGSSL_PREFIX` only if the FFM+`quiche_jni` same-process case is ever proven to collide (the
   `validate-artifacts` single-copy `nm` guard is the tripwire). Given D1a=(1), one shared copy holds.
4. **D4 — tvOS/watchOS: ✅ TRAIL.** No current consumer builds BoringSSL there and the cross-compile is
   unproven; gated behind the step-7 spike, shipped later if a triple resists. Blocks nothing.
5. **D5 — build-system repo: ✅ DEFER.** Accept a 4th trimmed `build-logic` copy now; ship the
   `boringssl-provision` plugin shared from day one; extract `ditchoom-build-system` later when drift bites.
6. **D6 — Windows/mingw: ✅ NON-TARGET.** The stubbed `quiche_jni.dll` reference stays inert; revisit
   only if a consumer needs it.
7. **D7 — Android minSdk: ✅ RAISE to 24; build Android BoringSSL for `arm64-v8a` + `x86_64` only**
   (drop `armeabi-v7a`, §5 Rule D). 24 is a modern floor covering the DTLS/QUIC datagram-seam devices;
   trivially bumpable, and the ecosystem-wide buffer/socket/webrtc minSdk raise is a separate coordinated
   change. Independent of DTLS 1.3.
