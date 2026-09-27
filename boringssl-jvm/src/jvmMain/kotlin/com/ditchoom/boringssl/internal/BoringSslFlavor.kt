package com.ditchoom.boringssl.internal

/**
 * A content-addressed BoringSSL native flavor bundled in the MRJAR (RFC §12 D8/D9).
 *
 * The dynamic loader ([NativeLibraryLoader]) probes flavors in preference order (newest first) and
 * loads the first one whose native library is actually bundled for the running OS/arch — "prefer the
 * newest (DTLS 1.3 if the newer lib loads, else canonical)". Each flavor's native library lives under
 * `<resourceBase>/<os>-<arch>/` in the jar, so distinct flavors never collide.
 *
 * Today only [CANONICAL] ships. The canonical pin (boring-sys 5.2.0's tree, API 41) has DTLS 1.3, so it
 * advertises it. A further content-addressed variant (build-out item 5) adds an entry here (newer →
 * earlier in [preferenceOrder]) with its own `resourceBase` and coordinate, and the loader picks it up
 * automatically when its lib is present.
 */
internal enum class BoringSslFlavor(
    /** Stable public name (surfaced via BoringSslBackendInfo.flavor). */
    val flavorName: String,
    /** Jar resource dir prefix for this flavor's native libs: `<resourceBase>/<os>-<arch>/<lib>`. */
    val resourceBase: String,
    /** Maven coordinate that supplies this flavor (surfaced in errors + BoringSslBackendInfo). */
    val coordinate: String,
    /** Whether this flavor's BoringSSL has DTLS 1.3 (`DTLS1_3_VERSION`; else DTLS 1.2 is its ceiling). */
    val supportsDtls13: Boolean,
) {
    // NOTE: order here is preference order — NEWEST FIRST. A content-addressed variant (item 5) is
    // inserted ABOVE CANONICAL so it wins when present.
    CANONICAL(
        flavorName = "canonical",
        resourceBase = "META-INF/native",
        coordinate = "com.ditchoom.boringssl:boringssl-jvm",
        supportsDtls13 = true,
    ),
    ;

    companion object {
        /** Flavors in preference order (newest/most-capable first). */
        val preferenceOrder: List<BoringSslFlavor> = entries.toList()
    }
}
