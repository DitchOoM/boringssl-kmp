import org.gradle.api.artifacts.VersionCatalogsExtension
import java.security.MessageDigest

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-build — PLAIN non-KMP Gradle project (RFC §3). Per-triple cmake cross-compile + packaging
// → `boringssl-<ver>-<triple>.tar.gz` (+ `.sha256`, `SHA256SUMS`, `provenance.json`).
//
// NOT published to Maven Central: the heavy static tarballs go to GitHub Releases (RFC §3 channel
// split). `libs/**` and `build/**` are gitignored; produced in CI (see README.md).
//
// STEP 2 (RFC §10): the Linux path is REAL here — ported from socket's proven `createBuildBoringSslTask`
// (both libssl.a + libcrypto.a, glibc-floor-safe). Apple/Android/tvOS/watchOS triples arrive in
// steps 7–8 behind their spikes. This is the load-bearing part of the whole consolidation.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

plugins {
    base
}

// The canonical BoringSSL commit is read from the version catalog — the ONE place the 40-hex literal
// lives (RFC §8 single-pin directive). No commit string is duplicated into any task.
val catalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
val boringsslCommit = catalog.findVersion("boringssl").get().requiredVersion
val boringsslApiVersion = catalog.findVersion("boringsslApiVersion").get().requiredVersion

// The boringssl-kmp *bundle* version (the release, NOT the BoringSSL commit) — names the tarballs the
// provision plugin resolves. Overridable with -PboringsslBundleVersion=x; defaults to a dev tag.
val bundleVersion = (findProperty("boringsslBundleVersion") as String?) ?: "0.0.1-dev"

// Deterministic packaging: pin a fixed epoch so tar/gzip mtimes are stable (RFC §11 reproducibility).
// 2023-05-08, the canonical commit's date — a stable, meaningful constant (Date.now() is intentionally
// avoided so re-packaging is bit-stable).
val sourceDateEpoch = "1683582543"

// One K/N triple's build recipe. The CANONICAL build runs inside the manylinux2014 (glibc 2.17)
// container, native-per-arch (amd64 directly / arm64 under qemu) — see docker/. The crossCc/host
// fields drive only the `-PnoContainer` dev fallback (host build; will fail the glibc-floor gate on a
// modern host — that's expected, it's dev-only).
data class NativeTriple(
    val id: String, // konan target id, e.g. linuxX64 — also the tarball/dir suffix
    val dockerArch: String, // amd64 | arm64 (manylinux base + Go tarball suffix)
    val dockerPlatform: String, // linux/amd64 | linux/arm64
    val crossCc: String?,
    val crossCxx: String?,
    val crossSystemProcessor: String?,
    val extraCFlags: List<String>,
)

val linuxTriples =
    listOf(
        NativeTriple(
            "linuxX64", dockerArch = "amd64", dockerPlatform = "linux/amd64",
            crossCc = null, crossCxx = null, crossSystemProcessor = null, extraCFlags = listOf("-fPIC"),
        ),
        // arm64: native aarch64 in the container (qemu). -mno-outline-atomics avoids emitting
        // __aarch64_cas*/__aarch64_ldadd* helper refs the K/N linker can't resolve (socket's fix).
        NativeTriple(
            "linuxArm64", dockerArch = "arm64", dockerPlatform = "linux/arm64",
            crossCc = "aarch64-linux-gnu-gcc",
            crossCxx = "aarch64-linux-gnu-g++",
            crossSystemProcessor = "aarch64",
            extraCFlags = listOf("-fPIC", "-mno-outline-atomics"),
        ),
    )

// ── Canonical build container (RFC §8a) ─────────────────────────────────────────────────────────
// The manylinux2014 image is content-addressed by a hash of the Dockerfile + build script, so it is
// a stable cache entry: built once, reused until the recipe changes.
val dockerDir = projectDir.resolve("docker")
val dockerRecipeHash =
    run {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        listOf(dockerDir.resolve("Dockerfile"), dockerDir.resolve("build-boringssl.sh")).forEach {
            if (it.exists()) md.update(it.readBytes())
        }
        md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }
fun imageTag(arch: String) = "ditchoom-boringssl-build:ml2014-$arch-$dockerRecipeHash"

// The FFI shim (boringssl_shim.{h,c} + exports map) is compiled + linked into libboringsslffi.so by
// the build, but is NOT baked into the container image (it is bind-mounted at /work). It gets its OWN
// hash, folded into the per-triple build marker below, so growing the shim surface rebuilds the
// bundle — without needlessly rebuilding the manylinux image.
// NOTE (optimization): today a shim edit reruns the whole triple, recompiling BoringSSL too, even
// though only the .so re-link depends on the shim. A future split (a cached `.a` build + a separate
// shim-keyed `.so` link task) would make shim iteration cheap; see RFC §10.
val shimHash =
    run {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        listOf(
            dockerDir.resolve("boringssl_shim.h"),
            dockerDir.resolve("boringssl_shim.c"),
            dockerDir.resolve("boringssl_ffi_exports.map"),
        ).forEach { if (it.exists()) md.update(it.readBytes()) }
        md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

val dockerUsable =
    !project.hasProperty("noContainer") &&
        try {
            ProcessBuilder("docker", "info").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0
        } catch (e: Exception) { false }

fun execCapture(vararg cmd: String): String =
    ProcessBuilder(*cmd).start().let { p -> val o = p.inputStream.bufferedReader().readText(); p.waitFor(); o.trim() }

// Build the manylinux2014 image once, per arch, tagged by the recipe hash → a stable cache entry.
fun ensureImage(arch: String, platform: String, tag: String) {
    val present =
        try {
            ProcessBuilder("docker", "image", "inspect", tag)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor() == 0
        } catch (e: Exception) { false }
    if (present) return
    val base = if (arch == "arm64") "quay.io/pypa/manylinux2014_aarch64" else "quay.io/pypa/manylinux2014_x86_64"
    val hostArch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "amd64"
    logger.lifecycle("Building canonical build image $tag (once; cached thereafter)...")
    // Native arch → legacy builder (no buildx dependency). Cross arch → buildx + qemu (CI installs
    // them via docker/setup-buildx-action + docker/setup-qemu-action). BASE selects the arch's glibc-2.17
    // image; the Dockerfile's Go tarball follows TARGETARCH (set by buildkit for the cross case).
    val cmd: List<String>
    val buildkit: String
    if (arch == hostArch) {
        cmd = listOf("docker", "build", "--build-arg", "BASE=$base", "-t", tag, dockerDir.absolutePath)
        buildkit = "0"
    } else {
        cmd = listOf("docker", "buildx", "build", "--platform", platform, "--build-arg", "BASE=$base", "-t", tag, "--load", dockerDir.absolutePath)
        buildkit = "1"
    }
    val pb = ProcessBuilder(cmd).directory(projectDir).redirectErrorStream(true)
    pb.environment()["DOCKER_BUILDKIT"] = buildkit
    val code = pb.start().also { it.inputStream.bufferedReader().forEachLine { l -> logger.lifecycle(l) } }.waitFor()
    if (code != 0) {
        throw GradleException(
            "docker build failed for $tag (arch=$arch, host=$hostArch)." +
                if (arch != hostArch) " Cross-arch builds need buildx + qemu — CI provides them; locally run " +
                    "`docker run --privileged --rm tonistiigi/binfmt --install all` and install docker-buildx." else "",
        )
    }
}

val boringsslWork = layout.buildDirectory.dir("boringssl")
val distDir = layout.buildDirectory.dir("dist")

// ── Shared source fetch: clone google/boringssl @ the pinned commit exactly once ──
val fetchBoringSsl =
    tasks.register("fetchBoringSsl") {
        group = "boringssl"
        description = "Clone google/boringssl @ $boringsslCommit (shallow, exact SHA)."
        val srcDir = boringsslWork.get().dir("src").asFile
        outputs.dir(srcDir)
        onlyIf { !File(srcDir, "CMakeLists.txt").exists() }
        doLast {
            srcDir.deleteRecursively(); srcDir.mkdirs()
            fun git(vararg a: String) =
                ProcessBuilder(listOf("git", *a)).directory(srcDir).redirectErrorStream(true).start()
                    .also { it.inputStream.bufferedReader().forEachLine { l -> logger.lifecycle(l) } }.waitFor()
            logger.lifecycle("Fetching google/boringssl @ $boringsslCommit (API v$boringsslApiVersion)...")
            if (git("init", "-q") != 0) throw GradleException("git init failed")
            git("remote", "add", "origin", "https://github.com/google/boringssl.git")
            if (git("fetch", "--depth", "1", "origin", boringsslCommit) != 0) {
                throw GradleException("Failed to fetch google/boringssl @ $boringsslCommit")
            }
            if (git("checkout", "-q", "FETCH_HEAD") != 0) throw GradleException("Failed to checkout $boringsslCommit")
        }
    }

// glibc >= 2.38 rewrites strtol/strtoul/strtoll/strtoull to __isoc23_* via a <stdlib.h> redirect, so a
// BoringSSL built on this host (glibc 2.39) emits references to __isoc23_strtoull etc. The Kotlin/Native
// linuxX64/linuxArm64 sysroots ship an OLDER glibc without those symbols → K/N link fails. We ar-merge a
// tiny compat TU that defines them as thin forwarders to the real (pre-C23) entry points. Declaring the
// targets via __asm__ (and NOT including <stdlib.h>) sidesteps the redirect, so no self-recursion.
val isoC23CompatSource =
    """
    /* generated by :boringssl-build — glibc>=2.38 __isoc23_* compat shim for Kotlin/Native linking. */
    extern unsigned long long strtoull(const char *, char **, int) __asm__("strtoull");
    extern long long          strtoll (const char *, char **, int) __asm__("strtoll");
    extern unsigned long      strtoul (const char *, char **, int) __asm__("strtoul");
    extern long               strtol  (const char *, char **, int) __asm__("strtol");
    unsigned long long __isoc23_strtoull(const char *n, char **e, int b){ return strtoull(n, e, b); }
    long long          __isoc23_strtoll (const char *n, char **e, int b){ return strtoll (n, e, b); }
    unsigned long      __isoc23_strtoul (const char *n, char **e, int b){ return strtoul (n, e, b); }
    long               __isoc23_strtol  (const char *n, char **e, int b){ return strtol  (n, e, b); }
    """.trimIndent()

fun sha256Of(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { ins ->
        val buf = ByteArray(1 shl 16)
        while (true) { val r = ins.read(buf); if (r < 0) break; md.update(buf, 0, r) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun runOrThrow(vararg cmd: String, dir: File, what: String) {
    val code = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        .also { it.inputStream.bufferedReader().forEachLine { l -> logger.lifecycle(l) } }.waitFor()
    if (code != 0) throw GradleException("$what failed (exit $code): ${cmd.joinToString(" ")}")
}

// ── glibc-floor gate (RFC §8) ─────────────────────────────────────────────────────────────────────
// K/N links linux consumer binaries against a bundled OLD glibc sysroot (2.19 x64 / 2.25 arm64). Any
// symbol our archives need that the FLOOR libc lacks fails the consumer's K/N link (that's what the
// __isoc23_* shim fixes). This is the automated, non-whack-a-mole guard: an "offender" is precisely a
// symbol the BUILD-host glibc provides but the FLOOR glibc does not — i.e. a too-new glibc dependency.
// Comparing against host∩¬floor (not a hand-written denylist) catches __isoc23_* AND any future
// version-gated symbol, while NOT false-flagging libgcc/compiler builtins (absent from host libc).

fun exportedSymbols(so: File): Set<String> {
    if (!so.exists()) return emptySet()
    val out = HashSet<String>()
    val p = ProcessBuilder("nm", "-D", "--defined-only", so.absolutePath).redirectErrorStream(false).start()
    p.inputStream.bufferedReader().forEachLine { l ->
        val name = l.trim().substringAfterLast(' ')
        if (name.isNotEmpty() && !name.contains(' ')) out.add(name.substringBefore('@'))
    }
    p.waitFor()
    return out
}

fun archiveExternalUndefined(archives: List<File>): Set<String> {
    val defined = HashSet<String>()
    val undef = HashSet<String>()
    for (a in archives) {
        val p = ProcessBuilder("nm", a.absolutePath).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().forEachLine { l ->
            val cols = l.trim().split(Regex("\\s+"))
            if (cols.size >= 2) {
                val type = cols[cols.size - 2]
                val name = cols[cols.size - 1]
                when {
                    type == "U" -> undef.add(name)
                    type.length == 1 -> defined.add(name) // T/t/W/w/D/B/R/… = defined somewhere in the archive
                }
            }
        }
        p.waitFor()
    }
    return undef - defined // truly external refs (intra-archive resolutions removed)
}

// FLOOR libc(s): the K/N bundled sysroot for the triple (glibc 2.19 x64 / 2.25 arm64).
fun konanFloorLibs(triple: String): List<File>? {
    val deps = File(System.getProperty("user.home"), ".konan/dependencies")
    if (!deps.isDirectory) return null
    val arch = if (triple == "linuxArm64") "aarch64-unknown-linux-gnu" else "x86_64-unknown-linux-gnu"
    val dep = deps.listFiles { f -> f.isDirectory && f.name.startsWith(arch) && f.name.contains("glibc") }
        ?.maxByOrNull { it.name } ?: return null
    val sysroot = dep.walkTopDown().firstOrNull { it.isDirectory && it.name == "sysroot" } ?: return null
    return sysroot.walkTopDown()
        .filter { it.isFile && Regex("^lib(c|pthread|m|dl|rt)\\.so.*").matches(it.name) }.toList()
        .takeIf { it.isNotEmpty() }
}

// BUILD-host libc: the glibc the archive was actually compiled against (system for x64, cross for arm64).
fun buildHostLibc(triple: String): File? {
    val candidates =
        if (triple == "linuxArm64") {
            listOf("/usr/aarch64-linux-gnu/lib/libc.so.6")
        } else {
            listOf("/lib/x86_64-linux-gnu/libc.so.6", "/usr/lib/x86_64-linux-gnu/libc.so.6", "/lib64/libc.so.6")
        }
    return candidates.map { File(it) }.firstOrNull { it.exists() }
}

fun registerTriple(t: NativeTriple): Pair<TaskProvider<Task>, TaskProvider<Task>> {
    val cap = t.id.replaceFirstChar { it.uppercase() }
    val outDir = projectDir.resolve("libs/boringssl/${t.id}")
    // Marker keyed on the BoringSSL commit, the build-recipe hash, AND the shim hash — so a
    // container/toolchain change OR a shim-surface change rebuilds. This is the local build cache:
    // onlyIf skips when a matching bundle already exists.
    val marker = outDir.resolve("lib/.built-$boringsslCommit-$dockerRecipeHash-$shimHash")
    val crossing = t.crossCc != null && System.getProperty("os.arch") != "aarch64"

    val build =
        tasks.register("buildBoringSsl$cap") {
            group = "boringssl"
            description = "Build BoringSSL static libs (libssl.a + libcrypto.a) + headers for ${t.id}."
            dependsOn(fetchBoringSsl)
            inputs.property("commit", boringsslCommit)
            inputs.property("recipe", dockerRecipeHash)
            inputs.property("shim", shimHash)
            inputs.property("container", dockerUsable)
            outputs.dir(outDir)
            outputs.cacheIf { true }
            // Rebuild if the archives are stale OR the FFM shared lib is absent (so adding the .so
            // step re-triggers a triple whose .a were already cached from an earlier recipe).
            onlyIf { !marker.exists() || !outDir.resolve("lib/libboringsslffi.so").exists() }
            doLast {
                val srcDir = boringsslWork.get().dir("src").asFile
                val cFlags = t.extraCFlags.joinToString(" ")

                if (dockerUsable) {
                    // ── CANONICAL: build inside manylinux2014 (glibc 2.17), native per-arch (RFC §8a) ──
                    val tag = imageTag(t.dockerArch)
                    ensureImage(t.dockerArch, t.dockerPlatform, tag)
                    val uid = execCapture("id", "-u")
                    val gid = execCapture("id", "-g")
                    logger.lifecycle("Building BoringSSL for ${t.id} in $tag (glibc 2.17)...")
                    runOrThrow(
                        "docker", "run", "--rm", "--platform", t.dockerPlatform,
                        "--user", "$uid:$gid", "-e", "HOME=/tmp", "-e", "CFLAGS=$cFlags", "-e", "CC=cc",
                        "-v", "${rootDir.absolutePath}:/work",
                        tag, "/work/boringssl-build/docker/build-boringssl.sh", t.id,
                        dir = projectDir, what = "container build ${t.id}",
                    )
                } else {
                    // ── DEV FALLBACK (-PnoContainer or no docker): host build. NOT glibc-floor-safe on a
                    //    modern host — checkGlibcFloor will (correctly) fail it. Dev iteration only. ──
                    logger.warn("checkGlibcFloor note: ${t.id} built on the HOST (no container) — not release-portable.")
                    val cmakeBuildDir = boringsslWork.get().dir("build-${t.id}").asFile
                    cmakeBuildDir.deleteRecursively(); cmakeBuildDir.mkdirs()
                    val cmakeArgs =
                        mutableListOf(
                            "cmake", "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF",
                            "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                            "-DCMAKE_C_FLAGS=$cFlags", "-DCMAKE_CXX_FLAGS=$cFlags", "-G", "Unix Makefiles",
                        )
                    if (crossing) {
                        cmakeArgs.addAll(
                            listOf(
                                "-DCMAKE_SYSTEM_NAME=Linux", "-DCMAKE_SYSTEM_PROCESSOR=${t.crossSystemProcessor}",
                                "-DCMAKE_C_COMPILER=${t.crossCc}", "-DCMAKE_CXX_COMPILER=${t.crossCxx}",
                            ),
                        )
                    }
                    cmakeArgs.add(srcDir.absolutePath)
                    runOrThrow(*cmakeArgs.toTypedArray(), dir = cmakeBuildDir, what = "cmake configure ${t.id}")
                    runOrThrow("make", "-j${Runtime.getRuntime().availableProcessors()}", "ssl", "crypto",
                        dir = cmakeBuildDir, what = "make ${t.id}")
                    outDir.resolve("lib").mkdirs()
                    val ssl = cmakeBuildDir.walkTopDown().firstOrNull { it.name == "libssl.a" }
                        ?: throw GradleException("libssl.a not found for ${t.id}")
                    val crypto = cmakeBuildDir.walkTopDown().firstOrNull { it.name == "libcrypto.a" }
                        ?: throw GradleException("libcrypto.a not found for ${t.id}")
                    ssl.copyTo(outDir.resolve("lib/libssl.a"), overwrite = true)
                    crypto.copyTo(outDir.resolve("lib/libcrypto.a"), overwrite = true)
                    val compatC = cmakeBuildDir.resolve("isoc23_compat.c").apply { writeText(isoC23CompatSource) }
                    val compatO = cmakeBuildDir.resolve("isoc23_compat.o")
                    val cc = t.crossCc ?: "cc"
                    runOrThrow(cc, "-c", "-fPIC", "-O2", compatC.absolutePath, "-o", compatO.absolutePath,
                        dir = cmakeBuildDir, what = "compile isoc23 compat ${t.id}")
                    runOrThrow("ar", "r", outDir.resolve("lib/libcrypto.a").absolutePath, compatO.absolutePath,
                        dir = cmakeBuildDir, what = "ar-merge isoc23 compat ${t.id}")
                    val inc = listOf(srcDir.resolve("src/include"), srcDir.resolve("include")).first { it.exists() }
                    inc.copyRecursively(outDir.resolve("include"), overwrite = true)

                    // libboringsslffi.so (host fallback; mirrors docker/build-boringssl.sh). Only the
                    // shim's boringssl_ffi_* symbols are exported; BoringSSL stays hidden (D3). NOT
                    // glibc-floor-safe on a modern host — dev iteration only, same caveat as the .a.
                    val shimDir = projectDir.resolve("docker")
                    val ffiCc = t.crossCc ?: "cc"
                    val shimO = cmakeBuildDir.resolve("boringssl_shim.o")
                    runOrThrow(
                        ffiCc, "-c", "-fPIC", "-O2", "-fvisibility=hidden",
                        "-I", outDir.resolve("include").absolutePath,
                        shimDir.resolve("boringssl_shim.c").absolutePath, "-o", shimO.absolutePath,
                        dir = cmakeBuildDir, what = "compile ffi shim ${t.id}",
                    )
                    runOrThrow(
                        ffiCc, "-shared", "-fPIC", "-o", outDir.resolve("lib/libboringsslffi.so").absolutePath,
                        shimO.absolutePath,
                        outDir.resolve("lib/libssl.a").absolutePath, outDir.resolve("lib/libcrypto.a").absolutePath,
                        "-Wl,--exclude-libs,ALL",
                        "-Wl,--version-script=${shimDir.resolve("boringssl_ffi_exports.map").absolutePath}",
                        "-Wl,--gc-sections", "-lpthread",
                        dir = cmakeBuildDir, what = "link libboringsslffi.so ${t.id}",
                    )
                }

                require(outDir.resolve("lib/libcrypto.a").exists()) { "libcrypto.a missing after build for ${t.id}" }
                require(outDir.resolve("lib/libboringsslffi.so").exists()) { "libboringsslffi.so missing after build for ${t.id}" }
                marker.writeText("google/boringssl @ $boringsslCommit (API v$boringsslApiVersion) ${t.id} [${if (dockerUsable) "container:manylinux2014" else "host"}]\n")
                logger.lifecycle("BoringSSL built for ${t.id} → ${outDir.resolve("lib")}")
            }
        }

    val verify =
        tasks.register("checkGlibcFloor$cap") {
            group = "boringssl"
            description = "Fail if ${t.id}'s archives need a glibc newer than the K/N sysroot floor (RFC §8)."
            dependsOn(build)
            doLast {
                val archives = listOf(outDir.resolve("lib/libcrypto.a"), outDir.resolve("lib/libssl.a"))
                val external = archiveExternalUndefined(archives)
                val floorLibs = konanFloorLibs(t.id)
                val hostLibc = buildHostLibc(t.id)
                if (floorLibs == null || hostLibc == null) {
                    // Fallback when the K/N sysroot or the build libc isn't locatable: catch the known
                    // C23 family by name so a regression of the shim still fails the build.
                    val bad = external.filter { it.startsWith("__isoc23_") }
                    if (bad.isNotEmpty()) throw GradleException("glibc-floor violation (${t.id}): ${bad.sorted()} — the __isoc23 compat shim regressed.")
                    logger.warn("checkGlibcFloor${cap}: K/N sysroot or host libc not found — ran name-based fallback only.")
                    return@doLast
                }
                val floor = floorLibs.flatMap { exportedSymbols(it) }.toSet()
                val host = exportedSymbols(hostLibc)
                // Offender = referenced, provided by the build glibc, absent from the floor glibc.
                val offenders = external.filter { it in host && it !in floor }.sorted()
                if (offenders.isNotEmpty()) {
                    throw GradleException(
                        "glibc-floor violation for ${t.id}: ${offenders} require a glibc newer than the K/N floor " +
                            "(${floorLibs.first().parentFile.parentFile.name}). Build on an old-glibc base (manylinux2014 / " +
                            "ubuntu-20.04) or extend the __isoc23 compat shim.",
                    )
                }
                logger.lifecycle("checkGlibcFloor${cap}: OK — ${external.size} external symbols, all within the K/N glibc floor.")
            }
        }

    val pkg =
        tasks.register("packageBoringSsl$cap") {
            group = "boringssl"
            description = "Package ${t.id} into boringssl-$bundleVersion-${t.id}.tar.gz (+ .sha256, provenance)."
            dependsOn(build, verify)
            doLast {
                val dist = distDir.get().asFile.apply { mkdirs() }
                val tarFile = dist.resolve("boringssl-$bundleVersion-${t.id}.tar")
                val tarGz = dist.resolve("boringssl-$bundleVersion-${t.id}.tar.gz")
                tarGz.delete()
                // Deterministic tar (sorted, fixed owner/mtime) then `gzip -n` (no embedded timestamp).
                // Two sequential processes via a temp file — NOT a shell pipe (a tar|gzip pipe deadlocks
                // once gzip's 64KB stdout buffer fills while we're still feeding its stdin).
                // The K/N/provision tarball carries only the static .a + headers. The FFM shared lib
                // (libboringsslffi.so) is excluded here — it ships in the boringssl-jvm MRJAR, not the
                // K/N bundle — so K/N consumers don't pay ~1MB for a library they never link.
                runOrThrow(
                    "tar", "--sort=name", "--mtime=@$sourceDateEpoch",
                    "--owner=0", "--group=0", "--numeric-owner", "--exclude=libboringsslffi.so",
                    "-cf", tarFile.absolutePath, "-C", outDir.absolutePath, "include", "lib",
                    dir = dist, what = "tar ${t.id}",
                )
                runOrThrow("gzip", "-nf", tarFile.absolutePath, dir = dist, what = "gzip ${t.id}")

                // Emit the FFM shared lib as its own checksummed artifact (directive #4: every artifact
                // carries a sha256). CI uploads these per-runner; the release job gathers all platforms'
                // .so into the single cross-platform MRJAR.
                val soSrc = outDir.resolve("lib/libboringsslffi.so")
                if (soSrc.exists()) {
                    val soOut = dist.resolve("libboringsslffi-$bundleVersion-${t.id}.so")
                    soSrc.copyTo(soOut, overwrite = true)
                    dist.resolve("${soOut.name}.sha256").writeText("${sha256Of(soOut)}  ${soOut.name}\n")
                }

                val digest = sha256Of(tarGz)
                dist.resolve("${tarGz.name}.sha256").writeText("$digest  ${tarGz.name}\n")
                dist.resolve("boringssl-$bundleVersion-${t.id}.provenance.json").writeText(
                    """
                    {
                      "artifact": "${tarGz.name}",
                      "sha256": "$digest",
                      "boringsslCommit": "$boringsslCommit",
                      "boringsslApiVersion": $boringsslApiVersion,
                      "quicheAbi": "${catalog.findVersion("boringsslQuicheAbi").get().requiredVersion}",
                      "triple": "${t.id}",
                      "bundleVersion": "$bundleVersion",
                      "sourceDateEpoch": $sourceDateEpoch
                    }
                    """.trimIndent() + "\n",
                )
                logger.lifecycle("Packaged ${t.id}: ${tarGz.name}  sha256=$digest")
            }
        }
    return build to pkg
}

val triples = linuxTriples.associate { it.id to registerTriple(it) }

tasks.register("buildBoringSslLinux") {
    group = "boringssl"
    description = "Build all wired Linux BoringSSL triples."
    dependsOn(triples.values.map { it.first })
}

val packageLinux =
    tasks.register("packageBoringSslLinux") {
        group = "boringssl"
        description = "Package all wired Linux triples + write a combined SHA256SUMS."
        dependsOn(triples.values.map { it.second })
        doLast {
            val dist = distDir.get().asFile
            val sums = dist.listFiles { f -> f.name.endsWith(".tar.gz") }?.sortedBy { it.name }?.joinToString("") {
                "${sha256Of(it)}  ${it.name}\n"
            }.orEmpty()
            dist.resolve("SHA256SUMS").writeText(sums)
            logger.lifecycle("Wrote SHA256SUMS:\n$sums")
        }
    }

// `assemble` produces the distributable tarballs.
tasks.named("assemble") { dependsOn(packageLinux) }
