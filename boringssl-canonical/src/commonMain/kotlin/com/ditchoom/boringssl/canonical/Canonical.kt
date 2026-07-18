package com.ditchoom.boringssl.canonical

// Intentionally the ONLY Kotlin source in this module: an internal marker so each native target's
// `main` compilation is NOT no-source (a no-source main compilation produces no `.klib` and fails
// KMP publishing). It adds NOTHING to the public/interop surface — the module ships exactly one
// ingredient, the canonical BoringSSL archive embedded in the bindings-free `boringsslcanonical`
// cinterop klib's `included/` (see boringsslcanonical.def + build.gradle.kts). RFC §2: not a klib
// library — no crypto bindings are exported here; every consumer keeps its own `.def`/glue.
internal const val CANONICAL_OWNER: String = "boringssl-canonical"
