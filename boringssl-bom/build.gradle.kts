import org.gradle.api.artifacts.VersionCatalogsExtension

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-bom — the published BOM (RFC §3). Pins every boringssl-kmp coordinate to one version and
// records the canonical BoringSSL commit + its quiche ABI anchor, so a consumer imports one platform
// and gets a coherent set (RFC §7: webrtc-dtls W4 pins the whole matrix via this BOM).
//
// Published to Maven Central. `java-platform` is a core Gradle plugin — no external resolution.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

plugins {
    `java-platform`
    `maven-publish`
}

group = "com.ditchoom.boringssl"

// Canonical anchor — read from the catalog (the ONE place the 40-hex literal lives, RFC §8). Recorded
// here so the BOM POM documents the exact BoringSSL tree + quiche ABI it pins against.
private val libs =
    extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
val canonicalCommit: String = libs.findVersion("boringssl").get().requiredVersion
val quicheAbi: String = libs.findVersion("boringsslQuicheAbi").get().requiredVersion

dependencies {
    constraints {
        // Coordinates the BOM pins. Versions resolve at publish time from the shared version
        // derivation (step 3); listed here so the platform's constraint set is explicit.
        // api("com.ditchoom.boringssl:boringssl-provision:${project.version}")
        // api("com.ditchoom.boringssl:boringssl-jvm:${project.version}")
        // api("com.ditchoom.boringssl:boringssl-android:${project.version}")
    }
}

publishing {
    publications {
        create<MavenPublication>("bom") {
            from(components["javaPlatform"])
            pom {
                name.set("BoringSSL BOM")
                // Canonical anchor recorded in the POM (RFC §8: release notes state commit + quiche anchor).
                description.set(
                    "Bill-of-materials pinning all com.ditchoom.boringssl coordinates. " +
                        "Canonical BoringSSL commit $canonicalCommit (ABI anchor: $quicheAbi).",
                )
            }
        }
    }
}
