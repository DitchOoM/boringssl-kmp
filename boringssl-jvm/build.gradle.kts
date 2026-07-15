import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("boringssl.multiplatform-library")
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-jvm — FFM producer (RFC §3/§4). Ships a MULTI-RELEASE JAR:
//
//   <root>/…                             jvmMain, compiled at JVM 8 — public API + native loader,
//                                        references NO java.lang.foreign, so the jar links on any JDK.
//   META-INF/versions/21/…               jvm21Main, compiled at JvmTarget.JVM_21 — the FFM downcalls
//                                        (Panama21Backend), loaded only on JDK 21+.
//   META-INF/native/<os>-<arch>/…        the shared libboringsslffi.{so,dylib} for each platform,
//                                        extracted + System.load-ed at runtime by NativeLibraryLoader.
//
// WHY Kotlin FFM (not shipped jextract Java): FFM is a *preview* API in JDK 21. javac stamps
// --enable-preview output with the class-file preview flag, which version-pins it to JDK 21 (it throws
// UnsupportedClassVersionError on 22+). kotlinc does NOT set that flag, so these bindings run on stock
// JDK 21 with no --enable-preview AND load unchanged on 22/25. jextract stays a build-time reference
// for authoring the shim/bindings, not the shipped artifact. A future JDK-22 final-API slice is a
// small add: a `jvm22` compilation whose output lands under META-INF/versions/22 (see below).
//
// The shared library is produced by :boringssl-build (it owns the glibc-2.17 container + the curated
// boringssl_shim.c); this module only PACKAGES it. On a host that can only build some platforms'
// .so, the MRJAR bundles what exists and logs the rest (the release job gathers all platforms).
// ─────────────────────────────────────────────────────────────────────────────────────────────────

// ── native library staging: gather each platform's libboringsslffi from :boringssl-build/libs ──
val nativeStageDir = layout.buildDirectory.dir("generated/native-resources")
val boringsslBuildDir = project(":boringssl-build").projectDir

data class NativeSo(
    val triple: String,
    val osArch: String,
    val soName: String,
)

val nativeSoMatrix =
    listOf(
        NativeSo("linuxX64", "linux-x86_64", "libboringsslffi.so"),
        NativeSo("linuxArm64", "linux-aarch64", "libboringsslffi.so"),
        NativeSo("macosX64", "macos-x86_64", "libboringsslffi.dylib"),
        NativeSo("macosArm64", "macos-aarch64", "libboringsslffi.dylib"),
    )

val stageNativeLibs =
    tasks.register("stageNativeLibs") {
        group = "boringssl"
        description = "Stage libboringsslffi.{so,dylib} into META-INF/native/<os>-<arch>/ for the MRJAR."
        outputs.dir(nativeStageDir)
        doLast {
            val root = nativeStageDir.get().asFile
            root.deleteRecursively()
            var staged = 0
            val missing = mutableListOf<String>()
            nativeSoMatrix.forEach { m ->
                val src = boringsslBuildDir.resolve("libs/boringssl/${m.triple}/lib/${m.soName}")
                if (src.exists()) {
                    val dst = root.resolve("META-INF/native/${m.osArch}/${m.soName}")
                    dst.parentFile.mkdirs()
                    src.copyTo(dst, overwrite = true)
                    staged++
                    logger.lifecycle("stageNativeLibs: bundled ${m.osArch}/${m.soName}")
                } else {
                    missing += m.osArch
                }
            }
            if (staged == 0) {
                throw GradleException(
                    "No libboringsslffi found under :boringssl-build/libs — build one first, e.g. " +
                        "`./gradlew :boringssl-build:buildBoringSslLinuxX64`.",
                )
            }
            if (missing.isNotEmpty()) {
                logger.warn(
                    "boringssl-jvm MRJAR: no native library for ${missing.sorted()} on this host — those " +
                        "platforms are ABSENT from this jar (built per-runner; the release job assembles all).",
                )
            }
        }
    }

kotlin {
    jvm {
        val mainCompilation = compilations.getByName("main")

        // FFM implementation slice → META-INF/versions/21, compiled at JVM 21 (FFM's floor).
        // Classpath + internal-visibility wiring to jvmMain is done below on the concrete compile
        // tasks (friendPaths/libraries pointed at main's CLASSES DIRS — NOT associateWith, which would
        // route the classpath through jvmJar and, since we also pack jvm21 into jvmJar, form a cycle).
        compilations.create("jvm21") {
            defaultSourceSet.kotlin.setSrcDirs(listOf("src/jvm21Main/kotlin"))
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
            }
        }

        // FFM link-smoke tests, run on the JDK-21 toolchain (no --enable-preview needed). They touch
        // only the public BoringSsl API (from jvmMain), so a plain compile classpath suffices.
        compilations.create("jvm21Test") {
            defaultSourceSet.kotlin.setSrcDirs(listOf("src/jvm21Test/kotlin"))
            defaultSourceSet.dependencies {
                implementation(kotlin("test-junit5"))
            }
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
            }
        }
    }

    // The staged native libs ride along as jvmMain resources: processed onto the test runtime
    // classpath (so the loader finds them) and packed into the jar root's META-INF/native.
    sourceSets.getByName("jvmMain").resources.srcDir(nativeStageDir)
}

tasks.named("jvmProcessResources") { dependsOn(stageNativeLibs) }

// Grant the FFM slices access to jvmMain via its CLASSES DIRS (breaks the jvmJar cycle). friendPaths
// gives jvm21 internal visibility of FfiBackend; the test only needs the public API on its classpath.
run {
    val mainComp = kotlin.jvm().compilations.getByName("main")
    val jvm21Comp = kotlin.jvm().compilations.getByName("jvm21")
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileJvm21KotlinJvm") {
        libraries.from(mainComp.output.classesDirs, mainComp.compileDependencyFiles)
        friendPaths.from(mainComp.output.classesDirs)
    }
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileJvm21TestKotlinJvm") {
        libraries.from(
            mainComp.output.classesDirs,
            mainComp.compileDependencyFiles,
            jvm21Comp.output.classesDirs,
        )
    }
}

// ── MRJAR assembly ──────────────────────────────────────────────────────────────────────────────
tasks.named<Jar>("jvmJar") {
    manifest {
        attributes(
            mapOf(
                "Multi-Release" to "true",
                // Authorizes FFM native linkage without the runtime warning; on a future JDK where
                // native access is enforced, this keeps the jar working out of the box.
                "Enable-Native-Access" to "ALL-UNNAMED",
            ),
        )
    }
    val jvm21Output =
        kotlin
            .jvm()
            .compilations
            .getByName("jvm21")
            .output.allOutputs
    into("META-INF/versions/21") {
        from(jvm21Output)
    }
}

// ── FFM link-smoke test task (custom compilation → needs its own Test task) ───────────────────────
val jvm21TestTask =
    tasks.register<Test>("jvm21Test") {
        group = "verification"
        description = "Run the JVM FFM link-smoke tests against the bundled libboringsslffi."
        dependsOn(stageNativeLibs)
        val jvmTarget = kotlin.jvm()
        val testComp = jvmTarget.compilations.getByName("jvm21Test")
        val jvm21Comp = jvmTarget.compilations.getByName("jvm21")
        val mainComp = jvmTarget.compilations.getByName("main")
        testClassesDirs = testComp.output.classesDirs
        classpath =
            files(
                testComp.output.allOutputs,
                jvm21Comp.output.allOutputs,
                mainComp.output.allOutputs, // includes processed resources (the staged .so)
                testComp.runtimeDependencyFiles,
            )
        useJUnitPlatform()
        // Kotlin FFM carries no preview flag → NO --enable-preview here; just authorize native access.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }

tasks.named("check") { dependsOn(jvm21TestTask) }
