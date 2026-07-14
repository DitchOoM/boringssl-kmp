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
}
