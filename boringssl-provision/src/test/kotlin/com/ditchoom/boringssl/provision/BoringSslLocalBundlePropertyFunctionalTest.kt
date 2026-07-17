package com.ditchoom.boringssl.provision

import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * TestKit coverage of the `-PboringsslLocalBundle` → [BoringSslProvisionExtension.localDist] mapping —
 * the one seam that needs a REAL Gradle invocation ([org.gradle.testfixtures.ProjectBuilder] builds
 * don't carry `-P` gradle properties). The build under test only prints the resolved `localDist`; the
 * resolve/verify/extract pipeline itself is covered in [BoringSslProvisionExtensionTest].
 */
class BoringSslLocalBundlePropertyFunctionalTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun consumerProject(boringsslBlock: String): File {
        val dir = tmp.newFolder("consumer")
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"consumer\"\n")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins { id("com.ditchoom.boringssl.provision") }

            boringssl {
            $boringsslBlock
                println("resolvedLocalDist=" + localDist?.absolutePath)
            }
            """.trimIndent() + "\n",
        )
        return dir
    }

    private fun run(dir: File, vararg args: String) =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments("help", *args)
            .build()

    @Test
    fun `relative property path seeds localDist against the root project dir`() {
        val dir = consumerProject("")

        val output = run(dir, "-PboringsslLocalBundle=candidate/dist").output

        val resolved = resolvedLocalDist(output)
        assertTrue("expected an absolute path, got $resolved", File(resolved).isAbsolute)
        assertTrue(
            "expected root-dir-relative candidate/dist, got $resolved",
            resolved.endsWith("${File.separator}candidate${File.separator}dist"),
        )
    }

    @Test
    fun `absolute property path is taken as-is`() {
        val dir = consumerProject("")
        val absolute = tmp.newFolder("abs-dist").absolutePath

        val output = run(dir, "-PboringsslLocalBundle=$absolute").output

        assertTrue("expected $absolute, got: ${resolvedLocalDist(output)}", resolvedLocalDist(output) == absolute)
    }

    @Test
    fun `explicit localDist assignment in the build script wins over the property`() {
        val dir = consumerProject("    localDist = file(\"explicit-dist\")")

        val output = run(dir, "-PboringsslLocalBundle=property-dist").output

        val resolved = resolvedLocalDist(output)
        assertTrue("explicit assignment must win, got $resolved", resolved.endsWith("${File.separator}explicit-dist"))
    }

    @Test
    fun `without the property localDist stays unset`() {
        val dir = consumerProject("")

        val output = run(dir).output

        assertTrue("expected null localDist, got: ${resolvedLocalDist(output)}", resolvedLocalDist(output) == "null")
    }

    private fun resolvedLocalDist(output: String): String =
        output.lineSequence().firstOrNull { it.startsWith("resolvedLocalDist=") }
            ?.removePrefix("resolvedLocalDist=")
            ?: error("build output is missing the resolvedLocalDist marker:\n$output")
}
