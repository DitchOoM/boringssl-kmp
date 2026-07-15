plugins {
    id("boringssl.multiplatform-library")
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-testsuite — per-target link-smoke validation (RFC §3/§8). Proves each produced bundle
// actually LINKS + RUNS in a real consumer, not just that the symbols exist.
//
// STEP 2/3 (this): a linuxX64 cinterop link-smoke — cinterop the container-built (glibc-floor-safe)
// libcrypto.a/libssl.a from :boringssl-build, compute SHA256("abc") through BoringSSL, and assert the
// known vector. Because it statically links the archive into the K/N test binary against K/N's
// glibc-2.19 sysroot, a link failure (e.g. an above-floor symbol) fails the build — the real close of
// the RFC §8a floor loop. Apple/Android FFM/JNI/prefab smokes + the quiche ffi,qlog link-smoke follow.
//
// The archive is wired directly from :boringssl-build here; real consumers (step 4) resolve the same
// {include,lib} via the com.ditchoom.boringssl.provision plugin (already round-trip verified).
// Not a published API surface → apiCheck-excluded.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

// Not a published API surface → exclude from binary-compatibility validation. Disable BOTH the
// aggregate (apiCheck/apiDump) AND the per-target variants (jvmApiCheck/jvmApiDump…) — the aggregate
// alone leaves jvmApiCheck demanding a non-existent api/jvm/*.api dump and failing the build.
tasks.matching { it.name.endsWith("ApiCheck") || it.name.endsWith("ApiDump") }.configureEach {
    enabled = false
}

// Not a published artifact (RFC §3: testsuite is [not published]). The convention plugin wires
// mavenPublishing for every module it applies to; disable ALL publish tasks here so the release
// pipeline can never accidentally ship the link-smoke harness to Central.
tasks.matching { it.name.startsWith("publish") }.configureEach { enabled = false }

// ── linuxX64 link-smoke against :boringssl-build's container-built archive ──
val bsslBuild = project(":boringssl-build")
val smokeTriple = "linuxX64"
val provisioned = bsslBuild.projectDir.resolve("libs/boringssl/$smokeTriple")
// Depend on the ARCHIVE stage only — the K/N link-smoke statically links libssl.a/libcrypto.a and
// never touches libboringsslffi.so, so there's no need to trigger the .so link for this smoke.
val buildArchiveTask = "${bsslBuild.path}:buildBoringSslArchives${smokeTriple.replaceFirstChar { it.uppercase() }}"

val baseDef = layout.projectDirectory.file("src/nativeInterop/cinterop/boringsslsmoke.def")
val generatedDef = layout.buildDirectory.file("generated/cinterop/boringsslsmoke.def")

// Inject the resolved absolute libraryPaths + staticLibraries into the def config (before `---`), the
// way socket generates its LinuxSockets def. Depends on the archive build so the .a exists first.
val generateSmokeDef =
    tasks.register("generateSmokeDef") {
        dependsOn(buildArchiveTask)
        inputs.file(baseDef)
        outputs.file(generatedDef)
        doLast {
            val libDir = provisioned.resolve("lib")
            val base = baseDef.asFile.readText()
            val sep = base.indexOf("\n---")
            require(sep > 0) { "boringsslsmoke.def missing the '---' separator" }
            val injected =
                base.substring(0, sep) +
                    "\nlibraryPaths = ${libDir.absolutePath}" +
                    "\nstaticLibraries = libssl.a libcrypto.a\n" +
                    base.substring(sep)
            generatedDef.get().asFile.apply { parentFile.mkdirs(); writeText(injected) }
        }
    }

// Skip the K/N native target on a linux-aarch64 host: Kotlin/Native has no linux-aarch64 HOST target,
// so registering linuxX64 there throws at config time (same guard the convention plugin uses). The
// linuxX64 cinterop link-smoke only runs on an x86_64 host anyway.
val knHostSupported =
    !(
        System.getProperty("os.name").orEmpty().startsWith("Linux", ignoreCase = true) &&
            System.getProperty("os.arch").orEmpty() in listOf("aarch64", "arm64")
    )

if (knHostSupported) {
    kotlin {
        linuxX64 {
            compilations.getByName("main").cinterops.create("boringsslsmoke") {
                defFile(project.file("build/generated/cinterop/boringsslsmoke.def"))
                includeDirs(provisioned.resolve("include"))
            }
        }
    }

    // The cinterop must see the generated def (and thus the built archive) first.
    tasks.matching { it.name == "cinteropBoringsslsmokeLinuxX64" }.configureEach {
        dependsOn(generateSmokeDef)
    }
}

// ── macosArm64 link-smoke against :boringssl-build's per-SDK Apple archive (RFC §8 / D2) ──
// The Apple analog of the linuxX64 smoke: statically link the macosArm64 libssl.a/libcrypto.a into a
// K/N macos test binary and run SHA256. RUNTIME-validated on the macOS runner (not compile-faithful).
// Registers only on a macOS host, where the convention plugin exposes the macosArm64 K/N target.
val isMacHost = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)
if (isMacHost) {
    val appleTriple = "macosArm64"
    val appleProvisioned = bsslBuild.projectDir.resolve("libs/boringssl/$appleTriple")
    val appleArchiveTask = "${bsslBuild.path}:buildBoringSslApple${appleTriple.replaceFirstChar { it.uppercase() }}"
    val appleBaseDef = layout.projectDirectory.file("src/nativeInterop/cinterop/boringsslsmoke_apple.def")
    val appleGeneratedDef = layout.buildDirectory.file("generated/cinterop/boringsslsmoke_apple.def")

    val generateSmokeDefApple =
        tasks.register("generateSmokeDefApple") {
            dependsOn(appleArchiveTask)
            inputs.file(appleBaseDef)
            outputs.file(appleGeneratedDef)
            doLast {
                val libDir = appleProvisioned.resolve("lib")
                val base = appleBaseDef.asFile.readText()
                val sep = base.indexOf("\n---")
                require(sep > 0) { "boringsslsmoke_apple.def missing the '---' separator" }
                val injected =
                    base.substring(0, sep) +
                        "\nlibraryPaths = ${libDir.absolutePath}" +
                        "\nstaticLibraries = libssl.a libcrypto.a\n" +
                        base.substring(sep)
                appleGeneratedDef.get().asFile.apply { parentFile.mkdirs(); writeText(injected) }
            }
        }

    kotlin {
        macosArm64 {
            compilations.getByName("main").cinterops.create("boringsslsmokeApple") {
                defFile(project.file("build/generated/cinterop/boringsslsmoke_apple.def"))
                includeDirs(appleProvisioned.resolve("include"))
            }
        }
    }

    tasks.matching { it.name == "cinteropBoringsslsmokeAppleMacosArm64" }.configureEach {
        dependsOn(generateSmokeDefApple)
    }
}
