plugins {
    id("boringssl.multiplatform-library")
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-testsuite — per-target link-smoke validation (RFC §3/§8). Exercises each produced bundle:
// SHA256 / AEAD / `SSL_CTX` / `DTLSv1`, a wrapper-`.def` compile+link per K/N triple, FFM
// `SymbolLookup` on JVM, AAR prefab metadata, and a quiche `ffi,qlog` link-smoke. Wired into the CI
// `validate-artifacts` lane. Not a published API surface → apiCheck-excluded.
//
// ⚠️ STUB — migration step 1. Real link-smoke tests arrive with the first Linux bundle (step 2/3).
// ─────────────────────────────────────────────────────────────────────────────────────────────────

// This is a validation harness, not a published library, so exclude it from binary-compatibility
// validation (RFC §3: "validation module, apiCheck-excluded").
tasks.matching { it.name == "apiCheck" || it.name == "apiDump" }.configureEach {
    enabled = false
}
