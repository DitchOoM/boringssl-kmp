plugins {
    id("boringssl.multiplatform-library")
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// :boringssl-android — AAR producer (RFC §3/§4/§5). Ships a prefab AAR: static `.a` + headers per ABI
// for consumer JNI shims to link at NDK build time. ABIs: `arm64-v8a` + `x86_64` only (RFC §5 Rule D /
// D7 — 32-bit `armeabi-v7a` dropped). Built lean (`-fvisibility=hidden -ffunction-sections
// -Wl,--gc-sections`) against the §5 ≤2.5 MB/ABI budget; ships via App Bundle so each device pulls one
// ABI slice.
//
// ⚠️ STUB — migration step 1. The convention supplies the Android KMP target + publishing; the prefab
// packaging + per-ABI static `.a` (from :boringssl-build) + the JNI shim wiring are step-2/7 work.
// ─────────────────────────────────────────────────────────────────────────────────────────────────
