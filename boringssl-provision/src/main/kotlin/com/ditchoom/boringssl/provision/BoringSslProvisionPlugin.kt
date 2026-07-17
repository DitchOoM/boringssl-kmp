package com.ditchoom.boringssl.provision

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.io.File
import java.net.URI
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
     */
    fun cinterop(
        target: KotlinNativeTarget,
        cinteropName: String = "boringssl",
        triple: String = target.name,
        def: File? = null,
        alias: String? = null,
        cryptoOnly: Boolean = false,
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
            if (alias == null) {
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
                val url = "$baseUrl$version/$tarName"
                project.logger.lifecycle("Downloading BoringSSL bundle: $url")
                URI(url).toURL().openStream().use { input -> cached.outputStream().use { input.copyTo(it) } }
            }
            cached
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val r = ins.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
