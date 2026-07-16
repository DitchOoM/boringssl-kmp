// ─────────────────────────────────────────────────────────────────────────────────────────────────
// samples/cinterop-consumer — a STANDALONE build (NOT part of the root build; not `include`d) that
// resolves the published `com.ditchoom.boringssl.provision` plugin exactly as an external K/N consumer
// would, and proves `boringssl.cinterop(target)` gives one-line BoringSSL bindings (RFC §4 / item 1).
//
// Run it against a locally-published plugin + a locally-built bundle:
//   ./gradlew :boringssl-provision:publishToMavenLocal :boringssl-build:packageBoringSslMacosArm64
//   ./gradlew -p samples/cinterop-consumer macosArm64Test
// (defaults below match that dev flow; CI passes -PboringsslPluginVersion / -PboringsslBundleVersion.)
// ─────────────────────────────────────────────────────────────────────────────────────────────────
pluginManagement {
    repositories {
        mavenLocal() // the locally-published provision plugin (publishToMavenLocal)
        gradlePluginPortal()
        mavenCentral()
        google()
    }
    // Pin the provision plugin version from a property so the same sample drives dev + CI.
    val provisionVersion = providers.gradleProperty("boringsslPluginVersion").orNull ?: "0.0.1-SNAPSHOT"
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.ditchoom.boringssl.provision") useVersion(provisionVersion)
        }
    }
}

rootProject.name = "cinterop-consumer"
