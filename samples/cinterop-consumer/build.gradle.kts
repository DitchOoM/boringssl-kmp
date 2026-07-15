import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform") version "2.4.0"
    id("com.ditchoom.boringssl.provision") // version pinned in settings.gradle.kts resolutionStrategy
}

repositories { mavenCentral() }

// The bundle version + dist dir are parameterized so the SAME sample drives the local dev flow and CI
// (build-apple.yaml). Defaults match the standard dev flow (`0.0.1-dev`, :boringssl-build/build/dist).
val bundleVersion = providers.gradleProperty("boringsslBundleVersion").orNull ?: "0.0.1-dev"
val distDir = providers.gradleProperty("boringsslDistDir").orNull
    ?: rootDir.resolve("../../boringssl-build/build/dist").absolutePath

kotlin {
    jvmToolchain(21)

    // Declare the K/N targets a consumer would use; only the host-buildable one actually links.
    val nativeTargets = listOf(macosArm64(), linuxX64())

    sourceSets["macosArm64Test"].dependencies { implementation(kotlin("test")) }
    sourceSets["linuxX64Test"].dependencies { implementation(kotlin("test")) }

    // ── The ENTIRE K/N consumption surface for BoringSSL: one line per target, no hand-written .def. ──
    // Each lane (build-apple / build-linux) builds only its own triple's tarball, so wire a target's
    // cinterop only when its bundle is present in distDir — otherwise boringsslDir() would fault at
    // configure time on a host that has no bundle for that triple.
    boringssl {
        version = bundleVersion
        localDist = file(distDir)
        nativeTargets.forEach { t: KotlinNativeTarget ->
            if (file("$distDir/boringssl-$bundleVersion-${t.name}.tar.gz").exists()) cinterop(t)
        }
    }
}
