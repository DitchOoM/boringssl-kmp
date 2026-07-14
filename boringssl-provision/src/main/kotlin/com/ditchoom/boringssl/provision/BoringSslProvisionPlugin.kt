package com.ditchoom.boringssl.provision

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * STUB (migration step 1). The published consumer surface for canonical BoringSSL bundles.
 *
 * Applying `id("com.ditchoom.boringssl.provision")` registers the [BoringSslProvisionExtension] as
 * `boringssl { }`. Consumers then point cinterop `-libraryPath` / `-staticLibrary` + `includeDirs` at
 * `boringssl.boringsslDir(triple)` (RFC §7).
 *
 * Step 2/3 (RFC §10) fills in the real download + verify + extract logic:
 *   - fetch `boringssl-<ver>-<triple>.tar.gz` by **stable direct asset URL**
 *     (`.../releases/download/<tag>/<asset>`), never the `/releases/latest` API (avoids the
 *     60-req/hr unauthenticated limit — RFC §3);
 *   - verify against the **baked-in sha256** (no TOFU) plus `SHA256SUMS`;
 *   - extract into `~/.gradle/caches/ditchoom-boringssl/<ver>/<triple>/{include,lib}`;
 *   - honour a mirror / base-URL override.
 */
class BoringSslProvisionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.extensions.create(
            "boringssl",
            BoringSslProvisionExtension::class.java,
            target,
        )
    }
}

/**
 * STUB extension. `boringsslDir(triple)` returns the cache directory a consumer's cinterop reads its
 * `{include, lib}` from. Step 2/3 replaces the pure path computation with a download-on-demand that
 * materialises + verifies the tarball before returning.
 */
open class BoringSslProvisionExtension(private val project: Project) {
    /** Overridable cache root; defaults to the shared per-user Gradle cache. */
    var cacheRoot: File =
        File(project.gradle.gradleUserHomeDir, "caches/ditchoom-boringssl")

    /** Pinned bundle version (the boringssl-kmp release, not the BoringSSL commit). Wired in step 3. */
    var version: String = "0.0.1"

    /**
     * STUB: returns the cache directory for [triple] without downloading anything yet. Step 2/3 makes
     * this trigger the verified download when the directory is absent.
     */
    fun boringsslDir(triple: String): File = File(cacheRoot, "$version/$triple")
}
