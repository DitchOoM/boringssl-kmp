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
