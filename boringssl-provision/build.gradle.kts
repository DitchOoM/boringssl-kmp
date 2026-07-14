// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-provision — the published Gradle PLUGIN (RFC §3). `id("com.ditchoom.boringssl.provision")`.
//
// Downloads + sha256-verifies the per-triple tarballs (built by :boringssl-build, hosted on GitHub
// Releases) into `~/.gradle/caches/ditchoom-boringssl/<ver>/<triple>/` and exposes
// `boringsslDir(triple) → {include, lib}`. This is the ENTIRE native-consumer surface — a ~10-line
// swap for today's ~150-line inline cinterop task (RFC §7).
//
// Published to **Maven Central + the Gradle Plugin Portal** (RFC §3). Publishing config is stubbed /
// commented below (migration step 3 wires it).
//
// ⚠️ STUB — migration step 1. The real download + verify + extract logic is step-2/3 work (RFC §10):
// stable direct asset URL fetch, baked-in checksums (no TOFU), mirror/base-URL override.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

plugins {
    `kotlin-dsl`
    // `java-gradle-plugin` is applied transitively by `kotlin-dsl`; the plugin descriptor is declared
    // in the `gradlePlugin { }` block below.
}

group = "com.ditchoom.boringssl"
version = "0.0.1-SNAPSHOT" // step 3 wires this to the shared version-derivation (Versioning.kt).

repositories {
    mavenCentral()
    gradlePluginPortal()
}

gradlePlugin {
    plugins {
        create("boringsslProvision") {
            id = "com.ditchoom.boringssl.provision"
            implementationClass = "com.ditchoom.boringssl.provision.BoringSslProvisionPlugin"
            displayName = "DitchOoM BoringSSL provision"
            description = "Downloads + sha256-verifies canonical BoringSSL bundles into the Gradle cache."
        }
    }
}

// ── Publishing to Central + Plugin Portal (STUB — migration step 3) ──
// The Plugin-Portal publish uses `com.gradle.plugin-publish`; the Central publish reuses the
// vanniktech coordinates. Left commented so step-1 configures without the extra plugin resolution.
//
// mavenPublishing {
//     coordinates("com.ditchoom.boringssl", "boringssl-provision", version.toString())
//     // pom { ... } — same shared fields as the convention modules.
// }
