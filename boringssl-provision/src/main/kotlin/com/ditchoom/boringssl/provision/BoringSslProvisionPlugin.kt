package com.ditchoom.boringssl.provision

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The published consumer surface for canonical BoringSSL bundles (RFC §3/§7).
 *
 * Applying `id("com.ditchoom.boringssl.provision")` registers [BoringSslProvisionExtension] as
 * `boringssl { }`. A consumer's cinterop then points `-libraryPath` / `-staticLibrary` + `includeDirs`
 * at `boringssl.boringsslDir(triple)` — a ~10-line swap for the old ~150-line inline clone+cmake task.
 *
 * Resolution order for a triple (first hit wins):
 *   1. `localDist` (a `:boringssl-build` `build/dist` dir) — used during migration BEFORE any release
 *      exists; verified against the sibling `<tarball>.sha256`. Defaults from
 *      `-PboringsslLocalBundle=<dir>` (see [BoringSslProvisionExtension.localDist]).
 *   2. Remote: `<baseUrl>/<version>/boringssl-<version>-<triple>.tar.gz`, fetched by STABLE DIRECT URL
 *      (never the `/releases/latest` API — avoids the 60-req/hr unauthenticated limit, RFC §3), then
 *      verified against the BAKED-IN checksum in [checksums] (no TOFU).
 * Verified tarballs extract once into `<cacheRoot>/<version>/<triple>/{include,lib}`, guarded by a
 * marker keyed on version + tarball sha256 — a same-version tarball with DIFFERENT bits (local
 * candidate iteration) busts the marker and re-extracts cleanly instead of silently no-opping. If the
 * source later becomes unresolvable (producer dist cleaned, no baked checksum), an already-provisioned
 * extraction keeps serving — a resolvable source is only required when (re)extraction is needed.
 */
class BoringSslProvisionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("boringssl", BoringSslProvisionExtension::class.java, target)
        // `-PboringsslLocalBundle=<dir>` (absolute, or relative to the ROOT project dir) seeds
        // `localDist` — the CLI override for iterating on a locally built candidate bundle without
        // touching the build script. Apply-time default only: the consumer's `boringssl { }` block runs
        // after apply(), so an explicit `localDist = …` assignment there still wins.
        target.providers.gradleProperty("boringsslLocalBundle").orNull?.let { path ->
            val dir = File(path)
            extension.localDist = if (dir.isAbsolute) dir else File(target.rootDir, path)
        }
    }
}

open class BoringSslProvisionExtension(private val project: Project) {
    // Baked-in release identity + checksums, loaded from `boringssl-provision.properties` on the plugin
    // classpath (written by :boringssl-provision:bakeChecksums at release time — RFC §8 directive #4).
    // Absent in dev builds → version falls back to "0.0.1" and checksums stay empty (localDist path).
    private val baked: java.util.Properties =
        java.util.Properties().apply {
            BoringSslProvisionExtension::class.java.getResourceAsStream("/boringssl-provision.properties")
                ?.use { load(it) }
        }

    /** The boringssl-kmp *release* version (bundle), NOT the BoringSSL commit. */
    var version: String = baked.getProperty("version")?.takeIf { it.isNotBlank() } ?: "0.0.1"

    /**
     * Base URL for released tarballs. Default is the GitHub Releases download root; a release tag `v$version`
     * holds `boringssl-<version>-<triple>.tar.gz`. Override for a mirror or an air-gapped proxy.
     */
    var baseUrl: String = "https://github.com/DitchOoM/boringssl-kmp/releases/download/v"

    /**
     * Optional local `:boringssl-build` dist dir. When set and it contains the tarball, it is used
     * instead of a network fetch (dev + the migration window before the first release). Verified
     * against the sibling `.sha256`; when that sidecar is absent the tarball is SELF-hashed — a
     * documented break-glass with a loud warning (structural checks become the only verification).
     *
     * Defaults from `-PboringsslLocalBundle=<dir>` (absolute, or relative to the root project dir),
     * wired at plugin apply time; an explicit assignment here always wins over the property.
     */
    var localDist: File? = null

    /** Per-user extraction cache. */
    var cacheRoot: File = File(project.gradle.gradleUserHomeDir, "caches/ditchoom-boringssl")

    /**
     * Baked-in `triple -> sha256` map for the released tarballs (no trust-on-first-use). Defaults to the
     * checksums baked into the shipped plugin for [version]; a consumer may override or extend it. For a
     * [localDist] resolve the sibling `.sha256` is authoritative and this may stay empty; for a remote
     * fetch a missing entry is a hard error.
     */
    var checksums: Map<String, String> =
        baked.stringPropertyNames().filter { it != "version" }.associateWith { baked.getProperty(it) }

    /**
     * Returns `<cacheRoot>/<version>/<triple>` containing `{include, lib}`, materialising (download →
     * verify → extract) on first use. Safe to call from a cinterop configuration block.
     */
    fun boringsslDir(triple: String): File = boringsslDir(triple, null)

    /**
     * Variant-aware provisioning (RFC §12 D8). [alias] null → the canonical unprefixed bundle; a
     * content-addressed `b<hash8>` [alias] → the PREFIXED variant `boringssl-<ver>-<triple>-<alias>.tar.gz`,
     * whose `lib/` holds the `b<hash>_`-prefixed archives and `adapter/` the generated plain->b<hash>_
     * alias table. Extracted to `<cacheRoot>/<version>/<triple>[-<alias>]/`.
     */
    fun boringsslDir(triple: String, alias: String?): File {
        val suffix = alias?.let { "-$it" } ?: ""
        val key = "$triple$suffix"
        val dest = File(cacheRoot, "$version/$key")
        val tarName = "boringssl-$version-$key.tar.gz"

        // The expected sha256 is knowable WITHOUT fetching (baked map / localDist sidecar / self-hash),
        // so the extraction marker keys on version + sha: a same-version tarball with different bits
        // (local candidate iteration) misses the marker and forces a clean re-extraction.
        val (expected, materialize) =
            try {
                resolveTarball(key, tarName)
            } catch (e: GradleException) {
                // Source unresolvable (localDist wiped by a producer clean, no baked checksum — the
                // migration window between producer rebuilds). An existing extraction was sha-verified
                // when its marker was written, so it keeps serving; a resolvable source is only
                // required when (re)extraction is actually needed.
                if (provisioned(dest)) return dest
                throw e
            }
        val marker = File(dest, ".provisioned-$version-${expected.lowercase().take(12)}")
        if (marker.exists() && File(dest, "lib").isDirectory && File(dest, "include").isDirectory) return dest

        val tarball = materialize()
        val actual = sha256(tarball)
        if (!expected.equals(actual, ignoreCase = true)) {
            throw GradleException("BoringSSL bundle checksum mismatch for $triple: expected $expected, got $actual (from ${tarball.absolutePath})")
        }

        // Clean any stale extraction (previous bits AND its old sha-keyed marker) before re-extracting.
        dest.deleteRecursively(); File(dest, "lib").mkdirs(); File(dest, "include").mkdirs()
        val code = ProcessBuilder("tar", "xzf", tarball.absolutePath, "-C", dest.absolutePath)
            .redirectErrorStream(true).start()
            .also { it.inputStream.bufferedReader().forEachLine { l -> project.logger.info(l) } }.waitFor()
        if (code != 0) throw GradleException("Failed to extract $tarName (tar exit $code)")
        require(File(dest, "lib/libcrypto.a").exists()) { "Extracted bundle for $triple is missing lib/libcrypto.a" }
        marker.writeText("$actual\n")
        project.logger.lifecycle("Provisioned BoringSSL $version/$triple → $dest")
        return dest
    }

    /**
     * One-line K/N cinterop wiring over [boringsslDir] (RFC §4 / D8). Provisions the [triple]'s bundle,
     * copies the canonical shipped `boringssl.def` (or a consumer [def] override), injects the resolved
     * `libraryPaths` / `staticLibraries` + platform `linkerOpts`, and registers the cinterop on the
     * target's `main` compilation — so a K/N consumer gets BoringSSL bindings with ~1 line and no
     * hand-written `.def`:
     *
     * ```
     * kotlin { val mac = macosArm64() }
     * boringssl { cinterop(mac) }
     * ```
     *
     * The generated cinterop is named [cinteropName] (default `boringssl`). [triple] defaults to the
     * target name (the konan preset name — `linuxX64`, `macosArm64`, … — which matches the bundle
     * triple); pass it explicitly only if the target was given a non-default name.
     *
     * [cryptoOnly] links `libcrypto.a` alone, omitting `libssl.a` — for a consumer with no TLS/DTLS
     * surface (e.g. buffer-crypto: hashes, AEAD, curve25519, HKDF, EC). Combined with the archives'
     * `-ffunction-sections`/`-fdata-sections` and the consumer's `--gc-sections`, the final binary
     * carries only the referenced crypto closure — never any of libssl. Canonical (unprefixed) bundle
     * only; ignored for a content-addressed [alias] variant (whose alias table spans crypto + ssl).
     *
     * ## Owner vs external ([embedArchive])
     * When several K/N modules co-link ONE final binary (e.g. `buffer` + `socket` + `webrtc-dtls`), only
     * one of them may EMBED the canonical archive — otherwise ld.lld sees the same `libcrypto.a` object
     * files from two klibs and fails with duplicate-symbol errors.
     *  - [embedArchive] `= true` (default, **owner** mode — exactly today's behaviour): the generated def
     *    carries `libraryPaths` + `staticLibraries`, so the cinterop klib EMBEDS the archive into its
     *    `included/` and contributes it to the final link line. 100% backward-compatible with every
     *    existing caller (buffer-crypto, samples, testsuite).
     *  - [embedArchive] `= false` (**external** mode): the generated def OMITS both `libraryPaths` and
     *    `staticLibraries`, keeping only `headers` + `compilerOpts` + the `includeDirs(<bundle>/include)`
     *    registered on the cinterop. The klib's `included/` stays empty; K/N auto-propagates the OWNER
     *    klib's embedded archive to the final link line, so this cinterop's symbol refs resolve against
     *    that single copy. No `linkerOpts`, no force-load, no marker needed.
     *
     * ### Caller responsibilities (not enforced here)
     *  - **Exactly ONE owner per final-link target.** Every module linked into a given binary must agree
     *    on which one calls with [embedArchive] `= true`; all others pass `false`. A build-time guardrail
     *    is a deliberate follow-up — for THIS release it is the caller's contract. Zero owners = undefined
     *    symbols at link; two owners = duplicate symbols.
     *  - **ABI parity.** An external consumer's headers MUST come from the SAME catalog-pinned bundle as
     *    the owner's embedded archive (same [version] + [triple] + BoringSSL commit). Since both resolve
     *    through [boringsslDir], keep [version]/[triple] identical across the co-linking modules.
     *
     * In external mode no archive is embedded, so [cryptoOnly] (which archive the OWNER embeds) and
     * [alias] (which prefixed variant the owner embeds) are moot and IGNORED — the owner alone decides
     * the archive; this call only contributes headers. The Linux `-lpthread -ldl` floor is still emitted
     * in external mode (the final binary needs pthread/dl regardless of which module supplies the
     * archive), which is harmless with no archive of our own.
     */
    fun cinterop(
        target: KotlinNativeTarget,
        cinteropName: String = "boringssl",
        triple: String = target.name,
        def: File? = null,
        alias: String? = null,
        cryptoOnly: Boolean = false,
        embedArchive: Boolean = true,
    ) {
        val dir = boringsslDir(triple, alias)
        val includeDir = File(dir, "include")
        val libDir = File(dir, "lib")
        val isLinux = triple.startsWith("linux")

        val baseDefText =
            def?.readText()
                ?: BoringSslProvisionExtension::class.java
                    .getResourceAsStream("/com/ditchoom/boringssl/provision/boringssl.def")
                    ?.use { it.readBytes().decodeToString() }
                ?: throw GradleException("boringssl.cinterop: canonical boringssl.def resource missing from the plugin jar")

        val sep = baseDefText.indexOf("\n---")
        require(sep > 0) { "boringssl.cinterop: def is missing the '---' separator (def=${def?.absolutePath ?: "canonical"})" }
        val baseDeclaresLinker = Regex("(?m)^\\s*linkerOpts\\s*=").containsMatchIn(baseDefText.substring(0, sep))

        // The link config to inject before `---`. For the canonical bundle: staticLibraries + the
        // platform floor libs. For a content-addressed VARIANT (D8): the headers are still plain, but the
        // archives export `b<hash>_` symbols, so the generated alias adapter maps the consumer's plain
        // refs onto them — Mach-O via ld64 `-alias_list` (lazy), ELF via `--whole-archive` + the PROVIDE
        // script (matching how quiche consumes the external libcrypto, RFC §6).
        val linkLines =
            if (!embedArchive) {
                // EXTERNAL mode: headers only — OMIT libraryPaths + staticLibraries so this klib's
                // included/ stays empty and no archive object files enter the link twice. The owner
                // cinterop (embedArchive = true, same bundle) supplies the single canonical archive; K/N
                // auto-propagates it to the final link line, resolving these refs. cryptoOnly + alias are
                // moot (no archive embedded here). Keep the linux platform floor: the final binary needs
                // pthread/dl regardless of which module owns the archive, and it is harmless standalone.
                buildString {
                    if (isLinux && !baseDeclaresLinker) append("\nlinkerOpts = -lpthread -ldl")
                }
            } else if (alias == null) {
                buildString {
                    append("\nlibraryPaths = ").append(libDir.absolutePath)
                    append("\nstaticLibraries = ").append(if (cryptoOnly) "libcrypto.a" else "libssl.a libcrypto.a")
                    // glibc floors keep pthread + dl separate (linux); Apple auto-links libSystem.
                    if (isLinux && !baseDeclaresLinker) append("\nlinkerOpts = -lpthread -ldl")
                }
            } else {
                val adapter = File(dir, "adapter/" + if (isLinux) "aliases.elf.ld" else "aliases.macho")
                require(adapter.exists()) {
                    "boringssl.cinterop(alias=$alias): variant adapter missing ($adapter) — not a prefixed -<alias> bundle."
                }
                val ssl = libDir.resolve("libssl.a").absolutePath
                val crypto = libDir.resolve("libcrypto.a").absolutePath
                // Whole-archive the prefixed libs so EVERY alias target is defined (the full plain->b<hash>_
                // table references crypto+ssl symbols; a lazy link would leave the unreferenced ones
                // dangling). This is exactly how quiche consumes the external libcrypto (RFC §6). K/N passes
                // linkerOpts STRAIGHT to ld, so these are raw ld64/GNU-ld options (no `-Wl,` driver prefix).
                if (isLinux) {
                    "\nlinkerOpts = --whole-archive $ssl $crypto --no-whole-archive ${adapter.absolutePath} -lpthread -ldl"
                } else {
                    "\nlinkerOpts = -force_load $ssl -force_load $crypto -alias_list ${adapter.absolutePath}"
                }
            }

        val injected = baseDefText.substring(0, sep) + linkLines + "\n" + baseDefText.substring(sep)
        val suffix = alias?.let { "-$it" } ?: ""
        val generatedDef =
            File(project.layout.buildDirectory.get().asFile, "generated/cinterop/$cinteropName-$triple$suffix.def")
                .apply { parentFile.mkdirs(); writeText(injected) }

        val settings = target.compilations.getByName("main").cinterops.create(cinteropName)
        settings.defFile(generatedDef)
        settings.includeDirs(includeDir)
        project.logger.info("boringssl.cinterop: wired '$cinteropName' for $triple$suffix → def=${generatedDef.absolutePath}")
    }

    /** True when [dest] holds a completed extraction for [version]: a sha-keyed marker plus lib/ + include/. */
    private fun provisioned(dest: File): Boolean =
        File(dest, "lib").isDirectory &&
            File(dest, "include").isDirectory &&
            dest.listFiles().orEmpty().any { it.name.startsWith(".provisioned-$version-") }

    /**
     * Resolve [tarName]'s expected checksum plus a materializer producing the tarball itself (the
     * [localDist] file, or the download-cache copy after fetching). The checksum is knowable WITHOUT
     * fetching — the sidecar `.sha256` (or break-glass self-hash) for [localDist], the baked-in
     * [checksums] entry (no TOFU; missing entry = hard error) for a remote — so [boringsslDir] can key
     * its extraction marker on it before paying for any download.
     */
    private fun resolveTarball(triple: String, tarName: String): Pair<String, () -> File> {
        localDist?.let { dist ->
            val local = File(dist, tarName)
            if (local.exists()) {
                val shaFile = File(dist, "$tarName.sha256")
                val expected =
                    if (shaFile.exists()) {
                        shaFile.readText().trim().substringBefore(" ")
                    } else {
                        project.logger.warn(
                            "boringssl: no $tarName.sha256 sidecar in ${dist.absolutePath} — self-hashing the local " +
                                "tarball (break-glass: the structural checks are the only verification).",
                        )
                        sha256(local)
                    }
                return expected to { local }
            }
        }
        val expected = checksums[triple]
            ?: throw GradleException(
                "No baked-in checksum for $triple (version $version) and no localDist tarball. " +
                    "Set boringssl { checksums = mapOf(\"$triple\" to \"<sha256>\") } or point localDist at :boringssl-build/build/dist.",
            )
        return expected to {
            val cached = File(cacheRoot, "downloads/$tarName")
            if (!cached.exists()) {
                cached.parentFile.mkdirs()
                download("$baseUrl$version/$tarName", cached)
            }
            cached
        }
    }

    /**
     * Fetch [url] into [dest] — atomically, with timeouts, and with a bounded retry.
     *
     * Three things a bare `URL.openStream().copyTo(...)` did not do, each answering a way the release
     * lanes actually broke:
     *
     * 1. **Retry.** The observed failure is `Unexpected end of file from server` — `HttpClient`'s words
     *    for a connection closed before the response completed, classically a pooled keep-alive socket
     *    the far end had already dropped. A single attempt turned that into a build failure, and four
     *    consecutive release runs died on it while the asset itself was healthy. `Connection: close`
     *    asks for a fresh socket per attempt, so a retry is not handed the same dead one back.
     * 2. **Timeouts.** `openStream()` sets none, so the failure mode opposite to an EOF — a server that
     *    accepts the connection and then never speaks — hangs the job until CI's own timeout kills it,
     *    with no retry and nothing in the log naming the URL.
     * 3. **Atomicity.** The old copy wrote straight to the cache path, so an interrupted download left a
     *    TRUNCATED file sitting there. The next invocation sees it exists, skips the fetch entirely, and
     *    fails the sha256 check instead — surfacing as a checksum mismatch, which reads as corruption or
     *    tampering rather than as a partial download, and which no amount of retrying clears until
     *    somebody deletes the cache by hand. Downloading beside the target and moving it into place on
     *    success means a partial is never visible as a cache hit.
     */
    private fun download(
        url: String,
        dest: File,
    ) {
        var lastFailure: IOException? = null
        repeat(DOWNLOAD_ATTEMPTS) { attempt ->
            project.logger.lifecycle(
                "Downloading BoringSSL bundle (attempt ${attempt + 1}/$DOWNLOAD_ATTEMPTS): $url",
            )
            // Unique per attempt, so two Gradle invocations racing the same cache cannot truncate each
            // other's in-flight body — a collision the single fixed path could not rule out either.
            val partial = File.createTempFile("${dest.name}.", ".part", dest.parentFile)
            try {
                val connection =
                    (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        setRequestProperty("Connection", "close")
                    }
                try {
                    val copied =
                        connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
                    // `copyTo` reads to EOF and cannot tell "the body ended" from "the connection did",
                    // so a body cut short arrives as a SHORT FILE with no exception at all. Left
                    // unchecked that reaches the caller as a sha256 mismatch — a message that reads as
                    // corruption or tampering and sends the reader to the release asset, when the asset
                    // was fine and the transfer was not. Compared only when the length is both declared
                    // and describes the bytes we actually receive (a re-encoded body would not).
                    val declared = connection.contentLengthLong
                    if (connection.contentEncoding == null && declared >= 0 && copied != declared) {
                        throw IOException("truncated response: read $copied of $declared declared bytes")
                    }
                } finally {
                    connection.disconnect()
                }
                // Only now, with the whole body read, does the cache path come into existence.
                Files.move(partial.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (e: IOException) {
                lastFailure = e
                partial.delete()
                project.logger.warn(
                    "boringssl: download attempt ${attempt + 1}/$DOWNLOAD_ATTEMPTS failed for $url: $e",
                )
                if (attempt < DOWNLOAD_ATTEMPTS - 1) Thread.sleep(RETRY_BACKOFF_MS * (attempt + 1L))
            }
        }
        throw GradleException(
            "BoringSSL bundle download failed after $DOWNLOAD_ATTEMPTS attempts: $url " +
                "(last failure: $lastFailure)",
            lastFailure,
        )
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val r = ins.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** Attempts per bundle URL. Three covers a stale pooled socket and a blip; a wall beyond that. */
        const val DOWNLOAD_ATTEMPTS = 3

        const val CONNECT_TIMEOUT_MS = 15_000

        /**
         * Generous against a slow runner but finite: the bundle is ~1.8 MB, so a read that stalls this
         * long is a hung connection, not a slow one, and retrying beats waiting for the job timeout.
         */
        const val READ_TIMEOUT_MS = 60_000

        /** Multiplied by the attempt number — 0.5s then 1s, enough for a transient to clear. */
        const val RETRY_BACKOFF_MS = 500L
    }
}
