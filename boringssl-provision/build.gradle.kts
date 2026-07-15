import com.vanniktech.maven.publish.GradlePublishPlugin

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-provision — the published Gradle PLUGIN (RFC §3). `id("com.ditchoom.boringssl.provision")`.
//
// Downloads + sha256-verifies the per-triple tarballs (built by :boringssl-build, hosted on GitHub
// Releases) into `~/.gradle/caches/ditchoom-boringssl/<ver>/<triple>/` and exposes
// `boringsslDir(triple) → {include, lib}`. This is the ENTIRE native-consumer surface — a ~10-line
// swap for today's ~150-line inline cinterop task (RFC §7).
//
// Published to **Maven Central + the Gradle Plugin Portal** (RFC §3):
//   • Maven Central — via vanniktech (the same Central-Portal path the convention modules use), so the
//     plugin + its marker resolve as a normal dependency and the BOM can pin it.
//   • Gradle Plugin Portal — via `com.gradle.plugin-publish`, so `plugins { id(...) }` resolves it.
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

// ── Publishing: Maven Central (vanniktech, the convention modules' Central-Portal path) + signing ──
// Signed Central publish only on main-branch CI with the key present; local + PR builds publish
// unsigned to mavenLocal (identical gate to the convention plugin). The Plugin-Portal publish is the
// separate `publishPlugins` task (com.gradle.plugin-publish), keyed on the Portal secret in merged.yaml.
val signingKey = findProperty("signingInMemoryKey")
val signingPassword = findProperty("signingInMemoryKeyPassword")
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val shouldSignAndPublish = isMainBranchGithub && signingKey is String && signingPassword is String

if (shouldSignAndPublish) {
    signing {
        useInMemoryPgpKeys(signingKey as String, signingPassword as String)
    }
}

// com.gradle.plugin-publish eagerly wires a Sign task for the plugin-marker publication; without a key
// (local + PR builds) it has no signatory and would fail publishToMavenLocal. Gate ALL Sign tasks on
// the key being present so unsigned local/PR publishing works, exactly like the convention modules.
tasks.withType<Sign>().configureEach { onlyIf { shouldSignAndPublish } }

mavenPublishing {
    // java-gradle-plugin project publishing WITH com.gradle.plugin-publish → vanniktech adds the
    // javadoc/sources jars, signs, and Central-uploads the publications plugin-publish created.
    configure(GradlePublishPlugin())
    if (shouldSignAndPublish) {
        publishToMavenCentral()
        signAllPublications()
    }
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
