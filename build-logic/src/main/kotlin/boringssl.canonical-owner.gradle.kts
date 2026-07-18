// ─────────────────────────────────────────────────────────────────────────────────────────────────
// boringssl.canonical-owner — the MINIMAL convention for :boringssl-canonical, the bindings-free K/N
// owner klib. It is deliberately NOT `boringssl.multiplatform-library`: that convention registers
// jvm() + android() (+ Apple), which an owner must NOT have (Apple has its own self-contained BoringSSL
// via quiche; a jvm/android owner klib is meaningless). This convention's ONLY job is to APPLY the
// Kotlin Multiplatform + publishing plugins from build-logic's classloader — the SAME one every other
// KMP module (`boringssl.multiplatform-library`) loads them from.
//
// WHY it must live here and not as `plugins { alias(libs.plugins.kotlin.multiplatform) }` in the
// module: Kotlin/Native registers a shared `KotlinNativeBundleBuildService`. If :boringssl-canonical
// loaded KGP from the plugin portal (its own script classloader) while :boringssl-jvm/:boringssl-
// testsuite load it from build-logic, the two KGP classes would try to register the SAME build service
// under different classloaders and Gradle fails configuration. Applying KGP here routes the owner
// through build-logic's single KGP, so all KMP modules share one classloader and one build service.
//
// EVERYTHING module-specific (linux-only target registration, the archive-embedding cinterop, the POM,
// version derivation) is hand-rolled in boringssl-canonical/build.gradle.kts — this file only unifies
// the plugin classloaders. Versions are pinned by build-logic's own dependencies (see build-logic/
// build.gradle.kts), so the ids carry no version here.
// ─────────────────────────────────────────────────────────────────────────────────────────────────
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.vanniktech.maven.publish")
    id("signing")
}
