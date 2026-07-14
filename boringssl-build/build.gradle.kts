import org.gradle.api.artifacts.VersionCatalogsExtension

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-build — PLAIN non-KMP Gradle project (RFC §3). Per-triple cmake cross-compile + packaging
// → `boringssl-<ver>-<triple>.tar.gz` (+ `.sha256`, `SHA256SUMS`, `provenance.json`).
//
// NOT published to Maven Central: the heavy static tarballs go to GitHub Releases (RFC §3 channel
// split). `libs/**` is gitignored and produced in CI (see README.md).
//
// ⚠️ STUB — migration step 1 only. The real cmake cross-compile (the unified `boringssl.native-build`
// task factory that ports buffer-crypto + socket + quiche cmake args into one place) is STEP 2
// (RFC §10). The tasks below only announce intent so the wiring + graph exist and configure cleanly.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

plugins {
    base
}

// The canonical BoringSSL commit is read from the version catalog — the ONE place the 40-hex literal
// lives (RFC §8 single-pin directive). No commit string is duplicated into any task.
val boringsslCommit =
    extensions
        .getByType(VersionCatalogsExtension::class.java)
        .named("libs")
        .findVersion("boringssl")
        .get()
        .requiredVersion

// Per-triple stub tasks. Step 2 replaces each `doLast` body with the real cmake invocation + packaging.
// Only the two proven-from-code Linux triples are wired now (RFC §10 step 2 "port the Linux path
// first"); Apple/Android/tvOS/watchOS triples arrive in steps 7–8 behind their spikes.
fun registerBuildStub(taskName: String, triple: String) =
    tasks.register(taskName) {
        group = "boringssl"
        description = "STUB (step 2): cross-compile BoringSSL @ $boringsslCommit for $triple."
        doLast {
            logger.lifecycle("TODO step 2: cross-compile BoringSSL @ $boringsslCommit for $triple")
        }
    }

val buildLinuxX64 = registerBuildStub("buildBoringSslLinuxX64", "linuxX64")
val buildLinuxArm64 = registerBuildStub("buildBoringSslLinuxArm64", "linuxArm64")

// Convenience aggregate for the linux-first lane.
tasks.register("buildBoringSslLinux") {
    group = "boringssl"
    description = "STUB (step 2): build all currently-wired Linux BoringSSL triples."
    dependsOn(buildLinuxX64, buildLinuxArm64)
}
