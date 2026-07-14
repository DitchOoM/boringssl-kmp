@file:OptIn(kotlinx.validation.ExperimentalBCVApi::class)
@file:Suppress("DEPRECATION") // x64 Apple tiers warn as deprecated but stay for consumer compatibility (matches buffer/socket)

import com.ditchoom.boringssl.gradle.computeNextVersion
import org.gradle.api.publish.PublishingExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The convention a KMP-shaped boringssl module applies:  plugins { id("boringssl.multiplatform-library") }
//
// It owns everything structural — the K/N target matrix (RFC §7, 12 triples), the JDK-21 toolchain,
// Android (minSdk 24 per D7), jvm, ktlint, dokka, kover, binary-compatibility validation (JVM dump
// only), Maven Central publishing, signing, and version derivation — so a module's own
// build.gradle.kts carries ONLY its dependencies. Structural facts are derived from the module name
// (artifactId, Android namespace); prose (POM_NAME / POM_DESCRIPTION) comes from the module's
// gradle.properties. No copy-pasted publishing blocks.
//
// Dropped vs the webrtc template (RFC §3, this is a binary factory not a codec library): js(), wasmJs(),
// kotlinx-benchmark, allopen, KSP/buffer-codec — and the source-set/benchmark/ktlint-benchmark wiring
// that went with them. The real BoringSSL cmake cross-compile is NOT here — it is authored in
// :boringssl-build as `boringssl.native-build` in migration step 2 (RFC §10).
// ─────────────────────────────────────────────────────────────────────────────────────────────────

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // AGP 9's Kotlin-Multiplatform-native Android plugin: Android is a normal KMP target configured as
    // `kotlin { android { } }`, and it composes cleanly with the KMP plugin (no `android.newDsl`
    // opt-out, and no accessor-generation conflict inside this convention plugin).
    id("com.android.kotlin.multiplatform.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
    id("dev.detekt")
    id("signing")
}

// detekt — the only static analyzer that sees the Native actuals (CodeQL only traces JVM bytecode).
// Non-blocking, committed per-module baselines so only NEW findings surface. KMP sources live under
// src/<sourceSet>/kotlin, not detekt's default JVM layout, so point it at each src/*/kotlin.
detekt {
    buildUponDefaultConfig.set(true)
    baseline.set(layout.projectDirectory.file("config/detekt/baseline.xml"))
    parallel.set(true)
    ignoreFailures.set(true)
    val ktSourceRoots =
        layout.projectDirectory.dir("src").asFile
            .listFiles { f -> f.isDirectory }
            ?.map { layout.projectDirectory.dir("src/${it.name}/kotlin") }
            ?: emptyList()
    if (ktSourceRoots.isNotEmpty()) {
        source.setFrom(ktSourceRoots)
    }
}

group = "com.ditchoom.boringssl"

val onGithub = System.getenv("GITHUB_REPOSITORY")?.isNotBlank() == true
if (version.toString() == "unspecified") {
    // Local builds get -SNAPSHOT; CI publishes release versions. -Pversion=x still wins (Gradle sets
    // `version` from it, so this guard is skipped).
    version = computeNextVersion(snapshot = !onGithub)
}

// Structural identity, derived — never restated per module.
val moduleArtifactId = name
val androidNamespace = "com.ditchoom." + name.replace('-', '.')

// Lock the published public ABI so additive minors are proven non-breaking by `apiCheck`. Validate
// the JVM dump only (RFC §3/§6): it is host-independent and the common public surface is wholly
// contained in it, whereas klib validation diverges between partial-target dev hosts and CI runners.
apiValidation {
    klib {
        enabled = false
    }
}

repositories {
    google()
    mavenCentral()
}

kotlin {
    jvmToolchain(21)

    // Android as a first-class KMP target (new AGP KMP-library DSL). namespace / compileSdk / minSdk
    // are the only Android facts a library needs; the plugin publishes the release variant itself, so
    // there is no separate `android {}` block. commonTest also runs on the Android host JVM.
    // minSdk = 24 per RFC §5 Rule D / D7 (raised from webrtc's 21; the DTLS/QUIC datagram seam does not
    // target ancient Android). Android BoringSSL builds arm64-v8a + x86_64 only (ABI matrix trim lives
    // in :boringssl-android / :boringssl-build, step 2).
    android {
        namespace = androidNamespace
        compileSdk = 36
        minSdk = 24
        withHostTest { }
    }
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    // Apple targets register on macOS hosts only (compile-faithful locally, runtime-validated on the
    // macOS runner). Linux K/N always registers — the server-side lane and a downstream consumer.
    // This is the RFC §7 set of 12 K/N triples: linuxX64, linuxArm64 (always) + the 10 Apple triples
    // below. watchosArm64 (arm64_32) is omitted, exactly as buffer-crypto omits it; Windows/mingw is a
    // non-target (D6). tvOS/watchOS BoringSSL cross-compile is unproven and gated behind a spike (D4) —
    // registering the target here is compile-faithful only until step 7 proves the cmake path.
    if (HostManager.hostIsMac) {
        macosX64()
        macosArm64()
        iosArm64()
        iosSimulatorArm64()
        iosX64()
        tvosArm64()
        tvosSimulatorArm64()
        tvosX64()
        watchosSimulatorArm64()
        watchosX64()
    }
    linuxX64()
    linuxArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

ktlint {
    verbose.set(true)
    outputToConsole.set(true)
    android.set(true)
    filter {
        exclude("**/generated/**")
        exclude { it.file.path.contains("/generated/") || it.file.path.contains("/build/") }
    }
}

// ── Publishing / signing (POM prose from module gradle.properties; shared fields from root) ──
// NB: use findProperty (not providers.gradleProperty) for the per-module fields — the provider API
// deliberately ignores *subproject* gradle.properties, so POM_NAME/POM_DESCRIPTION (which live in each
// module's gradle.properties) are only visible via findProperty. Central rejects a POM with no
// <description>, so an empty value here would fail publishing.
val pomName = (findProperty("POM_NAME") as String?)?.takeIf { it.isNotBlank() } ?: moduleArtifactId
val pomDescription = (findProperty("POM_DESCRIPTION") as String?)?.takeIf { it.isNotBlank() }
    ?: error("POM_DESCRIPTION missing for :$name — add it to $name/gradle.properties (Central requires a POM description)")
val publishedGroupId = providers.gradleProperty("publishedGroupId").get()
val siteUrl = providers.gradleProperty("siteUrl").get()
val gitUrl = providers.gradleProperty("gitUrl").get()
val licenseName = providers.gradleProperty("licenseName").get()
val licenseUrl = providers.gradleProperty("licenseUrl").get()
val developerOrg = providers.gradleProperty("developerOrg").get()
val developerName = providers.gradleProperty("developerName").get()
val developerEmail = providers.gradleProperty("developerEmail").get()
val developerId = providers.gradleProperty("developerId").get()

// Sign + publish to Central only on the main branch of CI with the key present; local and PR builds
// publish unsigned to mavenLocal.
val signingKey = findProperty("signingInMemoryKey")
val signingPassword = findProperty("signingInMemoryKeyPassword")
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val shouldSignAndPublish = isMainBranchGithub && signingKey is String && signingPassword is String

if (shouldSignAndPublish) {
    signing {
        useInMemoryPgpKeys(signingKey as String, signingPassword as String)
        sign(extensions.getByType(PublishingExtension::class.java).publications)
    }
}

mavenPublishing {
    if (shouldSignAndPublish) {
        publishToMavenCentral()
        signAllPublications()
    }
    coordinates(publishedGroupId, moduleArtifactId, version.toString())
    pom {
        name.set(pomName)
        description.set(pomDescription)
        url.set(siteUrl)
        licenses {
            license {
                name.set(licenseName)
                url.set(licenseUrl)
            }
        }
        developers {
            developer {
                id.set(developerId)
                name.set(developerName)
                email.set(developerEmail)
            }
        }
        organization {
            name.set(developerOrg)
        }
        scm {
            connection.set(gitUrl)
            developerConnection.set(gitUrl)
            url.set(siteUrl)
        }
    }
}
