plugins {
    id("boringssl.multiplatform-library")
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-jvm — FFM producer (RFC §3/§4). Ships a multi-release JAR carrying the shared
// `libboringsslffi.{so,dylib}` under `META-INF/native/<os>-<arch>/` plus the jextract-generated FFM
// bindings (generated over the curated `boringssl_shim.h`, RFC §4).
//
// ⚠️ STUB — migration step 1. Only structure/targets/publishing come from the convention. Step 2
// (RFC §10) authors: the linux FFM `.so`, the jextract codegen into a `jvm21Main` source set
// (JvmTarget.JVM_21 — FFM is preview in JDK 21), and the MRJAR packaging. No dependencies yet.
// ─────────────────────────────────────────────────────────────────────────────────────────────────
