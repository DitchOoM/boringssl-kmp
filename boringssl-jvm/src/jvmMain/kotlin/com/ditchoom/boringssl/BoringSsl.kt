package com.ditchoom.boringssl

import com.ditchoom.boringssl.internal.Backends

/**
 * The public entry point to the canonical BoringSSL, bound over the Foreign Function & Memory API.
 *
 * This is a **seed surface** (RFC §4): it proves the FFM lane end to end (a version probe and a
 * hash). The full DTLS/QUIC surface is added as curated shim functions grow — never by binding
 * BoringSSL's macro API directly.
 *
 * Requires a JDK 21+ runtime; the bindings run on 21 with no `--enable-preview` (they are Kotlin, so
 * the class files carry no preview flag) and forward unchanged onto 22/25.
 */
public object BoringSsl {
    /** BoringSSL's `OPENSSL_VERSION_NUMBER`; non-zero once the native library is loaded and linked. */
    public fun versionNumber(): Long = Backends.instance.versionNumber()

    /** Returns the 32-byte SHA-256 digest of [input], computed by BoringSSL. */
    public fun sha256(input: ByteArray): ByteArray = Backends.instance.sha256(input)
}
