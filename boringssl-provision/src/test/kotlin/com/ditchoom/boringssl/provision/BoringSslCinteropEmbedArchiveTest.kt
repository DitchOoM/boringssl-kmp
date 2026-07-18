package com.ditchoom.boringssl.provision

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Owner (`embedArchive = true`, the default) vs external (`embedArchive = false`) cinterop-def emission.
 *
 * Builds a REAL [KotlinNativeTarget] under [ProjectBuilder] (KGP is `testImplementation` for exactly
 * this) and calls [BoringSslProvisionExtension.cinterop], then reads the generated `.def` the helper
 * writes under `build/generated/cinterop/`. Asserts on the injected DIRECTIVE LINES (line-anchored) —
 * NOT raw substrings, because the shipped canonical `boringssl.def` carries a comment that mentions the
 * words `libraryPaths`/`staticLibraries`. The bundle fixture is built programmatically (RFC §8 #1).
 */
class BoringSslCinteropEmbedArchiveTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val testVersion = "0.0.1-test"
    private val triple = "linuxX64"
    private val tarName get() = "boringssl-$testVersion-$triple.tar.gz"

    private lateinit var project: Project
    private lateinit var extension: BoringSslProvisionExtension
    private lateinit var target: KotlinNativeTarget

    @Before
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("project")).build()
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        project.pluginManager.apply(BoringSslProvisionPlugin::class.java)
        extension = project.extensions.getByType(BoringSslProvisionExtension::class.java)
        extension.version = testVersion
        extension.cacheRoot = tmp.newFolder("cache")
        val dist = tmp.newFolder("dist")
        createBundleTarball(dist, "bits-A")
        extension.localDist = dist
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
        target = kotlin.linuxX64() as KotlinNativeTarget
    }

    @Test
    fun `owner mode (default) embeds libraryPaths + staticLibraries and keeps headers`() {
        extension.cinterop(target, cinteropName = "owner")
        val def = generatedDef("owner").readText()

        assertEquals(libDir().absolutePath, defValue(def, "libraryPaths"))
        assertEquals("libssl.a libcrypto.a", defValue(def, "staticLibraries"))
        assertEquals("-lpthread -ldl", defValue(def, "linkerOpts"))
        // Headers + compilerOpts are preserved unchanged.
        assertTrue(defValue(def, "headers")!!.startsWith("openssl/base.h"))
        assertEquals("-DBORINGSSL_NO_CXX", defValue(def, "compilerOpts"))
        // The bundle include dir is registered as a header search dir (the -I<bundle>/include).
        assertTrue(includeDirsOf("owner").contains(includeDir()))
    }

    @Test
    fun `owner cryptoOnly embeds libcrypto alone`() {
        extension.cinterop(target, cinteropName = "ownerCrypto", cryptoOnly = true)
        val def = generatedDef("ownerCrypto").readText()

        assertEquals(libDir().absolutePath, defValue(def, "libraryPaths"))
        assertEquals("libcrypto.a", defValue(def, "staticLibraries"))
    }

    @Test
    fun `external mode emits NEITHER libraryPaths NOR staticLibraries but keeps headers + includeDirs`() {
        extension.cinterop(target, cinteropName = "external", embedArchive = false)
        val def = generatedDef("external").readText()

        // The archive is NOT embedded: no directive lines for it (the owner klib supplies the one copy).
        assertNull("external mode must not embed libraryPaths", defValue(def, "libraryPaths"))
        assertNull("external mode must not embed staticLibraries", defValue(def, "staticLibraries"))
        // Headers + compilerOpts survive so the cinterop still binds the same declarations.
        assertTrue(defValue(def, "headers")!!.startsWith("openssl/base.h"))
        assertEquals("-DBORINGSSL_NO_CXX", defValue(def, "compilerOpts"))
        // The include dir IS still registered on the cinterop (the -I<bundle>/include compiler input).
        assertTrue(includeDirsOf("external").contains(includeDir()))
        // The linux platform floor is retained (documented: harmless, the final binary needs pthread/dl).
        assertEquals("-lpthread -ldl", defValue(def, "linkerOpts"))
    }

    @Test
    fun `external mode ignores cryptoOnly (no archive is embedded either way)`() {
        extension.cinterop(target, cinteropName = "externalCrypto", cryptoOnly = true, embedArchive = false)
        val def = generatedDef("externalCrypto").readText()

        assertNull(defValue(def, "staticLibraries"))
        assertNull(defValue(def, "libraryPaths"))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun generatedDef(name: String): File =
        File(project.layout.buildDirectory.get().asFile, "generated/cinterop/$name-$triple.def")

    /** Value of a line-anchored `<key> = <value>` directive in the generated def, or null if absent. */
    private fun defValue(def: String, key: String): String? =
        Regex("(?m)^" + Regex.escape(key) + "\\s*=\\s*(.*)$").find(def)?.groupValues?.get(1)?.trim()

    private fun includeDirsOf(cinteropName: String): Set<File> =
        target.compilations.getByName("main").cinterops.getByName(cinteropName)
            .includeDirs.allHeadersDirs.files

    private fun includeDir(): File = File(extension.cacheRoot, "$testVersion/$triple/include")

    private fun libDir(): File = File(extension.cacheRoot, "$testVersion/$triple/lib")

    /** Build `boringssl-<version>-<triple>.tar.gz` + `.sha256` sidecar from stub text files. */
    private fun createBundleTarball(distDir: File, bits: String): File {
        val stage = tmp.newFolder()
        File(stage, "lib").mkdirs()
        File(stage, "include/openssl").mkdirs()
        File(stage, "lib/libcrypto.a").writeText(bits)
        File(stage, "lib/libssl.a").writeText(bits)
        File(stage, "include/openssl/base.h").writeText("/* stub header */\n")
        val tarball = File(distDir, tarName)
        val code = ProcessBuilder("tar", "czf", tarball.absolutePath, "-C", stage.absolutePath, "lib", "include")
            .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        check(code == 0) { "fixture tar failed (exit $code)" }
        File(distDir, "$tarName.sha256").writeText("${sha256(tarball)}  $tarName\n")
        return tarball
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = ins.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
