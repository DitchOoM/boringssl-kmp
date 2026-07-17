import com.vanniktech.maven.publish.GradlePublishPlugin

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-provision — the published Gradle PLUGIN (RFC §3). `id("com.ditchoom.boringssl.provision")`.
//
// Downloads + sha256-verifies the per-triple tarballs (built by :boringssl-build, hosted on GitHub
// Releases) into `~/.gradle/caches/ditchoom-boringssl/<ver>/<triple>/` and exposes
// `boringsslDir(triple) → {include, lib}`. This is the ENTIRE native-consumer surface — a ~10-line
// swap for today's ~150-line inline cinterop task (RFC §7).
//
// Published to **Maven Central** (RFC §3): the plugin jar + its marker so `plugins { id(...) }` resolves
// from Central (via `pluginManagement { repositories { mavenCentral() } }`) and the BOM can pin it. The
// Gradle Plugin Portal is a deferred nice-to-have (no Portal secret is configured; `com.gradle.plugin-
// publish` is applied for the marker publication, not to publish to the Portal).
//
// Gradle publishes UNSIGNED to the local maven repo; the release workflow (publish-to-central.yaml)
// GPG-signs + curl-uploads the bundle to the Central Portal — the shared DitchOoM transport.
//
// The release version + baked-in per-triple checksums are supplied by merged.yaml at release time
// (`-Pversion=<ver>` + `bakeChecksums`), so the shipped plugin verifies downloads with NO trust-on-
// first-use (RFC §8 directive #4). See BoringSslProvisionExtension.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

plugins {
    `kotlin-dsl`
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.plugin.publish)
    signing
}

group = "com.ditchoom.boringssl"
// The release version is passed by merged.yaml (`-Pversion=<ver>`, computed once from the shared
// derivation); local/dev builds fall back to a SNAPSHOT. -Pversion sets project.version directly, so
// this only supplies the dev fallback when it is absent.
version = (findProperty("version") as? String)?.takeIf { it.isNotBlank() && it != "unspecified" } ?: "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// TestKit builds resolve the plugin from `withPluginClasspath()` (the java-gradle-plugin metadata =
// the plugin's RUNTIME classpath, which excludes the compileOnly KGP). Gradle's extension decoration
// reflects over the `cinterop(KotlinNativeTarget, …)` signature, so KGP must ride along for the build
// under test — a dedicated configuration keeps it out of the shipped plugin's own dependencies.
val testKitPluginClasspath: Configuration by configurations.creating { isCanBeConsumed = false }

dependencies {
    // The `boringssl.cinterop(target)` helper (RFC §4) types against KotlinNativeTarget. compileOnly:
    // the Kotlin Gradle plugin is ALWAYS on the classpath of the consumer build that applies this
    // plugin (it applies `kotlin("multiplatform")`), so it must not be bundled into or forced by the
    // provision plugin's own runtime — only visible at compile time.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")

    // Plugin unit + functional tests (src/test). Plain JUnit4 (no junit entry exists in the catalog;
    // kotlin-test is skipped to keep the embedded-Kotlin compiler off 2.4-metadata test libs). KGP is
    // testRuntimeOnly for the same extension-decoration reason as testKitPluginClasspath above.
    testImplementation("junit:junit:4.13.2")
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    testKitPluginClasspath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
}

tasks.named<org.gradle.plugin.devel.tasks.PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(testKitPluginClasspath)
}

tasks.withType<Test>().configureEach { useJUnit() }

// ── Baked-in checksums resource (no TOFU — RFC §8 directive #4) ───────────────────────────────────
// bakeChecksums reads a SHA256SUMS file (the release job's aggregated tarball digests) and writes
// `boringssl-provision.properties` into a generated resource dir packed into the plugin jar. The
// extension loads it at apply-time, so the SHIPPED plugin already knows every triple's checksum for its
// own release version. Absent (dev builds), the resource is version-only → the extension keeps its
// empty defaults and consumers use `localDist` (the migration-window path).
val bakedResourceDir = layout.buildDirectory.dir("generated/checksums")

val bakeChecksums =
    tasks.register("bakeChecksums") {
        group = "publishing"
        description = "Bake <triple>->sha256 (from -PchecksumsFile=<SHA256SUMS>) into the plugin jar for version $version."
        val checksumsFile = (findProperty("checksumsFile") as String?)?.let { file(it) }
        inputs.property("version", version.toString())
        checksumsFile?.let { inputs.file(it) }
        outputs.dir(bakedResourceDir)
        doLast {
            val out = bakedResourceDir.get().file("boringssl-provision.properties").asFile
            out.parentFile.mkdirs()
            if (checksumsFile == null || !checksumsFile.exists()) {
                logger.lifecycle("bakeChecksums: no -PchecksumsFile — writing version-only resource (dev build, no baked checksums).")
                out.writeText("version=$version\n")
                return@doLast
            }
            // SHA256SUMS lines: "<sha256>  boringssl-<version>-<triple>.tar.gz". Extract triple->sha.
            val re = Regex("""^([0-9a-fA-F]{64})\s+boringssl-\Q$version\E-(.+?)\.tar\.gz$""")
            val entries =
                checksumsFile.readLines().mapNotNull { line ->
                    re.find(line.trim())?.let { it.groupValues[2] to it.groupValues[1].lowercase() }
                }.sortedBy { it.first }
            require(entries.isNotEmpty()) {
                "bakeChecksums: no `boringssl-$version-<triple>.tar.gz` entries in ${checksumsFile.absolutePath} — wrong version or empty SHA256SUMS?"
            }
            val body =
                buildString {
                    append("version=$version\n")
                    entries.forEach { (triple, sha) -> append("$triple=$sha\n") }
                }
            out.writeText(body)
            logger.lifecycle("bakeChecksums: baked ${entries.size} checksums for $version → ${out.name}")
        }
    }

// Register the generated dir via the TASK provider so EVERY consumer (processResources, sourcesJar,
// …) inherits the dependency on bakeChecksums — not just processResources.
sourceSets.named("main") { resources.srcDir(bakeChecksums) }

gradlePlugin {
    website.set(providers.gradleProperty("siteUrl"))
    vcsUrl.set(providers.gradleProperty("siteUrl"))
    plugins {
        create("boringsslProvision") {
            id = "com.ditchoom.boringssl.provision"
            implementationClass = "com.ditchoom.boringssl.provision.BoringSslProvisionPlugin"
            displayName = "DitchOoM BoringSSL provision"
            description = "Downloads + sha256-verifies canonical BoringSSL bundles into the Gradle cache."
            tags.set(listOf("boringssl", "kotlin-multiplatform", "native", "cinterop", "cryptography"))
        }
    }
}

// ── Publishing: UNSIGNED to the local maven repo; the release workflow signs + uploads to Central ──
// com.gradle.plugin-publish eagerly wires a Sign task for the plugin-marker publication; since signing
// is owned by publish-to-central.yaml (GPG), disable Gradle signing entirely so publishToMavenLocal
// produces the clean unsigned bundle the workflow expects.
tasks.withType<Sign>().configureEach { enabled = false }

mavenPublishing {
    // java-gradle-plugin project WITH com.gradle.plugin-publish → publishes the plugin jar + its marker;
    // vanniktech adds the Central-required sources/javadoc jars + the POM. No publishToMavenCentral()/
    // signAllPublications() — the release workflow owns signing + the Central Portal upload.
    configure(GradlePublishPlugin())
    coordinates("com.ditchoom.boringssl", "boringssl-provision", version.toString())
    pom {
        name.set((findProperty("POM_NAME") as String?) ?: "BoringSSL Provision Plugin")
        description.set(
            (findProperty("POM_DESCRIPTION") as String?)
                ?: "Downloads + sha256-verifies canonical BoringSSL bundles into the Gradle cache.",
        )
        url.set(providers.gradleProperty("siteUrl"))
        licenses {
            license {
                name.set(providers.gradleProperty("licenseName"))
                url.set(providers.gradleProperty("licenseUrl"))
            }
        }
        developers {
            developer {
                id.set(providers.gradleProperty("developerId"))
                name.set(providers.gradleProperty("developerName"))
                email.set(providers.gradleProperty("developerEmail"))
            }
        }
        organization { name.set(providers.gradleProperty("developerOrg")) }
        scm {
            connection.set(providers.gradleProperty("gitUrl"))
            developerConnection.set(providers.gradleProperty("gitUrl"))
            url.set(providers.gradleProperty("siteUrl"))
        }
    }
}
