package com.ditchoom.boringssl

/**
 * The sealed error hierarchy for the dynamic (FFM / Android-JNI) BoringSSL surface (RFC §12 D8).
 *
 * On the *dynamic* path a consumer compiles against the stable API — not a pinned commit — and a
 * loader **probes** which native flavors are present, prefers the newest, and throws one of these when
 * selection or linkage fails. (Static Kotlin/Native cinterop and iOS are build-time: a missing symbol
 * is a *link* error, not one of these — see D8.)
 *
 * Sealed so a `when` over a caught `BoringSslException` is exhaustive.
 */
public sealed class BoringSslException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * No usable BoringSSL backend could be loaded for this runtime — no bundled native library matched the
 * OS/arch, or the JDK is too old for the FFM backend. [coordinate] is the Maven artifact that would
 * provide it; [hint] is the actionable next step (add a dependency, raise the JDK, …).
 */
public class BoringSslUnavailable(
    public val coordinate: String,
    public val hint: String,
    cause: Throwable? = null,
) : BoringSslException("BoringSSL unavailable ($coordinate): $hint", cause)

/**
 * A native flavor loaded but its ABI/version does not match what this build expects (e.g. a
 * content-addressed variant whose commit disagrees with the pin the bindings were generated against).
 * Reserved for the multi-variant future (D8/D9 item 5); the loader declares it so the hierarchy is
 * complete and callers can already branch on it.
 */
public class BoringSslVersionMismatch(
    message: String,
    cause: Throwable? = null,
) : BoringSslException(message, cause)

/**
 * A bundled/fetched native library failed its sha256 verification at load time (the runtime analog of
 * the provision plugin's build-time checksum gate — no trust-on-first-use, RFC §8 directive #4).
 * Reserved for when the dynamic path fetches+verifies a variant lib (D8 dynamic surface).
 */
public class BoringSslChecksumMismatch(
    message: String,
    cause: Throwable? = null,
) : BoringSslException(message, cause)

/**
 * Describes the BoringSSL backend actually selected at runtime — which content-addressed flavor loaded,
 * the Maven coordinate it came from, whether it advertises DTLS 1.3, and BoringSSL's version number.
 * The stable, capability-oriented view a dynamic consumer branches on (RFC §12 D8 "soft dependency").
 */
public class BoringSslBackendInfo internal constructor(
    /** The flavor's stable name, e.g. `canonical` or a content-addressed `b<hash8>` alias. */
    public val flavor: String,
    /** The Maven coordinate that supplied the loaded native library. */
    public val coordinate: String,
    /** Whether this flavor advertises DTLS 1.3 (else the canonical DTLS 1.2 baseline — D1a). */
    public val supportsDtls13: Boolean,
    /** BoringSSL's `OPENSSL_VERSION_NUMBER` as reported by the loaded library. */
    public val versionNumber: Long,
) {
    override fun toString(): String =
        "BoringSslBackendInfo(flavor=$flavor, coordinate=$coordinate, " +
            "supportsDtls13=$supportsDtls13, versionNumber=0x${versionNumber.toString(16)})"
}
