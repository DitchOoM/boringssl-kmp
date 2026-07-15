import com.vanniktech.maven.publish.JavaPlatform
import org.gradle.api.artifacts.VersionCatalogsExtension

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-bom — the published BOM (RFC §3). Pins every boringssl-kmp coordinate to one version and
// records the canonical BoringSSL commit + its quiche ABI anchor, so a consumer imports one platform
// and gets a coherent set (RFC §7: webrtc-dtls W4 pins the whole matrix via this BOM).
//
// Published to Maven Central. Gradle produces the UNSIGNED BOM publication into the local maven repo;
// the release workflow (publish-to-central.yaml) GPG-signs + uploads the bundle to the Central Portal.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

plugins {
    `java-platform`
    alias(libs.plugins.maven.publish)
}

group = "com.ditchoom.boringssl"
// The release version is passed by merged.yaml (`-Pversion=<ver>`); local/dev builds fall back to a
// SNAPSHOT. -Pversion sets project.version directly, so this only supplies the dev fallback.
version = (findProperty("version") as? String)?.takeIf { it.isNotBlank() && it != "unspecified" } ?: "0.0.1-SNAPSHOT"

// Canonical anchor — read from the catalog (the ONE place the 40-hex literal lives, RFC §8). Recorded
// here so the BOM POM documents the exact BoringSSL tree + quiche ABI it pins against.
private val libs =
    extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
val canonicalCommit: String = libs.findVersion("boringssl").get().requiredVersion
val quicheAbi: String = libs.findVersion("boringsslQuicheAbi").get().requiredVersion

dependencies {
    constraints {
        // The coordinates the BOM pins — all at this release's version (from -Pversion). The BOM never
        // pins itself. webrtc-dtls W4 imports this platform to lock the whole matrix (RFC §7).
        api("com.ditchoom.boringssl:boringssl-provision:${project.version}")
        api("com.ditchoom.boringssl:boringssl-jvm:${project.version}")
        api("com.ditchoom.boringssl:boringssl-android:${project.version}")
    }
}

mavenPublishing {
    // Unsigned local publish; the release workflow owns signing + the Central Portal upload.
    configure(JavaPlatform())
    coordinates("com.ditchoom.boringssl", "boringssl-bom", version.toString())
    pom {
        name.set("BoringSSL BOM")
        // Canonical anchor recorded in the POM (RFC §8: release notes state commit + quiche anchor).
        description.set(
            "Bill-of-materials pinning all com.ditchoom.boringssl coordinates. " +
                "Canonical BoringSSL commit $canonicalCommit (ABI anchor: $quicheAbi).",
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
