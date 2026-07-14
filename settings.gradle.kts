pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

// The `boringssl.*` convention plugins (KMP targets, publishing, versioning) live in build-logic and
// are shared with the main build here — one place for all module build logic, no copy-paste.
// (RFC §5/D5: accept a trimmed build-logic copy now; extract ditchoom-build-system later.)
includeBuild("build-logic")

rootProject.name = "boringssl-kmp"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version ("4.4.2")
}
develocity {
    buildScan {
        uploadInBackground.set(System.getenv("CI") != null)
        termsOfUseUrl.set("https://gradle.com/help/legal-terms-of-use")
        termsOfUseAgree.set("yes")
    }
}

// ── Module tree (RFC §3) — one canonical BoringSSL, produced once per commit, shipped as bundles ──
include(":boringssl-build")       // plain Gradle: per-triple cmake + packaging → GitHub Releases (not Central)
include(":boringssl-provision")   // Gradle plugin: download + sha256-verify tarballs → Central + Plugin Portal
include(":boringssl-jvm")         // FFM producer: MRJAR (shared lib + jextract bindings) → Central
include(":boringssl-android")     // AAR producer: prefab (static .a + headers per ABI) → Central
include(":boringssl-bom")         // BOM: pins coordinates + records canonical commit / quiche anchor → Central
include(":boringssl-testsuite")   // per-target link-smoke validation; wired into validate-artifacts
