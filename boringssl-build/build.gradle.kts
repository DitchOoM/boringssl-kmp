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
// Android ANDROID_PLATFORM floor for the per-ABI static .a — kept in the catalog so it can never drift
// from the convention plugin's minSdk (RFC §5 Rule D / D7).
val boringsslAndroidApi = catalog.findVersion("boringsslAndroidApi").get().requiredVersion

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
// The manylinux2014 image is content-addressed by a hash of the Dockerfile ALONE, so it is a stable
// cache entry: built once, reused until the toolchain recipe changes. The build scripts are NOT baked
// into the image (they are bind-mounted at /work), so editing a script must NOT rebuild the image.
val dockerDir = projectDir.resolve("docker")

fun hashFiles(vararg files: File): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    files.forEach { if (it.exists()) md.update(it.readBytes()) }
    return md.digest().joinToString("") { "%02x".format(it) }.take(12)
}

// Image content = the Dockerfile only → the image tag / cache key.
val imageHash = hashFiles(dockerDir.resolve("Dockerfile"))
fun imageTag(arch: String) = "ditchoom-boringssl-build:ml2014-$arch-$imageHash"

// ── Two decoupled build recipes (this is the .a/.so split — RFC §10) ─────────────────────────────
// The expensive static-archive build (build-archives.sh: compiles all of BoringSSL) and the cheap
// FFI re-link (link-ffi.sh: shim → libboringsslffi.so) are keyed on SEPARATE hashes, so a shim edit
// re-links only the .so instead of recompiling BoringSSL. See the two markers in registerTriple().
//   • archiveRecipeHash — the .a build recipe (independent of the shim surface).
//   • shimHash          — the curated shim surface + the .so link recipe.
val archiveRecipeHash = hashFiles(dockerDir.resolve("build-archives.sh"))
val shimHash =
    hashFiles(
        dockerDir.resolve("boringssl_shim.h"),
        dockerDir.resolve("boringssl_shim.c"),
        dockerDir.resolve("boringssl_ffi_exports.map"),
        dockerDir.resolve("link-ffi.sh"),
    )

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

// ── Patch axis (RFC §12 D9) ───────────────────────────────────────────────────────────────────────
// Ordered `*.patch` files under boringssl-build/patches/ are applied to the fetched BoringSSL source
// with `git apply -p1` in FILENAME order (convention: `NN-description.patch`), BEFORE any cmake. The
// ordered patch-set hash (names + contents) folds into EVERY build marker AND provenance.json, so a
// patch change forces a rebuild and is fully identified (a build-only patch → PATCH bump; a
// behaviour/ABI-affecting patch → MINOR/MAJOR — see patches/README.md).
//
// At the canonical pin the set is EMPTY: plain google/boringssl @ the pin is expected to satisfy the
// quiche `ffi,qlog` link-smoke (the boring-sys feature patches add symbols without changing the ABI —
// RFC §10). If that link-smoke ever fails, drop the needed patches here. An empty set hashes to a
// stable constant, so the machinery is inert until a patch is added.
val patchesDir = projectDir.resolve("patches")

fun orderedPatches(): List<File> =
    patchesDir.listFiles { f -> f.isFile && f.name.endsWith(".patch") }?.sortedBy { it.name } ?: emptyList()

// Content-address the ordered patch set by (name + bytes) of each patch, in order. Empty → constant.
val patchSetHash: String =
    run {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        orderedPatches().forEach { md.update(it.name.toByteArray()); md.update(it.readBytes()) }
        md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }
val patchSetNames: List<String> = orderedPatches().map { it.name }

// ── Shared source fetch + patch: clone google/boringssl @ the pinned commit, apply the patch set ──
val fetchBoringSsl =
    tasks.register("fetchBoringSsl") {
        group = "boringssl"
        description = "Clone google/boringssl @ $boringsslCommit (shallow, exact SHA) + apply patches/ (D9)."
        val srcDir = boringsslWork.get().dir("src").asFile
        // The patchset marker records WHICH patch set the checked-out tree carries; a patch change
        // flips the hash so the tree is re-prepared (clean checkout + re-apply), never left half-patched.
        val patchMarker = File(srcDir, ".patchset-$patchSetHash")
        inputs.property("commit", boringsslCommit)
        inputs.property("patchSet", patchSetHash)
        orderedPatches().forEach { inputs.file(it) }
        outputs.dir(srcDir)
        onlyIf { !(File(srcDir, "CMakeLists.txt").exists() && patchMarker.exists()) }
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

            // Apply the ordered patch set (D9). `git apply -p1 --check` first so a bad patch fails loud
            // BEFORE mutating the tree; then apply. Filename order = apply order.
            val patches = orderedPatches()
            patches.forEach { p ->
                logger.lifecycle("Applying patch ${p.name} (git apply -p1)...")
                if (git("apply", "-p1", "--check", p.absolutePath) != 0) {
                    throw GradleException("Patch ${p.name} does NOT apply cleanly to $boringsslCommit (git apply --check failed).")
                }
                if (git("apply", "-p1", p.absolutePath) != 0) {
                    throw GradleException("Failed to apply patch ${p.name}.")
                }
            }
            patchMarker.writeText(
                "boringssl @ $boringsslCommit + patchSet=$patchSetHash [${patches.joinToString(",") { it.name }.ifEmpty { "none" }}]\n",
            )
            if (patches.isEmpty()) {
                logger.lifecycle("No patches to apply (empty patch set, hash $patchSetHash).")
            } else {
                logger.lifecycle("Applied ${patches.size} patch(es) [$patchSetHash]: ${patches.joinToString(", ") { it.name }}")
            }
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

// Portable deterministic tar for the Apple lane (runs on macOS, whose BSD `tar` lacks GNU's
// --sort/--mtime/--owner). Normalizes every entry's mtime to the fixed epoch (TZ=UTC) and feeds a
// SORTED relative-path list in `ustar` format — a recipe both bsdtar and GNU tar honour identically, so
// the archive is byte-stable across the local mac and the macos-14 runner (the Apple analog of the
// Linux GNU-tar reproducibility recipe). Packs `include` + `lib` under `root`, minus `excludeNames`.
fun deterministicApplePackage(root: File, tarFile: File, epoch: String, excludeNames: Regex) {
    val entries =
        listOf("include", "lib").flatMap { top ->
            root.resolve(top).walkTopDown()
                .filter { it.isFile && !excludeNames.matches(it.name) }
                .map { it.relativeTo(root).path }.toList()
        }.sorted()
    require(entries.isNotEmpty()) { "no files to package under $root" }
    val stamp =
        java.time.Instant.ofEpochSecond(epoch.toLong()).atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmm.ss"))
    entries.forEach { rel ->
        val pb = ProcessBuilder("touch", "-t", stamp, rel).directory(root)
        pb.environment()["TZ"] = "UTC"
        if (pb.start().waitFor() != 0) throw GradleException("touch failed normalizing mtime for $rel")
    }
    val listFile = tarFile.parentFile.resolve("${tarFile.name}.filelist").apply { writeText(entries.joinToString("\n") + "\n") }
    try {
        val pb =
            ProcessBuilder(
                "tar", "--format", "ustar", "--uid", "0", "--gid", "0", "--numeric-owner",
                "-cf", tarFile.absolutePath, "-T", listFile.absolutePath,
            ).directory(root).redirectErrorStream(true)
        pb.environment()["TZ"] = "UTC"
        val code = pb.start().also { it.inputStream.bufferedReader().forEachLine { l -> logger.lifecycle(l) } }.waitFor()
        if (code != 0) throw GradleException("tar (apple) failed (exit $code) for ${tarFile.name}")
    } finally {
        listFile.delete()
    }
}

fun runOrThrow(vararg cmd: String, dir: File, what: String) {
    val code = ProcessBuilder(*cmd).directory(dir).redirectErrorStream(true).start()
        .also { it.inputStream.bufferedReader().forEachLine { l -> logger.lifecycle(l) } }.waitFor()
    if (code != 0) throw GradleException("$what failed (exit $code): ${cmd.joinToString(" ")}")
}

// Write a build marker, first deleting any stale siblings sharing its prefix, so EXACTLY ONE marker
// with a given prefix ever exists. This keeps the `onlyIf` short-circuit honest: reverting an input
// (e.g. undoing a shim edit) leaves no stale marker to wrongly skip the re-link.
fun writeMarker(marker: File, prefix: String, content: String) {
    marker.parentFile?.listFiles { f -> f.name.startsWith(prefix) && f != marker }?.forEach { it.delete() }
    marker.writeText(content)
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
    // The .a build and the .so re-link are keyed on SEPARATE markers so a shim edit re-links only the
    // .so (see RFC §10). Both fold in the commit + image (toolchain) hash; the archive marker adds the
    // archive recipe, and the ffi marker adds the archive recipe AND the shim hash (a new commit /
    // toolchain / archive-recipe rebuilds the .a AND forces a relink; a shim edit forces only a relink).
    val archiveMarker = outDir.resolve("lib/.archives-$boringsslCommit-p$patchSetHash-$imageHash-$archiveRecipeHash")
    val ffiMarker = outDir.resolve("lib/.ffi-$boringsslCommit-p$patchSetHash-$imageHash-$archiveRecipeHash-$shimHash")
    val crossing = t.crossCc != null && System.getProperty("os.arch") != "aarch64"
    val libDir = outDir.resolve("lib")

    // ── STAGE 1 (expensive): compile all of BoringSSL → libssl.a + libcrypto.a + headers ──
    val buildArchives =
        tasks.register("buildBoringSslArchives$cap") {
            group = "boringssl"
            description = "Compile BoringSSL static libs (libssl.a + libcrypto.a) + headers for ${t.id}."
            dependsOn(fetchBoringSsl)
            inputs.property("commit", boringsslCommit)
            inputs.property("image", imageHash)
            inputs.property("archiveRecipe", archiveRecipeHash)
            inputs.property("container", dockerUsable)
            outputs.dir(outDir)
            outputs.cacheIf { true }
            onlyIf { !archiveMarker.exists() || !libDir.resolve("libcrypto.a").exists() }
            doLast {
                val srcDir = boringsslWork.get().dir("src").asFile
                val cFlags = t.extraCFlags.joinToString(" ")

                if (dockerUsable) {
                    // ── CANONICAL: build inside manylinux2014 (glibc 2.17), native per-arch (RFC §8a) ──
                    val tag = imageTag(t.dockerArch)
                    ensureImage(t.dockerArch, t.dockerPlatform, tag)
                    val uid = execCapture("id", "-u")
                    val gid = execCapture("id", "-g")
                    logger.lifecycle("Building BoringSSL archives for ${t.id} in $tag (glibc 2.17)...")
                    runOrThrow(
                        "docker", "run", "--rm", "--platform", t.dockerPlatform,
                        "--user", "$uid:$gid", "-e", "HOME=/tmp", "-e", "CFLAGS=$cFlags", "-e", "CC=cc",
                        "-v", "${rootDir.absolutePath}:/work",
                        tag, "/work/boringssl-build/docker/build-archives.sh", t.id,
                        dir = projectDir, what = "container archive build ${t.id}",
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
                    libDir.mkdirs()
                    val ssl = cmakeBuildDir.walkTopDown().firstOrNull { it.name == "libssl.a" }
                        ?: throw GradleException("libssl.a not found for ${t.id}")
                    val crypto = cmakeBuildDir.walkTopDown().firstOrNull { it.name == "libcrypto.a" }
                        ?: throw GradleException("libcrypto.a not found for ${t.id}")
                    ssl.copyTo(libDir.resolve("libssl.a"), overwrite = true)
                    crypto.copyTo(libDir.resolve("libcrypto.a"), overwrite = true)
                    val compatC = cmakeBuildDir.resolve("isoc23_compat.c").apply { writeText(isoC23CompatSource) }
                    val compatO = cmakeBuildDir.resolve("isoc23_compat.o")
                    val cc = t.crossCc ?: "cc"
                    runOrThrow(cc, "-c", "-fPIC", "-O2", compatC.absolutePath, "-o", compatO.absolutePath,
                        dir = cmakeBuildDir, what = "compile isoc23 compat ${t.id}")
                    runOrThrow("ar", "r", libDir.resolve("libcrypto.a").absolutePath, compatO.absolutePath,
                        dir = cmakeBuildDir, what = "ar-merge isoc23 compat ${t.id}")
                    val inc = listOf(srcDir.resolve("src/include"), srcDir.resolve("include")).first { it.exists() }
                    inc.copyRecursively(outDir.resolve("include"), overwrite = true)
                }

                require(libDir.resolve("libcrypto.a").exists()) { "libcrypto.a missing after build for ${t.id}" }
                require(libDir.resolve("libssl.a").exists()) { "libssl.a missing after build for ${t.id}" }
                // The archives changed → any previously-linked .so is stale; drop ALL ffi markers so
                // the ffi task re-links even if its own inputs (the shim) are unchanged.
                libDir.listFiles { f -> f.name.startsWith(".ffi-") }?.forEach { it.delete() }
                writeMarker(archiveMarker, ".archives-", "google/boringssl @ $boringsslCommit (API v$boringsslApiVersion) ${t.id} archives [${if (dockerUsable) "container:manylinux2014" else "host"}]\n")
                logger.lifecycle("BoringSSL archives built for ${t.id} → $libDir")
            }
        }

    // ── STAGE 2 (cheap): link the curated shim + the static archives → libboringsslffi.so ──
    // Keyed on the shim hash, NOT the archive recipe: editing the shim re-links only the .so and does
    // NOT recompile BoringSSL. Depends on the archives so the .a exist first.
    val linkFfi =
        tasks.register("linkBoringSslFfi$cap") {
            group = "boringssl"
            description = "Link the FFI shim + BoringSSL archives into libboringsslffi.so for ${t.id}."
            dependsOn(buildArchives)
            inputs.property("commit", boringsslCommit)
            inputs.property("image", imageHash)
            inputs.property("shim", shimHash)
            inputs.property("container", dockerUsable)
            outputs.file(libDir.resolve("libboringsslffi.so"))
            onlyIf { !ffiMarker.exists() || !libDir.resolve("libboringsslffi.so").exists() }
            doLast {
                require(libDir.resolve("libcrypto.a").exists()) { "libcrypto.a missing — run buildBoringSslArchives$cap first" }

                if (dockerUsable) {
                    val tag = imageTag(t.dockerArch)
                    ensureImage(t.dockerArch, t.dockerPlatform, tag)
                    val uid = execCapture("id", "-u")
                    val gid = execCapture("id", "-g")
                    logger.lifecycle("Linking libboringsslffi.so for ${t.id} in $tag (glibc 2.17)...")
                    runOrThrow(
                        "docker", "run", "--rm", "--platform", t.dockerPlatform,
                        "--user", "$uid:$gid", "-e", "HOME=/tmp", "-e", "CC=cc",
                        "-v", "${rootDir.absolutePath}:/work",
                        tag, "/work/boringssl-build/docker/link-ffi.sh", t.id,
                        dir = projectDir, what = "container ffi link ${t.id}",
                    )
                } else {
                    // libboringsslffi.so (host fallback; mirrors docker/link-ffi.sh). Only the shim's
                    // boringssl_ffi_* symbols are exported; BoringSSL stays hidden (D3). NOT
                    // glibc-floor-safe on a modern host — dev iteration only, same caveat as the .a.
                    val shimDir = projectDir.resolve("docker")
                    val ffiCc = t.crossCc ?: "cc"
                    val linkWork = boringsslWork.get().dir("link-${t.id}").asFile
                        .apply { deleteRecursively(); mkdirs() }
                    val shimO = linkWork.resolve("boringssl_shim.o")
                    runOrThrow(
                        ffiCc, "-c", "-fPIC", "-O2", "-fvisibility=hidden",
                        "-I", outDir.resolve("include").absolutePath,
                        shimDir.resolve("boringssl_shim.c").absolutePath, "-o", shimO.absolutePath,
                        dir = linkWork, what = "compile ffi shim ${t.id}",
                    )
                    runOrThrow(
                        ffiCc, "-shared", "-fPIC", "-o", libDir.resolve("libboringsslffi.so").absolutePath,
                        shimO.absolutePath,
                        libDir.resolve("libssl.a").absolutePath, libDir.resolve("libcrypto.a").absolutePath,
                        "-Wl,--exclude-libs,ALL",
                        "-Wl,--version-script=${shimDir.resolve("boringssl_ffi_exports.map").absolutePath}",
                        "-Wl,--gc-sections", "-lpthread",
                        dir = linkWork, what = "link libboringsslffi.so ${t.id}",
                    )
                }

                require(libDir.resolve("libboringsslffi.so").exists()) { "libboringsslffi.so missing after link for ${t.id}" }
                writeMarker(ffiMarker, ".ffi-", "libboringsslffi.so @ $boringsslCommit ${t.id} shim=$shimHash [${if (dockerUsable) "container:manylinux2014" else "host"}]\n")
                logger.lifecycle("libboringsslffi.so linked for ${t.id} → $libDir")
            }
        }

    // Umbrella: the full per-triple build (archives + .so). Kept as `buildBoringSsl<Triple>` so the
    // aggregate tasks, testsuite, and docs keep a single stable entry point.
    val build =
        tasks.register("buildBoringSsl$cap") {
            group = "boringssl"
            description = "Build BoringSSL archives + libboringsslffi.so for ${t.id}."
            dependsOn(buildArchives, linkFfi)
        }

    val verify =
        tasks.register("checkGlibcFloor$cap") {
            group = "boringssl"
            description = "Fail if ${t.id}'s archives need a glibc newer than the K/N sysroot floor (RFC §8)."
            dependsOn(buildArchives)
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
                    "--exclude=.archives-*", "--exclude=.ffi-*",
                    "-cf", tarFile.absolutePath, "-C", outDir.absolutePath, "include", "lib",
                    dir = dist, what = "tar ${t.id}",
                )
                runOrThrow("gzip", "-nf", tarFile.absolutePath, dir = dist, what = "gzip ${t.id}")

                // Emit the FFM shared lib as its own checksummed artifact (directive #4: every artifact
                // carries a sha256). CI uploads these per-runner; the release job gathers all platforms'
                // .so into the single cross-platform MRJAR.
                val soSrc = outDir.resolve("lib/libboringsslffi.so")
                if (soSrc.exists()) {
                    val smokeSo = dist.resolve("libboringsslffi-$bundleVersion-${t.id}.so")
                    soSrc.copyTo(smokeSo, overwrite = true)
                    dist.resolve("${smokeSo.name}.sha256").writeText("${sha256Of(smokeSo)}  ${smokeSo.name}\n")
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
                      "patchSet": "$patchSetHash",
                      "patches": [${patchSetNames.joinToString(", ") { "\"$it\"" }}],
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

// ── Android per-ABI static archives (RFC §5 / §7) ───────────────────────────────────────────────
// Unlike Linux, Android cross-compiles with the NDK (Bionic, not glibc) — so there is NO manylinux
// container, NO __isoc23 compat shim, and NO libboringsslffi.so (Android consumers use JNI + a prefab
// AAR of the static .a, not FFM). ANDROID_PLATFORM=android-$boringsslAndroidApi is the floor, so the
// NDK toolchain is itself the portability control (the analog of the glibc-floor container). Output is
// per-ABI static libssl.a/libcrypto.a + headers; :boringssl-android packages them into the prefab AAR.
data class AndroidAbi(
    val abi: String, // NDK ABI id, e.g. arm64-v8a — also the libs/ output subdir
    val suffix: String, // task-name suffix, e.g. Arm64V8a
    val clangTriple: String, // NDK unified-clang target, e.g. aarch64-linux-android
    // How CI RUNS the on-device KAT for this ABI, which sets the KAT's linkage:
    //   • x86_64  → dynamic PIE, run on a KVM-accelerated Android emulator (full Bionic runtime).
    //   • arm64-v8a → STATIC, run under qemu-aarch64 on the x86_64 runner (no arm64 runner / KVM
    //     needed; a static binary needs no Android loader so qemu-user can execute it directly).
    val katStatic: Boolean,
)

val androidAbis =
    listOf(
        AndroidAbi("arm64-v8a", "Arm64V8a", "aarch64-linux-android", katStatic = true),
        AndroidAbi("x86_64", "X8664", "x86_64-linux-android", katStatic = false),
        // armeabi-v7a (32-bit ARM) deliberately dropped — RFC §5 Rule D / D7.
    )

// ── §5 per-ABI size budget ───────────────────────────────────────────────────────────────────────
// The budget is on "the static subset the consumer PULLS" (post --gc-sections), NOT the raw ~4 MB
// archive. checkAndroidSize links a representative DTLS+crypto surface — what webrtc-dtls (DTLS) and
// quiche (TLS+AEAD+HKDF) actually reference — with --gc-sections, strips, and gates the result. At the
// canonical pin this lands ~1.1 MB/ABI, comfortably under 2.5 MiB.
val androidAbiBudgetBytes = 2_621_440L // 2.5 MiB (RFC §5)
val androidBudgetProbe =
    """
    #include <openssl/ssl.h>
    #include <openssl/sha.h>
    #include <openssl/hmac.h>
    #include <openssl/hkdf.h>
    #include <openssl/curve25519.h>
    #include <openssl/aead.h>
    #include <openssl/rand.h>
    #include <openssl/ec.h>
    #include <openssl/evp.h>
    #include <openssl/x509.h>
    /* A broad, realistic DTLS/QUIC-ish surface so --gc-sections keeps a representative consumer
       footprint (webrtc-dtls uses DTLS; quiche uses TLS+AEAD+HKDF+crypto). */
    void boringssl_budget_probe(void) {
        SSL_CTX *c = SSL_CTX_new(DTLS_method()); SSL *s = SSL_new(c);
        SSL_do_handshake(s); SSL_read(s, 0, 0); SSL_write(s, 0, 0); SSL_shutdown(s);
        SSL_CTX_new(TLS_method());
        unsigned char h[64]; SHA256(0, 0, h); SHA512(0, 0, h);
        unsigned int hl; HMAC(EVP_sha256(), 0, 0, 0, 0, h, &hl);
        HKDF(h, 0, EVP_sha256(), 0, 0, 0, 0, 0, 0);
        uint8_t pub[32], priv[32]; X25519_keypair(pub, priv); X25519(h, priv, pub);
        EVP_AEAD_CTX_new(EVP_aead_chacha20_poly1305(), h, 32, 0);
        (void)EVP_aead_aes_128_gcm();
        RAND_bytes(h, 32);
        (void)EC_KEY_new_by_curve_name(NID_X9_62_prime256v1);
        X509 *x = X509_new(); X509_verify(x, 0);
    }
    """.trimIndent() + "\n"

// Resolve the NDK: ANDROID_NDK_HOME/ROOT, else the highest-versioned ndk/<ver> under the SDK. Returns
// (ndkDir, version) — version is the directory name (e.g. 28.2.13676358), folded into the build marker
// + provenance so an NDK bump rebuilds. Null when no NDK is installed (tasks fail with a clear message).
fun resolveNdk(): Pair<File, String>? {
    val candidates =
        buildList {
            listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT").forEach { e ->
                System.getenv(e)?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
            }
            val sdk =
                (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let { File(it) }
                    ?: File(System.getProperty("user.home"), "Android/Sdk").takeIf { it.isDirectory }
            sdk?.resolve("ndk")?.listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }?.let { add(it) }
        }
    val dir = candidates.firstOrNull { File(it, "build/cmake/android.toolchain.cmake").exists() } ?: return null
    return dir to dir.name
}

// The host tag under toolchains/llvm/prebuilt (linux-x86_64 on this host); picked by listing so a
// darwin build host also resolves. clang/llvm-nm live in its bin/.
fun ndkPrebuilt(ndkDir: File): File =
    ndkDir.resolve("toolchains/llvm/prebuilt").listFiles { f -> f.isDirectory }?.firstOrNull()
        ?: throw GradleException("NDK prebuilt toolchain dir not found under $ndkDir")

fun registerAndroidAbi(a: AndroidAbi): Triple<TaskProvider<Task>, List<TaskProvider<Task>>, TaskProvider<Task>> {
    val outDir = projectDir.resolve("libs/boringssl/android/${a.abi}")
    val libDir = outDir.resolve("lib")

    val build =
        tasks.register("buildBoringSslAndroid${a.suffix}") {
            group = "boringssl"
            description = "Cross-compile BoringSSL static libs for Android ${a.abi} (NDK, API $boringsslAndroidApi)."
            dependsOn(fetchBoringSsl)
            // The ABI MUST be a declared input: without it both ABI tasks share a build-cache key
            // (identical commit+api) and Gradle restores one ABI's archives into the other's output dir.
            inputs.property("abi", a.abi)
            inputs.property("commit", boringsslCommit)
            inputs.property("api", boringsslAndroidApi)
            // Lazy provider (evaluated at execution, not config) so an NDK bump invalidates the cache
            // without breaking configuration on a machine that has no NDK (returns "none" there).
            inputs.property("ndk", providers.provider { resolveNdk()?.second ?: "none" })
            outputs.dir(outDir)
            outputs.cacheIf { true }
            doLast {
                val (ndkDir, ndkVersion) =
                    resolveNdk() ?: throw GradleException(
                        "Android build needs an NDK: set ANDROID_NDK_HOME or install one under \$ANDROID_HOME/ndk. " +
                            "CI provides it via the setup-android action.",
                    )
                val marker = libDir.resolve(".android-$boringsslCommit-p$patchSetHash-ndk$ndkVersion-api$boringsslAndroidApi")
                if (marker.exists() && libDir.resolve("libcrypto.a").exists()) {
                    logger.lifecycle("Android ${a.abi}: up-to-date ($marker) — skipping.")
                    return@doLast
                }
                val srcDir = boringsslWork.get().dir("src").asFile
                val cmakeBuildDir = boringsslWork.get().dir("build-android-${a.abi}").asFile
                    .apply { deleteRecursively(); mkdirs() }
                // Lean flags so the consumer's --gc-sections drops unreferenced code toward the §5
                // ≤2.5 MB/ABI budget (measured on the linked subset in :boringssl-android, not here).
                val leanFlags = "-fvisibility=hidden -ffunction-sections -fdata-sections"
                logger.lifecycle("Cross-compiling BoringSSL for Android ${a.abi} (NDK $ndkVersion, API $boringsslAndroidApi)...")
                runOrThrow(
                    "cmake",
                    "-DCMAKE_TOOLCHAIN_FILE=${ndkDir.resolve("build/cmake/android.toolchain.cmake").absolutePath}",
                    "-DANDROID_ABI=${a.abi}", "-DANDROID_PLATFORM=android-$boringsslAndroidApi",
                    "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF",
                    "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                    "-DCMAKE_C_FLAGS=$leanFlags", "-DCMAKE_CXX_FLAGS=$leanFlags",
                    "-G", "Unix Makefiles", srcDir.absolutePath,
                    dir = cmakeBuildDir, what = "cmake configure android ${a.abi}",
                )
                runOrThrow("make", "-j${Runtime.getRuntime().availableProcessors()}", "ssl", "crypto",
                    dir = cmakeBuildDir, what = "make android ${a.abi}")
                libDir.mkdirs()
                val ssl = cmakeBuildDir.walkTopDown().firstOrNull { it.name == "libssl.a" }
                    ?: throw GradleException("libssl.a not found for android ${a.abi}")
                val crypto = cmakeBuildDir.walkTopDown().firstOrNull { it.name == "libcrypto.a" }
                    ?: throw GradleException("libcrypto.a not found for android ${a.abi}")
                ssl.copyTo(libDir.resolve("libssl.a"), overwrite = true)
                crypto.copyTo(libDir.resolve("libcrypto.a"), overwrite = true)
                val inc = listOf(srcDir.resolve("src/include"), srcDir.resolve("include")).first { it.exists() }
                inc.copyRecursively(outDir.resolve("include"), overwrite = true)

                require(libDir.resolve("libcrypto.a").exists()) { "libcrypto.a missing after android build ${a.abi}" }
                // Record raw archive sizes (the §5 budget is enforced on the linked subset in the AAR step).
                listOf("libcrypto.a", "libssl.a").forEach {
                    val f = libDir.resolve(it)
                    logger.lifecycle("  · android/${a.abi}/$it: ${f.length()} bytes (${f.length() / 1024} KiB)")
                }
                writeMarker(marker, ".android-", "google/boringssl @ $boringsslCommit ${a.abi} [NDK $ndkVersion, API $boringsslAndroidApi]\n")
                logger.lifecycle("BoringSSL built for android ${a.abi} → $libDir")
            }
        }

    // Android link-smoke: force-load the whole archive into a .so with --no-undefined, targeting the
    // NDK sysroot at API $boringsslAndroidApi. ANY BoringSSL object referencing a symbol absent from
    // Bionic-$boringsslAndroidApi fails the link — the Android analog of checkGlibcFloor. Also asserts
    // the archive exports a PLAIN SHA256_Init (D3 one-unprefixed-copy).
    val smokeSo = boringsslWork.get().dir("smoke-android-${a.abi}").file("libprobe.so").asFile
    val smoke =
        tasks.register("linkSmokeAndroid${a.suffix}") {
            group = "boringssl"
            description = "NDK link-smoke: whole-archive link + D3 plain-symbol check for Android ${a.abi}."
            dependsOn(build)
            // Up-to-date when the archives are unchanged and the probe .so is present → `check` doesn't
            // re-link the ~10 MB whole-archive every run.
            inputs.files(libDir.resolve("libssl.a"), libDir.resolve("libcrypto.a"))
            outputs.file(smokeSo)
            doLast {
                val (ndkDir, _) = resolveNdk() ?: throw GradleException("Android link-smoke needs an NDK (see buildBoringSslAndroid${a.suffix}).")
                val bin = ndkPrebuilt(ndkDir).resolve("bin")
                val clang = bin.resolve("clang")
                val llvmNm = bin.resolve("llvm-nm")
                val work = smokeSo.parentFile.apply { mkdirs() }
                val probe = work.resolve("probe.c")
                probe.writeText(
                    """
                    #include <openssl/sha.h>
                    #include <stddef.h>
                    /* Reference a real BoringSSL entry point so the archive is genuinely exercised. */
                    unsigned char boringssl_android_probe(const unsigned char *d, size_t n, unsigned char *out) {
                        SHA256(d, n, out);
                        return out[0];
                    }
                    """.trimIndent() + "\n",
                )
                runOrThrow(
                    clang.absolutePath, "--target=${a.clangTriple}$boringsslAndroidApi",
                    "-fPIC", "-shared", "-o", smokeSo.absolutePath,
                    "-I", outDir.resolve("include").absolutePath, probe.absolutePath,
                    "-Wl,--no-undefined",
                    "-Wl,--whole-archive", libDir.resolve("libssl.a").absolutePath, libDir.resolve("libcrypto.a").absolutePath,
                    "-Wl,--no-whole-archive",
                    dir = work, what = "android link-smoke ${a.abi}",
                )
                // D3: the static archive must export SHA256_Init un-mangled (a prefix build renames it).
                val nmProc = ProcessBuilder(llvmNm.absolutePath, libDir.resolve("libcrypto.a").absolutePath)
                    .redirectErrorStream(true).start()
                val nmOut = nmProc.inputStream.bufferedReader().readText(); nmProc.waitFor()
                if (!Regex("(^| )T _?SHA256_Init$", RegexOption.MULTILINE).containsMatchIn(nmOut)) {
                    throw GradleException("android ${a.abi}: libcrypto.a does not export a plain SHA256_Init — prefix-mangled or unexpected symbol set.")
                }
                logger.lifecycle("android ${a.abi} link-smoke OK: whole-archive links against Bionic-$boringsslAndroidApi (${smokeSo.length() / 1024} KiB) + plain SHA256_Init present.")
            }
        }

    // §5 budget gate: link the representative DTLS+crypto probe with --gc-sections, strip, and fail if
    // the linked subset exceeds androidAbiBudgetBytes. Models the footprint a real consumer pulls.
    val budgetSo = boringsslWork.get().dir("budget-android-${a.abi}").file("libbudget.so").asFile
    val size =
        tasks.register("checkAndroidSize${a.suffix}") {
            group = "boringssl"
            description = "Fail if Android ${a.abi}'s representative linked subset exceeds the §5 ${androidAbiBudgetBytes / 1024} KiB budget."
            dependsOn(build)
            inputs.files(libDir.resolve("libssl.a"), libDir.resolve("libcrypto.a"))
            outputs.file(budgetSo)
            doLast {
                val (ndkDir, _) = resolveNdk() ?: throw GradleException("Android size check needs an NDK (see buildBoringSslAndroid${a.suffix}).")
                val bin = ndkPrebuilt(ndkDir).resolve("bin")
                val work = budgetSo.parentFile.apply { mkdirs() }
                val probe = work.resolve("probe.c").apply { writeText(androidBudgetProbe) }
                runOrThrow(
                    bin.resolve("clang").absolutePath, "--target=${a.clangTriple}$boringsslAndroidApi",
                    "-fPIC", "-O2", "-ffunction-sections", "-fdata-sections",
                    "-I", outDir.resolve("include").absolutePath, "-shared", "-o", budgetSo.absolutePath,
                    probe.absolutePath, libDir.resolve("libssl.a").absolutePath, libDir.resolve("libcrypto.a").absolutePath,
                    "-Wl,--gc-sections", "-Wl,--no-undefined",
                    dir = work, what = "android budget link ${a.abi}",
                )
                runOrThrow(bin.resolve("llvm-strip").absolutePath, "--strip-unneeded", budgetSo.absolutePath,
                    dir = work, what = "android budget strip ${a.abi}")
                val bytes = budgetSo.length()
                logger.lifecycle("android ${a.abi} representative linked subset: $bytes bytes (${bytes / 1024} KiB) / budget ${androidAbiBudgetBytes / 1024} KiB.")
                if (bytes > androidAbiBudgetBytes) {
                    throw GradleException(
                        "android ${a.abi} exceeds the §5 size budget: ${bytes / 1024} KiB > ${androidAbiBudgetBytes / 1024} KiB " +
                            "(representative DTLS+crypto subset, --gc-sections + strip).",
                    )
                }
            }
        }

    // On-device KAT executable: NDK-compile android_kat.c (statically linked against this ABI's .a) into
    // a PIE. It is not RUN here (needs an emulator of the target arch) — build-android.yaml pushes it to
    // an Android emulator and runs it, the runtime parity check vs :boringssl-jvm:jvm21Test.
    val katSrc = projectDir.resolve("scripts/android_kat.c")
    val katExe = boringsslWork.get().dir("android-kat/${a.abi}").file("boringssl_kat").asFile
    val kat =
        tasks.register("buildAndroidKat${a.suffix}") {
            group = "boringssl"
            description = "Build the on-device BoringSSL KAT executable for Android ${a.abi} (run ${if (a.katStatic) "under qemu-aarch64" else "on the emulator"} in CI)."
            dependsOn(build)
            inputs.file(katSrc)
            inputs.property("static", a.katStatic)
            inputs.files(libDir.resolve("libssl.a"), libDir.resolve("libcrypto.a"))
            outputs.file(katExe)
            doLast {
                val (ndkDir, _) = resolveNdk() ?: throw GradleException("Android KAT build needs an NDK (see buildBoringSslAndroid${a.suffix}).")
                val clang = ndkPrebuilt(ndkDir).resolve("bin/clang")
                katExe.parentFile.mkdirs()
                // Static (arm64/qemu) needs no Android loader; dynamic PIE (x86_64/emulator) is the
                // realistic on-device form. -static implies non-PIE, so the two are mutually exclusive.
                val linkFlags = if (a.katStatic) listOf("-static") else listOf("-fPIE", "-pie")
                runOrThrow(
                    *(listOf(clang.absolutePath, "--target=${a.clangTriple}$boringsslAndroidApi", "-O2") +
                        linkFlags +
                        listOf(
                            "-o", katExe.absolutePath,
                            "-I", outDir.resolve("include").absolutePath, katSrc.absolutePath,
                            libDir.resolve("libssl.a").absolutePath, libDir.resolve("libcrypto.a").absolutePath,
                            "-Wl,--no-undefined",
                        )).toTypedArray(),
                    dir = katExe.parentFile, what = "android KAT link ${a.abi}",
                )
                logger.lifecycle("android ${a.abi} KAT executable (${if (a.katStatic) "static" else "dynamic"}) → $katExe (${katExe.length() / 1024} KiB)")
            }
        }
    return Triple(build, listOf(smoke, size), kat)
}

val androidBuilds = androidAbis.associate { it.abi to registerAndroidAbi(it) }

tasks.register("buildBoringSslAndroid") {
    group = "boringssl"
    description = "Cross-compile BoringSSL static libs for all wired Android ABIs (arm64-v8a, x86_64)."
    dependsOn(androidBuilds.values.map { it.first })
}

tasks.register("checkBoringSslAndroid") {
    group = "boringssl"
    description = "NDK link-smoke + §5 size-budget gate for every wired Android ABI."
    dependsOn(androidBuilds.values.flatMap { it.second })
}

// Per-ABI on-device KAT executables (built here; RUN on the emulator in build-android.yaml). Also
// addressable per-ABI as buildAndroidKat<Abi> so CI builds only the arch it will boot.
tasks.register("buildAndroidKat") {
    group = "boringssl"
    description = "Build the on-device BoringSSL KAT executable for every wired Android ABI."
    dependsOn(androidBuilds.values.map { it.third })
}

// ── Apple per-SDK static archives + FFM dylib (RFC §7 / D2 / D4) ─────────────────────────────────
// Apple cross-compiles NATIVELY on a macOS runner with Xcode's clang + per-SDK sysroots — no docker,
// no glibc floor: on Apple the DEPLOYMENT TARGET is the portability floor (K/N links its consumer
// binaries against it, the analog of the Linux glibc floor / the Android minSdk). Each triple resolves
// its SDK via `xcrun --sdk <sdk> --show-sdk-path` and cross-compiles with CMAKE_OSX_SYSROOT /
// _ARCHITECTURES / _DEPLOYMENT_TARGET (+ CMAKE_SYSTEM_NAME for the non-macOS OSes; native Darwin omits
// it). macOS additionally links the FFM libboringsslffi.dylib for the boringssl-jvm MRJAR; the other
// triples ship only the K/N static .a (Apple consumers cinterop, not FFM — D2).
//
// tvOS/watchOS BoringSSL cross-compile is UNPROVEN (D4): authored + link-smoke-gated here, but marked
// compile-faithful (proven=false) until a real runner proves the cmake path. iosSimulatorArm64's
// perlasm objects tag correctly as platform=iOS-simulator under cmake + this pin (verified), so no
// OPENSSL_NO_ASM workaround is needed for the arm64 simulator.
data class AppleTriple(
    val id: String, // konan id / tarball+dir suffix, e.g. macosArm64
    val sdk: String, // xcrun --sdk arg (macosx | iphoneos | iphonesimulator | appletv* | watch*)
    val arch: String, // CMAKE_OSX_ARCHITECTURES (x86_64 | arm64)
    val systemName: String?, // CMAKE_SYSTEM_NAME for iOS/tvOS/watchOS; null → native macOS (Darwin)
    val minVersion: String, // CMAKE_OSX_DEPLOYMENT_TARGET — the Apple "floor"
    val clangTarget: String, // -target triple for the link-smoke / dylib (carries min + -simulator tag)
    val ffiOsArch: String?, // MRJAR <os>-<arch> label iff this triple ships an FFM dylib (macOS only)
    val proven: Boolean, // false → D4 spike triple (tvOS/watchOS): compile-faithful until proven
)

val appleTriples =
    listOf(
        // ── macOS — runtime-validated on the macOS runner; the only triples that ship an FFM dylib ──
        AppleTriple("macosX64", "macosx", "x86_64", null, "11.0", "x86_64-apple-macos11.0", "macos-x86_64", true),
        AppleTriple("macosArm64", "macosx", "arm64", null, "11.0", "arm64-apple-macos11.0", "macos-aarch64", true),
        // ── iOS device + simulators — device is compile-faithful; simulators run on the macOS runner ──
        AppleTriple("iosArm64", "iphoneos", "arm64", "iOS", "12.0", "arm64-apple-ios12.0", null, true),
        AppleTriple("iosSimulatorArm64", "iphonesimulator", "arm64", "iOS", "12.0", "arm64-apple-ios12.0-simulator", null, true),
        AppleTriple("iosX64", "iphonesimulator", "x86_64", "iOS", "12.0", "x86_64-apple-ios12.0-simulator", null, true),
        // ── tvOS + watchOS — D4 spike: authored + link-smoke-gated, compile-faithful until proven ──
        AppleTriple("tvosArm64", "appletvos", "arm64", "tvOS", "12.0", "arm64-apple-tvos12.0", null, false),
        AppleTriple("tvosSimulatorArm64", "appletvsimulator", "arm64", "tvOS", "12.0", "arm64-apple-tvos12.0-simulator", null, false),
        AppleTriple("tvosX64", "appletvsimulator", "x86_64", "tvOS", "12.0", "x86_64-apple-tvos12.0-simulator", null, false),
        AppleTriple("watchosSimulatorArm64", "watchsimulator", "arm64", "watchOS", "7.0", "arm64-apple-watchos7.0-simulator", null, false),
        AppleTriple("watchosX64", "watchsimulator", "x86_64", "watchOS", "7.0", "x86_64-apple-watchos7.0-simulator", null, false),
    )

val isMacHost = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

fun sha12(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

// Xcode/clang toolchain id, folded into the build marker + provenance so an Xcode bump rebuilds. Lazy
// (execution-time) so configuration never shells out; "none" off a mac keeps config working there.
fun appleToolchainId(): String =
    try {
        execCapture("xcrun", "clang", "--version").lineSequence().firstOrNull()?.let { sha12(it) } ?: "none"
    } catch (e: Exception) { "none" }

fun xcrunSdkPath(sdk: String): String =
    execCapture("xcrun", "--sdk", sdk, "--show-sdk-path").also {
        if (it.isBlank()) throw GradleException("xcrun --sdk $sdk --show-sdk-path returned nothing — is the $sdk SDK installed?")
    }

fun registerAppleTriple(t: AppleTriple): Triple<TaskProvider<Task>, TaskProvider<Task>, TaskProvider<Task>> {
    val cap = t.id.replaceFirstChar { it.uppercase() }
    val outDir = projectDir.resolve("libs/boringssl/${t.id}")
    val libDir = outDir.resolve("lib")

    // ── STAGE 1: cmake cross-compile → libssl.a + libcrypto.a + headers ──
    val build =
        tasks.register("buildBoringSslApple$cap") {
            group = "boringssl"
            description = "Cross-compile BoringSSL static libs (libssl.a + libcrypto.a) for ${t.id} (Xcode/${t.sdk}, min ${t.minVersion})."
            dependsOn(fetchBoringSsl)
            // Same inputs discipline as Android: the konan id MUST be an input so two Apple triples never
            // share a build-cache key (identical commit) and restore each other's archives.
            inputs.property("triple", t.id)
            inputs.property("commit", boringsslCommit)
            inputs.property("min", t.minVersion)
            inputs.property("xcode", providers.provider { appleToolchainId() })
            outputs.dir(outDir)
            outputs.cacheIf { true }
            doLast {
                if (!isMacHost) {
                    throw GradleException("Apple builds require a macOS host with Xcode — CI runs this lane on macos-14.")
                }
                val toolId = appleToolchainId()
                val marker = libDir.resolve(".apple-$boringsslCommit-p$patchSetHash-$toolId-min${t.minVersion}")
                if (marker.exists() && libDir.resolve("libcrypto.a").exists()) {
                    logger.lifecycle("Apple ${t.id}: up-to-date ($marker) — skipping.")
                    return@doLast
                }
                val srcDir = boringsslWork.get().dir("src").asFile
                val sysroot = xcrunSdkPath(t.sdk)
                val cmakeBuildDir = boringsslWork.get().dir("build-apple-${t.id}").asFile
                    .apply { deleteRecursively(); mkdirs() }
                val cmakeArgs =
                    mutableListOf(
                        "cmake", "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF",
                        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                        "-DCMAKE_OSX_SYSROOT=$sysroot",
                        "-DCMAKE_OSX_ARCHITECTURES=${t.arch}",
                        "-DCMAKE_OSX_DEPLOYMENT_TARGET=${t.minVersion}",
                    )
                // Non-macOS OSes need CMAKE_SYSTEM_NAME so cmake enters cross mode (and, with a
                // *simulator* sysroot, tags objects for the simulator). macOS is the native host → omit.
                if (t.systemName != null) cmakeArgs.add("-DCMAKE_SYSTEM_NAME=${t.systemName}")
                cmakeArgs.addAll(listOf("-G", "Unix Makefiles", srcDir.absolutePath))
                logger.lifecycle("Cross-compiling BoringSSL for ${t.id} (${t.sdk}, ${t.arch}, min ${t.minVersion})...")
                runOrThrow(*cmakeArgs.toTypedArray(), dir = cmakeBuildDir, what = "cmake configure apple ${t.id}")
                runOrThrow("make", "-j${Runtime.getRuntime().availableProcessors()}", "ssl", "crypto",
                    dir = cmakeBuildDir, what = "make apple ${t.id}")
                libDir.mkdirs()
                val ssl = cmakeBuildDir.walkTopDown().firstOrNull { it.name == "libssl.a" }
                    ?: throw GradleException("libssl.a not found for ${t.id}")
                val crypto = cmakeBuildDir.walkTopDown().firstOrNull { it.name == "libcrypto.a" }
                    ?: throw GradleException("libcrypto.a not found for ${t.id}")
                ssl.copyTo(libDir.resolve("libssl.a"), overwrite = true)
                crypto.copyTo(libDir.resolve("libcrypto.a"), overwrite = true)
                val inc = listOf(srcDir.resolve("src/include"), srcDir.resolve("include")).first { it.exists() }
                inc.copyRecursively(outDir.resolve("include"), overwrite = true)

                require(libDir.resolve("libcrypto.a").exists()) { "libcrypto.a missing after apple build ${t.id}" }
                require(libDir.resolve("libssl.a").exists()) { "libssl.a missing after apple build ${t.id}" }
                // Archives changed → any previously-linked dylib is stale; drop the ffi marker so the
                // dylib re-links even if its own inputs (the shim) are unchanged.
                libDir.listFiles { f -> f.name.startsWith(".ffi-") }?.forEach { it.delete() }
                listOf("libcrypto.a", "libssl.a").forEach {
                    val f = libDir.resolve(it)
                    logger.lifecycle("  · ${t.id}/$it: ${f.length()} bytes (${f.length() / 1024} KiB)")
                }
                writeMarker(marker, ".apple-", "google/boringssl @ $boringsslCommit ${t.id} [Xcode $toolId, ${t.sdk}, min ${t.minVersion}]\n")
                logger.lifecycle("BoringSSL built for ${t.id} → $libDir")
            }
        }

    // ── STAGE 2 (macOS only): link the curated shim + archives → libboringsslffi.dylib ──
    // Mach-O analog of link-ffi.sh: clang -dynamiclib, -fvisibility=hidden, and an EXPORTED-SYMBOLS
    // LIST (one glob per line, WITH the Mach-O leading underscore) so ONLY the shim's boringssl_ffi_*
    // symbols leave the dylib — every BoringSSL symbol stays hidden (D3 one-unprefixed-copy). The list
    // is generated from the same curated surface as the GNU version-script (keyed on shimHash).
    val ffiMarker = libDir.resolve(".ffi-$boringsslCommit-p$patchSetHash-$shimHash")
    val linkFfi =
        tasks.register("linkBoringSslFfiApple$cap") {
            group = "boringssl"
            description =
                if (t.ffiOsArch != null) {
                    "Link the FFM shim + BoringSSL archives into libboringsslffi.dylib for ${t.id}."
                } else {
                    "No-op: ${t.id} ships no FFM dylib (Apple non-macOS consumers use cinterop, not FFM — D2)."
                }
            dependsOn(build)
            inputs.property("commit", boringsslCommit)
            inputs.property("shim", shimHash)
            onlyIf { t.ffiOsArch != null && (!ffiMarker.exists() || !libDir.resolve("libboringsslffi.dylib").exists()) }
            doLast {
                if (!isMacHost) throw GradleException("Apple FFM dylib link requires a macOS host with Xcode.")
                require(libDir.resolve("libcrypto.a").exists()) { "libcrypto.a missing — run buildBoringSslApple$cap first" }
                val shimDir = projectDir.resolve("docker")
                val sysroot = xcrunSdkPath(t.sdk)
                val linkWork = boringsslWork.get().dir("link-apple-${t.id}").asFile
                    .apply { deleteRecursively(); mkdirs() }
                // Exported-symbols list: the Mach-O leading-underscore form of the GNU version-script's
                // `boringssl_ffi_*` glob. ld64 honours `*` here just as the version-script does.
                val exportList = linkWork.resolve("boringssl_ffi_exports.symbols").apply { writeText("_boringssl_ffi_*\n") }
                val shimO = linkWork.resolve("boringssl_shim.o")
                val dylib = libDir.resolve("libboringsslffi.dylib")
                runOrThrow(
                    "clang", "-c", "-O2", "-fvisibility=hidden",
                    "-target", t.clangTarget, "-isysroot", sysroot,
                    "-I", outDir.resolve("include").absolutePath,
                    shimDir.resolve("boringssl_shim.c").absolutePath, "-o", shimO.absolutePath,
                    dir = linkWork, what = "compile ffi shim ${t.id}",
                )
                runOrThrow(
                    "clang", "-dynamiclib", "-target", t.clangTarget, "-isysroot", sysroot,
                    "-fvisibility=hidden", "-o", dylib.absolutePath, shimO.absolutePath,
                    libDir.resolve("libssl.a").absolutePath, libDir.resolve("libcrypto.a").absolutePath,
                    "-Wl,-exported_symbols_list,${exportList.absolutePath}", "-Wl,-dead_strip",
                    dir = linkWork, what = "link libboringsslffi.dylib ${t.id}",
                )
                runOrThrow("strip", "-x", dylib.absolutePath, dir = linkWork, what = "strip dylib ${t.id}")
                require(dylib.exists()) { "libboringsslffi.dylib missing after link for ${t.id}" }
                writeMarker(ffiMarker, ".ffi-", "libboringsslffi.dylib @ $boringsslCommit ${t.id} shim=$shimHash\n")
                logger.lifecycle("libboringsslffi.dylib linked for ${t.id} → $libDir")
            }
        }

    // ── Link-smoke: build a throwaway probe .dylib for the triple's SDK against the .a, exercising a
    // real BoringSSL entry point. Mach-O defaults to erroring on undefined symbols, so a missing/above
    // -floor symbol fails the link — the Apple analog of the Android whole-archive Bionic link-smoke /
    // the K/N glibc floor. Also asserts D3: the archive exports a PLAIN _SHA256_Init. This is the
    // compile-faithful proof for the device + tvOS/watchOS triples that can't run on the mac runner.
    val smokeDylib = boringsslWork.get().dir("smoke-apple-${t.id}").file("libprobe.dylib").asFile
    val smoke =
        tasks.register("linkSmokeApple$cap") {
            group = "boringssl"
            description = "Apple link-smoke: probe .dylib for ${t.id} (${t.sdk}) + D3 plain-symbol check."
            dependsOn(build)
            inputs.files(libDir.resolve("libssl.a"), libDir.resolve("libcrypto.a"))
            outputs.file(smokeDylib)
            doLast {
                if (!isMacHost) throw GradleException("Apple link-smoke requires a macOS host with Xcode.")
                val sysroot = xcrunSdkPath(t.sdk)
                val work = smokeDylib.parentFile.apply { mkdirs() }
                val probe = work.resolve("probe.c")
                probe.writeText(
                    """
                    #include <openssl/sha.h>
                    #include <stddef.h>
                    /* Reference a real BoringSSL entry point so the archive is genuinely exercised. */
                    unsigned char boringssl_apple_probe(const unsigned char *d, size_t n, unsigned char *out) {
                        SHA256(d, n, out);
                        return out[0];
                    }
                    """.trimIndent() + "\n",
                )
                runOrThrow(
                    "clang", "-dynamiclib", "-target", t.clangTarget, "-isysroot", sysroot,
                    "-I", outDir.resolve("include").absolutePath, probe.absolutePath,
                    libDir.resolve("libssl.a").absolutePath, libDir.resolve("libcrypto.a").absolutePath,
                    "-o", smokeDylib.absolutePath,
                    dir = work, what = "apple link-smoke ${t.id}",
                )
                // D3: the static archive must export _SHA256_Init un-mangled (a prefix build renames it).
                val nmProc = ProcessBuilder("nm", libDir.resolve("libcrypto.a").absolutePath)
                    .redirectErrorStream(true).start()
                val nmOut = nmProc.inputStream.bufferedReader().readText(); nmProc.waitFor()
                if (!Regex("(^| )T _SHA256_Init$", RegexOption.MULTILINE).containsMatchIn(nmOut)) {
                    throw GradleException("apple ${t.id}: libcrypto.a does not export a plain _SHA256_Init — prefix-mangled or unexpected symbol set.")
                }
                logger.lifecycle("apple ${t.id} link-smoke OK: probe links against the ${t.sdk} SDK (${smokeDylib.length() / 1024} KiB) + plain _SHA256_Init present.")
            }
        }

    val pkg =
        tasks.register("packageBoringSslApple$cap") {
            group = "boringssl"
            description = "Package ${t.id} into boringssl-$bundleVersion-${t.id}.tar.gz (+ .sha256, provenance)."
            dependsOn(build, linkFfi, smoke)
            doLast {
                val dist = distDir.get().asFile.apply { mkdirs() }
                val tarFile = dist.resolve("boringssl-$bundleVersion-${t.id}.tar")
                val tarGz = dist.resolve("boringssl-$bundleVersion-${t.id}.tar.gz")
                tarGz.delete()
                // Deterministic tar → `gzip -n`. macOS BSD tar lacks GNU's --sort/--mtime/--owner, so
                // this uses the portable normalize-mtime + sorted-filelist recipe. The FFM dylib +
                // build markers are excluded (the dylib ships in the boringssl-jvm MRJAR, not the K/N
                // cinterop bundle).
                deterministicApplePackage(
                    outDir, tarFile, sourceDateEpoch,
                    Regex("""^(libboringsslffi\.dylib|\.apple-.*|\.ffi-.*)$"""),
                )
                runOrThrow("gzip", "-nf", tarFile.absolutePath, dir = dist, what = "gzip ${t.id}")

                // Emit the FFM dylib as its own checksummed artifact (macOS only; directive #4). CI
                // gathers all platforms' shared libs into the single cross-platform MRJAR.
                val dylibSrc = libDir.resolve("libboringsslffi.dylib")
                if (t.ffiOsArch != null && dylibSrc.exists()) {
                    val smokeDy = dist.resolve("libboringsslffi-$bundleVersion-${t.id}.dylib")
                    dylibSrc.copyTo(smokeDy, overwrite = true)
                    dist.resolve("${smokeDy.name}.sha256").writeText("${sha256Of(smokeDy)}  ${smokeDy.name}\n")
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
                      "patchSet": "$patchSetHash",
                      "patches": [${patchSetNames.joinToString(", ") { "\"$it\"" }}],
                      "quicheAbi": "${catalog.findVersion("boringsslQuicheAbi").get().requiredVersion}",
                      "triple": "${t.id}",
                      "appleSdk": "${t.sdk}",
                      "deploymentTarget": "${t.minVersion}",
                      "proven": ${t.proven},
                      "bundleVersion": "$bundleVersion",
                      "sourceDateEpoch": $sourceDateEpoch
                    }
                    """.trimIndent() + "\n",
                )
                logger.lifecycle("Packaged ${t.id}: ${tarGz.name}  sha256=$digest")
            }
        }
    return Triple(build, smoke, pkg)
}

val appleBuilds = appleTriples.associate { it.id to registerAppleTriple(it) }

tasks.register("buildBoringSslApple") {
    group = "boringssl"
    description = "Cross-compile BoringSSL static libs for all wired Apple triples (macOS/iOS/tvOS/watchOS)."
    dependsOn(appleBuilds.values.map { it.first })
}

tasks.register("checkBoringSslApple") {
    group = "boringssl"
    description = "Apple per-SDK link-smoke + D3 plain-symbol check for every wired Apple triple."
    dependsOn(appleBuilds.values.map { it.second })
}

val packageApple =
    tasks.register("packageBoringSslApple") {
        group = "boringssl"
        description = "Package all wired Apple triples + write a combined SHA256SUMS."
        dependsOn(appleBuilds.values.map { it.third })
        doLast {
            val dist = distDir.get().asFile
            val sums = dist.listFiles { f -> f.name.endsWith(".tar.gz") }?.sortedBy { it.name }?.joinToString("") {
                "${sha256Of(it)}  ${it.name}\n"
            }.orEmpty()
            dist.resolve("SHA256SUMS").writeText(sums)
            logger.lifecycle("Wrote SHA256SUMS:\n$sums")
        }
    }

// `assemble` produces the distributable tarballs. Apple rides along only on a macOS host (the Apple
// tasks require Xcode); Linux/CI runners that invoke a specific packageBoringSsl<Triple> task are
// unaffected.
tasks.named("assemble") {
    dependsOn(packageLinux)
    if (isMacHost) dependsOn(packageApple)
}
