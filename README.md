# boringssl-kmp

**One canonical, single-pinned [BoringSSL](https://boringssl.googlesource.com/boringssl/) for every
platform Kotlin Multiplatform touches** — Kotlin/Native (Linux + Apple), JVM (Foreign Function & Memory),
and Android (JNI/prefab). Instead of every library (`buffer`, `socket`, `webrtc-dtls`, `quiche`, …)
cross-compiling its own copy, this repo builds **one pinned commit** across the whole matrix in CI and
ships **checksum-pinned prebuilt bundles** you drop into your build.

> It is a **binary factory**, not a klib library. It ships the *ingredients* (headers + static archives +
> a JVM shared library); you keep your own binding `.def`/JNI glue.

**Canonical pin:** `google/boringssl @ 44b3df6f` (BoringSSL API 21) — the exact tree
`boring-sys 4.22.0` vendors for `quiche 0.29.2`. That commit is the single source of truth; the pin
follows quiche going forward.

---

## Which artifact do I want?

| Your target | Add this | You get |
|---|---|---|
| **Desktop / server JVM** (JDK 21+, FFM) | `com.ditchoom.boringssl:boringssl-jvm` (Maven Central) | a ready-to-call `BoringSsl` API — no native setup |
| **Kotlin/Native** — Linux **and Apple** (macOS/iOS/tvOS/watchOS) | the `com.ditchoom.boringssl.provision` Gradle plugin | `libcrypto.a` + `libssl.a` + headers, provisioned into your cinterop |
| **Android** (NDK/JNI) | `com.ditchoom.boringssl:boringssl-android` (Maven Central) | a **prefab** AAR: per-ABI static `.a` + headers your JNI links against |
| Pin all of the above to one version | `com.ditchoom.boringssl:boringssl-bom` | a BOM constraining every coordinate |

Current version: **`0.0.1`**.

---

## Usage

### JVM (Foreign Function & Memory) — the easy path

Requires **JDK 21+** (the shared library ships inside the JAR as a Multi-Release resource and is
`System.load`-ed for the running OS/arch automatically — no manual native install).

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.ditchoom.boringssl:boringssl-jvm:0.0.1")
}
```

```kotlin
import com.ditchoom.boringssl.BoringSsl

val digest  = BoringSsl.sha256("abc".encodeToByteArray())        // 32 bytes
val mac      = BoringSsl.hmacSha256(key, message)                 // HMAC-SHA256
val okm      = BoringSsl.hkdfSha256(length = 42, secret = ikm, salt = salt, info = info)
val keyPair  = BoringSsl.x25519KeyPair()                          // RFC 7748
val shared   = BoringSsl.x25519(keyPair.privateKey, peerPublic)
val random   = BoringSsl.randomBytes(32)
```

Run with `--enable-native-access=ALL-UNNAMED` (the JAR manifest already declares it, so on most JDKs no
flag is needed). Bundled JVM platforms: `linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`.

### Kotlin/Native (Linux **and** Apple) — cinterop via the provision plugin

Apple and Linux consume BoringSSL the **same way**: the provision plugin downloads the per-triple
tarball from GitHub Releases, verifies it against a **baked-in sha256** (no trust-on-first-use), extracts
it to `~/.gradle/caches/ditchoom-boringssl/<version>/<triple>/{include,lib}`, and hands you the path.
You supply your own `.def` (headers + any `static inline` wrappers) — we ship the ingredients.

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform")
    id("com.ditchoom.boringssl.provision") version "0.0.1"
}

kotlin {
    // Every Linux + Apple triple wires up identically:
    listOf(linuxX64(), macosArm64(), iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("boringssl") {
            // downloads + sha256-verifies + extracts on first use; returns {include, lib}
            val bssl = boringssl.boringsslDir(target.targetName)
            defFile("src/nativeInterop/cinterop/boringssl.def")
            includeDirs(bssl.resolve("include"))
            extraOpts("-libraryPath", bssl.resolve("lib").path)
        }
    }
}
```

```properties
# src/nativeInterop/cinterop/boringssl.def
headers = openssl/ssl.h openssl/crypto.h openssl/sha.h
staticLibraries = libssl.a libcrypto.a
# Apple auto-links libSystem; on Linux add: linkerOpts = -lpthread -ldl
```

Supported K/N triples (12): `linuxX64`, `linuxArm64`, `macosX64`, `macosArm64`, `iosArm64`,
`iosSimulatorArm64`, `iosX64`, `tvosArm64`, `tvosSimulatorArm64`, `tvosX64`, `watchosSimulatorArm64`,
`watchosX64`. Call `boringsslDir(triple)` with the Kotlin/Native target name (which equals the triple).

### Android (NDK / JNI) — prefab AAR

```kotlin
// build.gradle.kts (Android library/app, android.prefab enabled)
android {
    buildFeatures { prefab = true }
}
dependencies {
    implementation("com.ditchoom.boringssl:boringssl-android:0.0.1")
}
```

```cmake
# CMakeLists.txt — link your JNI shim against the prefab modules
find_package(boringssl REQUIRED CONFIG)
target_link_libraries(your_jni ssl crypto)   # `ssl` transitively exports `crypto`
```

ABIs: `arm64-v8a` + `x86_64` (minSdk 24). Prefab modules: `crypto` and `ssl` (headers under
`openssl/` come with each). You write the JNI glue; the AAR provides the archives + headers.

### BOM — one version for everything

```kotlin
dependencies {
    implementation(platform("com.ditchoom.boringssl:boringssl-bom:0.0.1"))
    implementation("com.ditchoom.boringssl:boringssl-jvm")     // version comes from the BOM
    implementation("com.ditchoom.boringssl:boringssl-android")
}
```

The BOM's POM records the canonical BoringSSL commit + the quiche ABI anchor it was built against.

---

## Where do the binaries actually live?

Nothing is committed to git (`libs/**` is gitignored). Everything is compiled from the pinned commit in
CI and shipped through two channels (RFC §3 "channel split"):

| Binary | Form | Hosted on |
|---|---|---|
| K/N static archives (`libcrypto.a` + `libssl.a` + headers), per triple | `boringssl-<ver>-<triple>.tar.gz` (+ `.sha256` + provenance) | **GitHub Releases** — the heavy blobs never go to Central |
| JVM shared library (`libboringsslffi.{so,dylib}`) | inside the `boringssl-jvm` MRJAR at `META-INF/native/<os>-<arch>/` | **Maven Central** |
| Android static archives, per ABI | inside the `boringssl-android` prefab AAR at `prefab/modules/{crypto,ssl}/libs/android.<abi>/` | **Maven Central** |

Every tarball carries a `.sha256`; the whole set is listed in `SHA256SUMS`; and those checksums are
**baked into the provision plugin**, so a K/N consumer verifies each download against a pin shipped in
the plugin itself — never trust-on-first-use.

---

## Platform coverage & validation

| Platform | Status | How it's proven |
|---|---|---|
| Linux x64 / arm64 | ✅ runtime-validated | built in a glibc-2.17 (manylinux2014) container for K/N-floor safety; FFM + K/N KATs |
| JVM (x64 + arm64) | ✅ runtime-validated | FFM known-answer tests (SHA-2, HMAC, HKDF, X25519, RNG) on real JDK 21 |
| Android arm64-v8a / x86_64 | ✅ runtime-validated | on-device KATs (x86_64 emulator + arm64 under qemu) + §5 size budget |
| **macOS x64 / arm64** | ✅ runtime-validated | FFM KATs + K/N cinterop link-smoke on macOS runners |
| **iOS device + simulators** | 🔶 compile-faithful | cross-compiled + per-SDK link-smoke (Apple posture is opt-in — D2) |
| **tvOS / watchOS** | 🔶 compile-faithful | cross-compiled + link-smoke; on-device runtime not yet proven (D4) |

**Apple note (D2):** BoringSSL on Apple is opt-in — use it only where mandated (e.g. webrtc-dtls,
quiche). Pure crypto on Apple is usually better served by CryptoKit/CommonCrypto. tvOS/watchOS
cross-compile is proven at the compile+link level but not runtime-validated on-device.

---

## Building from source

Requires **JDK 21**; Apple targets build on macOS only, Android needs the NDK, Linux uses Docker
(manylinux2014).

```bash
./gradlew :boringssl-build:packageBoringSslLinuxX64        # one Linux triple → tarball
./gradlew :boringssl-build:packageBoringSslAppleMacosArm64 # one Apple triple (macOS host)
./gradlew :boringssl-build:buildBoringSslAndroid           # Android per-ABI .a (NDK)
./gradlew :boringssl-jvm:jvm21Test                         # FFM known-answer tests
```

CI cross-compiles the whole matrix per commit and, on a labeled merge to `main`, publishes the small
artifacts to Maven Central and the tarballs to GitHub Releases.

## More

- [`RFC_BORINGSSL_KMP.md`](RFC_BORINGSSL_KMP.md) — the plan of record (matrix, distribution, decisions).
- [`CLAUDE.md`](CLAUDE.md) — repo conventions + standing directives.

Licensed under Apache-2.0. BoringSSL carries its own (ISC/OpenSSL-style) license.
