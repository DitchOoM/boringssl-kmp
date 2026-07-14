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

// One K/N triple's build recipe. `crossCc == null` means "build natively with the host compiler".
data class NativeTriple(
    val id: String, // konan target id, e.g. linuxX64 — also the tarball/dir suffix
    val crossCc: String?,
    val crossCxx: String?,
    val crossSystemProcessor: String?,
    val extraCFlags: List<String>,
)

val linuxTriples =
    listOf(
        NativeTriple("linuxX64", crossCc = null, crossCxx = null, crossSystemProcessor = null, extraCFlags = listOf("-fPIC")),
        // arm64: cross only when the host isn't already aarch64. -mno-outline-atomics avoids emitting
        // __aarch64_cas*/__aarch64_ldadd* helper refs the K/N linker can't resolve (socket's fix).
        NativeTriple(
            "linuxArm64",
            crossCc = "aarch64-linux-gnu-gcc",
            crossCxx = "aarch64-linux-gnu-g++",
            crossSystemProcessor = "aarch64",
            extraCFlags = listOf("-fPIC", "-mno-outline-atomics"),
        ),
    )

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

fun registerTriple(t: NativeTriple): Pair<TaskProvider<Task>, TaskProvider<Task>> {
    val cap = t.id.replaceFirstChar { it.uppercase() }
    val outDir = projectDir.resolve("libs/boringssl/${t.id}")
    val marker = outDir.resolve("lib/.built-$boringsslCommit")
    val crossing = t.crossCc != null && System.getProperty("os.arch") != "aarch64"

    val build =
        tasks.register("buildBoringSsl$cap") {
            group = "boringssl"
            description = "Build BoringSSL static libs (libssl.a + libcrypto.a) + headers for ${t.id}."
            dependsOn(fetchBoringSsl)
            inputs.property("commit", boringsslCommit)
            outputs.dir(outDir)
            onlyIf { !marker.exists() }
            doLast {
                val srcDir = boringsslWork.get().dir("src").asFile
                val cmakeBuildDir = boringsslWork.get().dir("build-${t.id}").asFile
                cmakeBuildDir.deleteRecursively(); cmakeBuildDir.mkdirs()

                val cFlags = t.extraCFlags.joinToString(" ")
                val cmakeArgs =
                    mutableListOf(
                        "cmake",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DBUILD_SHARED_LIBS=OFF",
                        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                        "-DCMAKE_C_FLAGS=$cFlags",
                        "-DCMAKE_CXX_FLAGS=$cFlags",
                        "-G", "Unix Makefiles",
                    )
                if (crossing) {
                    cmakeArgs.addAll(
                        listOf(
                            "-DCMAKE_SYSTEM_NAME=Linux",
                            "-DCMAKE_SYSTEM_PROCESSOR=${t.crossSystemProcessor}",
                            "-DCMAKE_C_COMPILER=${t.crossCc}",
                            "-DCMAKE_CXX_COMPILER=${t.crossCxx}",
                        ),
                    )
                }
                cmakeArgs.add(srcDir.absolutePath)

                logger.lifecycle("Configuring BoringSSL for ${t.id}${if (crossing) " (cross)" else ""}...")
                runOrThrow(*cmakeArgs.toTypedArray(), dir = cmakeBuildDir, what = "cmake configure ${t.id}")

                logger.lifecycle("Building BoringSSL ssl+crypto for ${t.id} (a few minutes)...")
                val jobs = Runtime.getRuntime().availableProcessors()
                runOrThrow("make", "-j$jobs", "ssl", "crypto", dir = cmakeBuildDir, what = "make ${t.id}")

                outDir.resolve("lib").mkdirs()
                val ssl = cmakeBuildDir.walkTopDown().firstOrNull { it.name == "libssl.a" }
                    ?: throw GradleException("libssl.a not found for ${t.id}")
                val crypto = cmakeBuildDir.walkTopDown().firstOrNull { it.name == "libcrypto.a" }
                    ?: throw GradleException("libcrypto.a not found for ${t.id}")
                ssl.copyTo(outDir.resolve("lib/libssl.a"), overwrite = true)
                crypto.copyTo(outDir.resolve("lib/libcrypto.a"), overwrite = true)

                // ar-merge the __isoc23_* compat object into libcrypto.a (glibc>=2.38 K/N link fix).
                val compatC = cmakeBuildDir.resolve("isoc23_compat.c").apply { writeText(isoC23CompatSource) }
                val compatO = cmakeBuildDir.resolve("isoc23_compat.o")
                val cc = t.crossCc ?: "cc"
                runOrThrow(cc, "-c", "-fPIC", "-O2", compatC.absolutePath, "-o", compatO.absolutePath,
                    dir = cmakeBuildDir, what = "compile isoc23 compat ${t.id}")
                runOrThrow("ar", "r", outDir.resolve("lib/libcrypto.a").absolutePath, compatO.absolutePath,
                    dir = cmakeBuildDir, what = "ar-merge isoc23 compat ${t.id}")

                // Headers (BoringSSL: src/include/openssl/*.h; older layouts: include/).
                val inc = listOf(srcDir.resolve("src/include"), srcDir.resolve("include")).first { it.exists() }
                inc.copyRecursively(outDir.resolve("include"), overwrite = true)

                marker.writeText("google/boringssl @ $boringsslCommit (API v$boringsslApiVersion) for ${t.id}\n")
                logger.lifecycle("BoringSSL built for ${t.id} → ${outDir.resolve("lib")}")
            }
        }

    val pkg =
        tasks.register("packageBoringSsl$cap") {
            group = "boringssl"
            description = "Package ${t.id} into boringssl-$bundleVersion-${t.id}.tar.gz (+ .sha256, provenance)."
            dependsOn(build)
            doLast {
                val dist = distDir.get().asFile.apply { mkdirs() }
                val tarFile = dist.resolve("boringssl-$bundleVersion-${t.id}.tar")
                val tarGz = dist.resolve("boringssl-$bundleVersion-${t.id}.tar.gz")
                tarGz.delete()
                // Deterministic tar (sorted, fixed owner/mtime) then `gzip -n` (no embedded timestamp).
                // Two sequential processes via a temp file — NOT a shell pipe (a tar|gzip pipe deadlocks
                // once gzip's 64KB stdout buffer fills while we're still feeding its stdin).
                runOrThrow(
                    "tar", "--sort=name", "--mtime=@$sourceDateEpoch",
                    "--owner=0", "--group=0", "--numeric-owner",
                    "-cf", tarFile.absolutePath, "-C", outDir.absolutePath, "include", "lib",
                    dir = dist, what = "tar ${t.id}",
                )
                runOrThrow("gzip", "-nf", tarFile.absolutePath, dir = dist, what = "gzip ${t.id}")

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
