package com.ditchoom.boringssl.internal

/**
 * The internal FFM surface, resolved at runtime from the MRJAR slice matching the running JDK.
 *
 * The base (this source set, `jvmMain`, compiled at JVM 8) declares only this interface — it
 * references NO `java.lang.foreign` type, so the jar links on any JDK. The implementation
 * ([com.ditchoom.boringssl.internal.Panama21Backend]) lives in `jvm21Main` and is packaged under
 * `META-INF/versions/21/`, so it is only ever loaded on JDK 21+ (see [Backends]).
 */
internal interface FfiBackend {
    /** BoringSSL's `OPENSSL_VERSION_NUMBER` (a C macro, re-exported by the shim as a real symbol). */
    fun versionNumber(): Long

    /** SHA-256 of [input], computed by BoringSSL through the FFM downcall. Returns the 32-byte digest. */
    fun sha256(input: ByteArray): ByteArray

    /** SHA-512 of [input]. Returns the 64-byte digest. */
    fun sha512(input: ByteArray): ByteArray

    /** HMAC-SHA256 of [data] under [key]. Returns the 32-byte MAC. */
    fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray

    /** HKDF-SHA256 (RFC 5869) deriving [length] bytes from [secret] with [salt] and [info]. */
    fun hkdfSha256(
        length: Int,
        secret: ByteArray,
        salt: ByteArray,
        info: ByteArray,
    ): ByteArray

    /** A fresh X25519 key pair: `first` = public, `second` = private (each 32 bytes). */
    fun x25519KeyPair(): Pair<ByteArray, ByteArray>

    /** X25519 Diffie-Hellman: the 32-byte shared secret of [privateKey] and [peerPublicKey]. */
    fun x25519(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): ByteArray

    /** [length] cryptographically secure random bytes from BoringSSL's RNG. */
    fun randomBytes(length: Int): ByteArray
}
