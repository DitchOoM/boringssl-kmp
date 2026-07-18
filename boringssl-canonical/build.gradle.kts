import org.gradle.api.publish.PublishingExtension

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-canonical — the BINDINGS-FREE Kotlin/Native OWNER klib (RFC §2/§3/§12 D3).
//
// It ships exactly ONE ingredient: the canonical BoringSSL archive (libssl.a + libcrypto.a for the
// pinned commit), embedded into a cinterop klib with an EMPTY binding surface. When several K/N
// modules co-link one final binary (buffer + socket + webrtc-dtls), exactly ONE of them must EMBED
// the archive; every other adds its own headers-only ("external") cinterops that resolve against this
// single copy. Owning BoringSSL at the boringssl-kmp project level (here) — never leaked into
// buffer/socket — is what makes "exactly one unprefixed libcrypto in-process" (D3) hold.
//
// LINUX ONLY (linuxX64 + linuxArm64), NEVER Apple: on Apple, quiche force-loads its own self-contained
// BoringSSL and consumers use CryptoKit/Network.framework, so a second owner there would collide.
//
// This is a HAND-ROLLED module — it deliberately does NOT apply `boringssl.multiplatform-library`
// (that convention registers jvm/android/apple, which an owner must not have) nor the
// `com.ditchoom.boringssl.provision` plugin (a sibling subproject's plugin is not applyable inside the
// same build without publishing it first — it would break a clean `./gradlew projects`). Instead it
// replicates the plugin's `cinterop(embedArchive = true)` behaviour EXACTLY the way :boringssl-testsuite
// does: a task injects the resolved `libraryPaths` + `staticLibraries` (+ the linux pthread/dl floor)
// into the bindings-free def, so K/N copies the archive into the published `-cinterop` klib's
// `included/`. The resulting artifact is identical to the provision plugin's owner-mode output; the
// plugin's own unit tests (BoringSslCinteropEmbedArchiveTest) cover that emission at the plugin level.
//
// Published to Maven Central (linux CI lanes only). Gradle Module Metadata is MANDATORY: the embedded
// archive rides a SEPARATE `-cinterop` klib artifact referenced from the `.module` file, NOT the POM.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

plugins {
    // A MINIMAL build-logic convention that ONLY applies Kotlin Multiplatform + vanniktech + signing
    // from build-logic's classloader — the same one every other KMP module uses — so the owner's KGP
    // shares one classloader (and one KotlinNativeBundleBuildService) with :boringssl-jvm/:boringssl-
    // testsuite. It does NOT register jvm/android/apple; all targets + wiring are hand-rolled below.
    // See build-logic/src/main/kotlin/boringssl.canonical-owner.gradle.kts for why a raw
    // `alias(libs.plugins.kotlin.multiplatform)` in this module would fail configuration.
    id("boringssl.canonical-owner")
}

group = "com.ditchoom.boringssl"
// The release version is passed by merged.yaml (`-Pversion=<ver>`); local/dev builds fall back to a
// SNAPSHOT. -Pversion sets project.version directly, so this only supplies the dev fallback. This is
// the module's PUBLISHED coordinate version — distinct from the BoringSSL commit and the bundle name.
version = (findProperty("version") as? String)?.takeIf { it.isNotBlank() && it != "unspecified" } ?: "0.0.1-SNAPSHOT"

// Not a published API surface in the binary-compatibility sense (empty bindings). No BCV plugin is
// applied here, so there are no *ApiCheck/*ApiDump tasks; the defensive disable keeps the module
// inert should the root ever wire aggregate api tasks across subprojects.
tasks.matching { it.name.endsWith("ApiCheck") || it.name.endsWith("ApiDump") }.configureEach {
    enabled = false
}

repositories {
    mavenCentral()
}

// Host detection via System properties (never Kotlin/Native's HostManager.host — it throws on the
// linux-aarch64 CI runner, which has NO K/N host target). On a linux-aarch64 host, skip K/N target
// registration entirely — same guard the convention plugin and :boringssl-testsuite use.
val hostOsName = System.getProperty("os.name").orEmpty()
val hostOsArch = System.getProperty("os.arch").orEmpty()
val knHostSupported =
    !(hostOsName.startsWith("Linux", ignoreCase = true) && hostOsArch in listOf("aarch64", "arm64"))

// The archive + headers source: :boringssl-build's per-triple `libs/boringssl/<triple>/{lib,include}`,
// produced by buildBoringSslArchives<Cap> — the SAME wiring :boringssl-testsuite uses (NOT the
// provision plugin's tarball cache, which resolves at configuration time and cannot be produced within
// the same build invocation).
val bsslBuild = project(":boringssl-build")
val baseDef = layout.projectDirectory.file("src/nativeInterop/cinterop/boringsslcanonical.def")

// Per-triple: inject the resolved absolute libraryPaths + staticLibraries (the full bundle: libssl.a +
// libcrypto.a — an owner embeds the whole archive so ANY consumer's crypto AND ssl refs resolve; a
// buffer-only consumer's --gc-sections then drops unused libssl at zero size cost) + the linux
// pthread/dl floor into the bindings-free def, before `---`. Runs at task time (doLast), so a
// config-only host with no archive (e.g. macOS running `./gradlew projects`) still configures cleanly.
fun registerGenerateCanonicalDef(triple: String): TaskProvider<Task> {
    val cap = triple.replaceFirstChar { it.uppercase() }
    val provisioned = bsslBuild.projectDir.resolve("libs/boringssl/$triple")
    val out = layout.buildDirectory.file("generated/cinterop/boringsslcanonical-$triple.def")
    return tasks.register("generateCanonicalDef$cap") {
        // Depend on the ARCHIVE stage only — the owner embeds libssl.a/libcrypto.a and never touches
        // libboringsslffi.so, so there is no need to trigger the .so link.
        dependsOn("${bsslBuild.path}:buildBoringSslArchives$cap")
        inputs.file(baseDef)
        outputs.file(out)
        doLast {
            val libDir = provisioned.resolve("lib")
            val base = baseDef.asFile.readText()
            val sep = base.indexOf("\n---")
            require(sep > 0) { "boringsslcanonical.def missing the '---' separator" }
            val injected =
                base.substring(0, sep) +
                    "\nlibraryPaths = ${libDir.absolutePath}" +
                    "\nstaticLibraries = libssl.a libcrypto.a" +
                    // glibc floors keep pthread + dl in separate libs (linux). Recorded in the owner's
                    // cinterop manifest so it propagates to every consumer's final link.
                    "\nlinkerOpts = -lpthread -ldl\n" +
                    base.substring(sep)
            out.get().asFile.apply {
                parentFile.mkdirs()
                writeText(injected)
            }
        }
    }
}

kotlin {
    jvmToolchain(21)

    applyDefaultHierarchyTemplate()

    if (knHostSupported) {
        // LINUX ONLY — never Apple (see header). linuxArm64 cross-compiles from a linux-x64 host.
        val triples = listOf("linuxX64", "linuxArm64")
        triples.forEach { triple ->
            val gen = registerGenerateCanonicalDef(triple)
            val cap = triple.replaceFirstChar { it.uppercase() }
            // Register the target by its konan preset name, then attach the bindings-free cinterop.
            when (triple) {
                "linuxX64" -> linuxX64()
                "linuxArm64" -> linuxArm64()
            }
            val target = kotlin.targets.getByName(triple) as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
            target.compilations.getByName("main").cinterops.create("boringsslcanonical") {
                defFile(project.file("build/generated/cinterop/boringsslcanonical-$triple.def"))
                // No includeDirs: the def is header-less (empty binding surface) — only the archive is
                // shipped. Consumers bring their own headers via their own external cinterops.
            }
            // The cinterop must see its generated def (and thus the built archive) first.
            tasks.matching { it.name == "cinteropBoringsslcanonical$cap" }.configureEach {
                dependsOn(gen)
            }
        }
    }
}

// ── Publishing / signing (POM prose from module gradle.properties; shared fields from root) ──
// Mirrors the convention plugin's gate: sign + publish to Central only on main-branch CI with the key
// present; local + PR builds publish UNSIGNED to mavenLocal. vanniktech auto-detects the KMP project
// type and publishes every target klib + the `-cinterop` klib as separate GMM-referenced artifacts.
val pomName = (findProperty("POM_NAME") as String?)?.takeIf { it.isNotBlank() } ?: name
val pomDescription = (findProperty("POM_DESCRIPTION") as String?)?.takeIf { it.isNotBlank() }
    ?: error("POM_DESCRIPTION missing for :$name — add it to $name/gradle.properties (Central requires a POM description)")
val publishedGroupId = providers.gradleProperty("publishedGroupId").get()
val siteUrl = providers.gradleProperty("siteUrl").get()
val gitUrl = providers.gradleProperty("gitUrl").get()
val licenseName = providers.gradleProperty("licenseName").get()
val licenseUrl = providers.gradleProperty("licenseUrl").get()
val developerOrg = providers.gradleProperty("developerOrg").get()
val developerName = providers.gradleProperty("developerName").get()
val developerEmail = providers.gradleProperty("developerEmail").get()
val developerId = providers.gradleProperty("developerId").get()

val signingKey = findProperty("signingInMemoryKey")
val signingPassword = findProperty("signingInMemoryKeyPassword")
val isMainBranchGithub = System.getenv("GITHUB_REF") == "refs/heads/main"
val shouldSignAndPublish = isMainBranchGithub && signingKey is String && signingPassword is String

if (shouldSignAndPublish) {
    signing {
        useInMemoryPgpKeys(signingKey as String, signingPassword as String)
        sign(extensions.getByType(PublishingExtension::class.java).publications)
    }
}

mavenPublishing {
    if (shouldSignAndPublish) {
        publishToMavenCentral()
        signAllPublications()
    }
    coordinates(publishedGroupId, name, version.toString())
    pom {
        name.set(pomName)
        description.set(pomDescription)
        url.set(siteUrl)
        licenses {
            license {
                name.set(licenseName)
                url.set(licenseUrl)
            }
        }
        developers {
            developer {
                id.set(developerId)
                name.set(developerName)
                email.set(developerEmail)
            }
        }
        organization {
            name.set(developerOrg)
        }
        scm {
            connection.set(gitUrl)
            developerConnection.set(gitUrl)
            url.set(siteUrl)
        }
    }
}
