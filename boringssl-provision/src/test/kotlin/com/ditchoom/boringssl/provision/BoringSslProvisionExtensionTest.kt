package com.ditchoom.boringssl.provision

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Unit tests of [BoringSslProvisionExtension]'s resolve/verify/extract pipeline via [ProjectBuilder].
 *
 * Every tarball fixture is built PROGRAMMATICALLY at test runtime (stub text files → `tar czf`) —
 * committed binaries are forbidden (RFC §8 directive #1). The `-PboringsslLocalBundle` property flow
 * itself needs a real Gradle invocation and lives in [BoringSslLocalBundlePropertyFunctionalTest].
 */
class BoringSslProvisionExtensionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val testVersion = "0.0.1-test"
    private val triple = "linuxX64"
    private val tarName get() = "boringssl-$testVersion-$triple.tar.gz"

    private lateinit var project: Project
    private lateinit var extension: BoringSslProvisionExtension

    @Before
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("project")).build()
        project.pluginManager.apply(BoringSslProvisionPlugin::class.java)
        extension = project.extensions.getByType(BoringSslProvisionExtension::class.java)
        extension.version = testVersion
        extension.cacheRoot = tmp.newFolder("cache")
    }

    @Test
    fun `apply registers the boringssl extension`() {
        assertNotNull(project.extensions.findByName("boringssl"))
    }

    @Test
    fun `happy path - local tarball with sidecar extracts and writes a sha-keyed marker`() {
        val dist = tmp.newFolder("dist")
        val tarball = createBundleTarball(dist, "bits-A")
        extension.localDist = dist

        val dir = extension.boringsslDir(triple)

        assertEquals(File(extension.cacheRoot, "$testVersion/$triple"), dir)
        assertEquals("bits-A", File(dir, "lib/libcrypto.a").readText())
        assertTrue(File(dir, "include/openssl/base.h").exists())
        val expectedMarker = ".provisioned-$testVersion-${sha256(tarball).take(12)}"
        assertTrue("missing marker $expectedMarker", File(dir, expectedMarker).exists())
    }

    @Test
    fun `same bits re-provision is a no-op (marker respected)`() {
        val dist = tmp.newFolder("dist")
        createBundleTarball(dist, "bits-A")
        extension.localDist = dist

        val dir = extension.boringsslDir(triple)
        val sentinel = File(dir, "sentinel.txt").apply { writeText("survives a no-op") }

        assertEquals(dir, extension.boringsslDir(triple))
        assertTrue("re-provisioning identical bits must not re-extract", sentinel.exists())
    }

    @Test
    fun `changed tarball bits bust the marker and force a clean re-extraction`() {
        val dist = tmp.newFolder("dist")
        createBundleTarball(dist, "bits-A")
        extension.localDist = dist

        val dir = extension.boringsslDir(triple)
        assertEquals("bits-A", File(dir, "lib/libcrypto.a").readText())
        val sentinel = File(dir, "sentinel.txt").apply { writeText("stale") }

        // Same version, DIFFERENT bits (+ refreshed sidecar) — the local-iteration staleness trap.
        val second = createBundleTarball(dist, "bits-B")
        assertEquals(dir, extension.boringsslDir(triple))

        assertEquals("bits-B", File(dir, "lib/libcrypto.a").readText())
        assertFalse("stale extraction must be cleaned before re-extracting", sentinel.exists())
        val markers = dir.listFiles().orEmpty().filter { it.name.startsWith(".provisioned-") }
        assertEquals("exactly one (new) marker after re-extraction", 1, markers.size)
        assertEquals(".provisioned-$testVersion-${sha256(second).take(12)}", markers.single().name)
    }

    @Test
    fun `checksum mismatch against the sidecar is a hard error`() {
        val dist = tmp.newFolder("dist")
        val tarball = createBundleTarball(dist, "bits-A")
        // Corrupt the tarball AFTER the sidecar was written → sidecar is now stale.
        tarball.appendBytes(byteArrayOf(0))
        extension.localDist = dist

        val e = assertThrows(GradleException::class.java) { extension.boringsslDir(triple) }
        assertTrue("message should name the mismatch: ${e.message}", e.message!!.contains("checksum mismatch"))
        assertFalse(File(extension.cacheRoot, "$testVersion/$triple/lib/libcrypto.a").exists())
    }

    @Test
    fun `missing baked checksum with no local tarball is a hard error (no TOFU)`() {
        extension.checksums = emptyMap()

        val e = assertThrows(GradleException::class.java) { extension.boringsslDir(triple) }
        assertTrue("message should point at checksums/localDist: ${e.message}", e.message!!.contains("No baked-in checksum"))
    }

    @Test
    fun `provisioned cache keeps serving after the tarball source becomes unresolvable`() {
        val dist = tmp.newFolder("dist")
        createBundleTarball(dist, "bits-A")
        extension.localDist = dist
        val dir = extension.boringsslDir(triple)

        // Producer clean between rebuilds: dist wiped, no baked checksum (the migration window). The
        // already-verified extraction (sha-keyed marker + lib/ + include/) must keep serving.
        extension.localDist = null
        extension.checksums = emptyMap()

        assertEquals(dir, extension.boringsslDir(triple))
        assertEquals("bits-A", File(dir, "lib/libcrypto.a").readText())
    }

    @Test
    fun `missing sidecar falls back to self-hash (break-glass) and still provisions`() {
        val dist = tmp.newFolder("dist")
        val tarball = createBundleTarball(dist, "bits-A", sidecar = false)
        extension.localDist = dist

        val dir = extension.boringsslDir(triple)

        assertEquals("bits-A", File(dir, "lib/libcrypto.a").readText())
        assertTrue(File(dir, ".provisioned-$testVersion-${sha256(tarball).take(12)}").exists())
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────────────────────────

    /** Build `boringssl-<version>-<triple>.tar.gz` (+ optional `.sha256` sidecar) from stub text files. */
    private fun createBundleTarball(distDir: File, bits: String, sidecar: Boolean = true): File {
        val stage = tmp.newFolder()
        File(stage, "lib").mkdirs()
        File(stage, "include/openssl").mkdirs()
        File(stage, "lib/libcrypto.a").writeText(bits)
        File(stage, "include/openssl/base.h").writeText("/* stub header */\n")
        val tarball = File(distDir, tarName)
        val code = ProcessBuilder("tar", "czf", tarball.absolutePath, "-C", stage.absolutePath, "lib", "include")
            .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        check(code == 0) { "fixture tar failed (exit $code)" }
        if (sidecar) File(distDir, "$tarName.sha256").writeText("${sha256(tarball)}  $tarName\n")
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
