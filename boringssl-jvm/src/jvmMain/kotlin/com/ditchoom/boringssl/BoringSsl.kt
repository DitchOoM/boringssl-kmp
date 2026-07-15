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
    /** An X25519 key pair (RFC 7748); each value is 32 bytes. */
    public class X25519KeyPair internal constructor(
        public val publicKey: ByteArray,
        public val privateKey: ByteArray,
    )

    /** BoringSSL's `OPENSSL_VERSION_NUMBER`; non-zero once the native library is loaded and linked. */
    public fun versionNumber(): Long = Backends.instance.versionNumber()

    /** Returns the 32-byte SHA-256 digest of [input], computed by BoringSSL. */
    public fun sha256(input: ByteArray): ByteArray = Backends.instance.sha256(input)

    /** Returns the 64-byte SHA-512 digest of [input]. */
    public fun sha512(input: ByteArray): ByteArray = Backends.instance.sha512(input)

    /** Returns the 32-byte HMAC-SHA256 of [data] keyed by [key]. */
    public fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray = Backends.instance.hmacSha256(key, data)

    /**
     * Derives [length] bytes of key material from [secret] via HKDF-SHA256 (RFC 5869), using the
     * optional [salt] and [info]. [length] must be at most 255·32 = 8160 bytes.
     */
    public fun hkdfSha256(
        length: Int,
        secret: ByteArray,
        salt: ByteArray = ByteArray(0),
        info: ByteArray = ByteArray(0),
    ): ByteArray = Backends.instance.hkdfSha256(length, secret, salt, info)

    /** Generates a fresh X25519 key pair using BoringSSL's RNG. */
    public fun x25519KeyPair(): X25519KeyPair {
        val (pub, priv) = Backends.instance.x25519KeyPair()
        return X25519KeyPair(pub, priv)
    }

    /** Computes the 32-byte X25519 shared secret between [privateKey] and [peerPublicKey]. */
    public fun x25519(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): ByteArray = Backends.instance.x25519(privateKey, peerPublicKey)

    /** Returns [length] cryptographically secure random bytes from BoringSSL's RNG. */
    public fun randomBytes(length: Int): ByteArray = Backends.instance.randomBytes(length)
}
