import org.gradle.api.publish.PublishingExtension
import java.util.zip.ZipOutputStream

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-android — prefab AAR producer (RFC §3/§4/§5). Ships the per-ABI static `.a` + headers
// (arm64-v8a + x86_64 only, RFC §5 Rule D / D7) as a **prefab** AAR so a consumer's JNI shim
// `find_package(boringssl)`s crypto/ssl and links at NDK build time. The archives come from
// :boringssl-build (the binary factory); this module only PACKAGES + publishes them.
//
// It deliberately does NOT apply `boringssl.multiplatform-library`: a prefab AAR carries no Kotlin, and
// AGP's KMP-library DSL (`com.android.kotlin.multiplatform.library`) has no prefab-publishing surface.
// So — like :boringssl-bom (java-platform) and :boringssl-provision (kotlin-dsl) — it hand-rolls a
// focused `maven-publish` producer. The AAR is assembled deterministically as a zip; no AGP native
// build runs here. Budget (§5 ≤2.5 MiB/ABI on the linked subset) is gated in
// :boringssl-build:checkBoringSslAndroid, which this build depends on.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

plugins {
    `maven-publish`
    alias(libs.plugins.maven.publish)
    signing
}

group = providers.gradleProperty("publishedGroupId").get()
if (version.toString() == "unspecified") {
    version = "0.0.1-SNAPSHOT" // step 3 wires the shared version derivation (matches :boringssl-provision).
}

// Prefab ABIs — MUST match :boringssl-build's androidAbis (arm64-v8a + x86_64; RFC §5 Rule D / D7).
val abis = listOf("arm64-v8a", "x86_64")
val androidApi = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java)
    .named("libs").findVersion("boringsslAndroidApi").get().requiredVersion
val ndkMajor = 28 // the pinned NDK's major (informational in abi.json; keep in step with build-android.yaml).

val bsslBuild = project(":boringssl-build")
fun abiLibDir(abi: String) = bsslBuild.projectDir.resolve("libs/boringssl/android/$abi/lib")
fun abiIncludeDir(abi: String) = bsslBuild.projectDir.resolve("libs/boringssl/android/$abi/include")

// One prefab module per BoringSSL library. ssl depends on crypto (export_libraries), so a consumer of
// :ssl gets crypto's headers + archive on the link line transitively.
data class PrefabModule(val name: String, val libBase: String, val exports: List<String>)

val prefabModules =
    listOf(
        PrefabModule("crypto", "libcrypto", emptyList()),
        PrefabModule("ssl", "libssl", listOf(":crypto")),
    )

val stagingDir = layout.buildDirectory.dir("prefab-aar")

// ── Assemble the prefab tree (RFC §5 prefab schema v2) ──────────────────────────────────────────
val prepareAar =
    tasks.register("prepareAar") {
        group = "boringssl"
        description = "Assemble the prefab AAR tree from :boringssl-build's per-ABI Android archives."
        // Depend on the FULL Android check: builds the archives, link-smokes, AND gates the §5 budget —
        // so an over-budget or non-linking archive can never be packaged.
        dependsOn("${bsslBuild.path}:checkBoringSslAndroid")
        inputs.property("version", version.toString())
        inputs.property("api", androidApi)
        abis.forEach { abi ->
            inputs.dir(abiIncludeDir(abi))
            inputs.files(abiLibDir(abi).resolve("libcrypto.a"), abiLibDir(abi).resolve("libssl.a"))
        }
        outputs.dir(stagingDir)
        doLast {
            val root = stagingDir.get().asFile
            root.deleteRecursively(); root.mkdirs()

            // AAR shell: a minimal manifest + an empty classes.jar (a native-only AAR carries no code,
            // but AGP still expects classes.jar to be present).
            root.resolve("AndroidManifest.xml").writeText(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="com.ditchoom.boringssl.android">
                    <uses-sdk android:minSdkVersion="$androidApi" />
                </manifest>
                """.trimIndent() + "\n",
            )
            ZipOutputStream(root.resolve("classes.jar").outputStream()).close() // valid empty jar

            val prefab = root.resolve("prefab").apply { mkdirs() }
            prefab.resolve("prefab.json").writeText(
                """{"schema_version": 2, "name": "boringssl", "version": "${version.toString().substringBefore("-")}", "dependencies": []}""" + "\n",
            )

            prefabModules.forEach { m ->
                val modDir = prefab.resolve("modules/${m.name}").apply { mkdirs() }
                val exportsJson = m.exports.joinToString(", ") { "\"$it\"" }
                modDir.resolve("module.json").writeText(
                    """{"export_libraries": [$exportsJson], "library_name": "${m.libBase}"}""" + "\n",
                )
                // Headers: the openssl tree is arch-independent — ship it per module (ssl.h needs
                // crypto's base.h, and prefab exposes each module's include/ on the search path).
                abiIncludeDir(abis.first()).copyRecursively(modDir.resolve("include"), overwrite = true)
                abis.forEach { abi ->
                    val abiDir = modDir.resolve("libs/android.$abi").apply { mkdirs() }
                    abiDir.resolve("abi.json").writeText(
                        """{"abi": "$abi", "api": $androidApi, "ndk": $ndkMajor, "stl": "none", "static": true}""" + "\n",
                    )
                    abiLibDir(abi).resolve("${m.libBase}.a").copyTo(abiDir.resolve("${m.libBase}.a"), overwrite = true)
                }
            }
            logger.lifecycle("Assembled prefab AAR tree → $root")
        }
    }

// ── Zip the tree into a deterministic .aar ──────────────────────────────────────────────────────
val prefabAar =
    tasks.register<Zip>("prefabAar") {
        group = "boringssl"
        description = "Package the prefab tree into boringssl-android-<version>.aar."
        dependsOn(prepareAar)
        from(stagingDir)
        archiveBaseName.set("boringssl-android")
        archiveExtension.set("aar")
        destinationDirectory.set(layout.buildDirectory.dir("outputs/aar"))
        isReproducibleFileOrder = true
        isPreserveFileTimestamps = false
    }

tasks.named("assemble") { dependsOn(prefabAar) }

// ── Publishing (hand-rolled, mirrors :boringssl-bom — shared POM fields from root gradle.properties,
//    prose from this module's gradle.properties). Central-repo wiring lands with the release pipeline. ──
val pomName = (findProperty("POM_NAME") as String?)?.takeIf { it.isNotBlank() } ?: name
val pomDescription = (findProperty("POM_DESCRIPTION") as String?)?.takeIf { it.isNotBlank() }
    ?: error("POM_DESCRIPTION missing for :$name — add it to $name/gradle.properties (Central requires a POM description)")

publishing {
    publications {
        create<MavenPublication>("prefabAar") {
            artifactId = "boringssl-android"
            artifact(prefabAar)
            pom {
                name.set(pomName)
                description.set(pomDescription)
                packaging = "aar"
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
                organization {
                    name.set(providers.gradleProperty("developerOrg"))
                }
                scm {
                    connection.set(providers.gradleProperty("gitUrl"))
                    developerConnection.set(providers.gradleProperty("gitUrl"))
                    url.set(providers.gradleProperty("siteUrl"))
                }
            }
        }
    }
}

// ── Central-Portal transport (vanniktech) over the hand-rolled AAR publication ──
// The `prefabAar` publication above is complete (artifact + full POM); vanniktech supplies only the
// Central-Portal upload + signing, the same path as every other module. Signed Central publish on
// main-branch CI with the key present; local + PR builds publish unsigned to mavenLocal (same gate).
val signingKey = findProperty("signingInMemoryKey")
val signingPassword = findProperty("signingInMemoryKeyPassword")
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val shouldSignAndPublish = isMainBranchGithub && signingKey is String && signingPassword is String

if (shouldSignAndPublish) {
    signing {
        useInMemoryPgpKeys(signingKey as String, signingPassword as String)
    }
}
tasks.withType<Sign>().configureEach { onlyIf { shouldSignAndPublish } }

mavenPublishing {
    // No configure()/coordinates() — the `prefabAar` publication is hand-rolled above; vanniktech just
    // bundles + uploads (and signs) the existing publications to the Central Portal.
    if (shouldSignAndPublish) {
        publishToMavenCentral()
        signAllPublications()
    }
}
