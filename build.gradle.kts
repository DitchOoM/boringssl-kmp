// Root build file for the multi-module com.ditchoom.boringssl:boringssl-kmp project.
// Per-module build logic (KMP targets, publishing, versioning, ktlint, dokka, kover, detekt,
// binary-compat validation) lives in the build-logic `boringssl.multiplatform-library` convention
// plugin. This file carries only the aggregate lifecycle tasks CI invokes.
//
// NB (RFC §2): this repo is a BINARY FACTORY, not a klib library. Several modules are deliberately
// NOT KMP (`:boringssl-build` plain Gradle, `:boringssl-provision` a Gradle plugin, `:boringssl-bom`
// a java-platform), so aggregate tasks below tolerate modules that lack a given task.

// Aggregate detekt across every module that applies it (each such module applies detekt via the convention).
tasks.register("detektAll") {
    description = "Run detekt static analysis across all modules that apply it (non-blocking)."
    group = "verification"
    dependsOn(subprojects.map { sp -> sp.tasks.matching { it.name == "detekt" } })
}

tasks.register("allTests") {
    description = "Run tests for all modules and platforms"
    group = "verification"
    dependsOn(subprojects.map { sp -> sp.tasks.matching { it.name == "allTests" } })
}

tasks.register("buildAll") {
    description = "Build all modules"
    group = "build"
    dependsOn(subprojects.map { sp -> sp.tasks.matching { it.name == "build" } })
}
