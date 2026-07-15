package com.ditchoom.boringssl.internal

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * The FFM implementation of [FfiBackend], packaged into the MRJAR under `META-INF/versions/21/`.
 *
 * Authored in Kotlin (not jextract-generated Java) on purpose: kotlinc does not stamp the class-file
 * preview flag, so these classes run on stock JDK 21 with no `--enable-preview` AND load unchanged on
 * JDK 22/25. To keep that forward portability, this sticks to the FFM subset that survived unchanged
 * from the JDK-21 preview into the JDK-22 final API — `Linker`, `SymbolLookup.loaderLookup`,
 * `downcallHandle`, `FunctionDescriptor`, `ValueLayout`, `Arena.ofConfined`, `Arena.allocate`, and the
 * `MemorySegment.copy` array overloads — and avoids the helpers that were renamed (e.g.
 * `allocateUtf8String` → `allocateFrom`).
 *
 * Symbols are resolved via `loaderLookup()`, which sees the library that [NativeLibraryLoader]
 * `System.load`ed on this class's loader; [Backends] guarantees that load happens first.
 */
internal class Panama21Backend : FfiBackend {
    private val linker: Linker = Linker.nativeLinker()
    private val lookup: SymbolLookup = SymbolLookup.loaderLookup()

    private fun handle(
        symbol: String,
        descriptor: FunctionDescriptor,
    ): MethodHandle {
        val address =
            lookup.find(symbol).orElseThrow {
                IllegalStateException("libboringsslffi does not export '$symbol' — shim/.so out of sync")
            }
        return linker.downcallHandle(address, descriptor)
    }

    // unsigned long boringssl_ffi_version_number(void)
    private val versionHandle: MethodHandle =
        handle("boringssl_ffi_version_number", FunctionDescriptor.of(ValueLayout.JAVA_LONG))

    // void boringssl_ffi_sha256 / sha512 (const uint8_t* data, size_t len, uint8_t* out)
    private val sha256Handle: MethodHandle = handle("boringssl_ffi_sha256", DIGEST_DESC)
    private val sha512Handle: MethodHandle = handle("boringssl_ffi_sha512", DIGEST_DESC)

    // void boringssl_ffi_hmac_sha256(const uint8_t* key, size_t, const uint8_t* data, size_t, uint8_t* out)
    private val hmacSha256Handle: MethodHandle =
        handle(
            "boringssl_ffi_hmac_sha256",
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
            ),
        )

    // int boringssl_ffi_hkdf_sha256(uint8_t* out, size_t, secret, size_t, salt, size_t, info, size_t)
    private val hkdfSha256Handle: MethodHandle =
        handle(
            "boringssl_ffi_hkdf_sha256",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_LONG,
            ),
        )

    // void boringssl_ffi_x25519_keypair(uint8_t* pub, uint8_t* priv)
    private val x25519KeypairHandle: MethodHandle =
        handle("boringssl_ffi_x25519_keypair", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS))

    // int boringssl_ffi_x25519(uint8_t* out, const uint8_t* priv, const uint8_t* peer)
    private val x25519Handle: MethodHandle =
        handle(
            "boringssl_ffi_x25519",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )

    // int boringssl_ffi_rand_bytes(uint8_t* out, size_t len)
    private val randBytesHandle: MethodHandle =
        handle("boringssl_ffi_rand_bytes", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG))

    override fun versionNumber(): Long = versionHandle.invoke() as Long

    override fun sha256(input: ByteArray): ByteArray = digest(sha256Handle, input, SHA256_LEN)

    override fun sha512(input: ByteArray): ByteArray = digest(sha512Handle, input, SHA512_LEN)

    private fun digest(
        handle: MethodHandle,
        input: ByteArray,
        outLen: Int,
    ): ByteArray {
        Arena.ofConfined().use { arena ->
            val inSeg = arena.copyOf(input)
            val outSeg = arena.allocate(outLen.toLong())
            handle.invoke(inSeg, input.size.toLong(), outSeg)
            return outSeg.readBytes(outLen)
        }
    }

    override fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        Arena.ofConfined().use { arena ->
            val keySeg = arena.copyOf(key)
            val dataSeg = arena.copyOf(data)
            val outSeg = arena.allocate(SHA256_LEN.toLong())
            hmacSha256Handle.invoke(keySeg, key.size.toLong(), dataSeg, data.size.toLong(), outSeg)
            return outSeg.readBytes(SHA256_LEN)
        }
    }

    override fun hkdfSha256(
        length: Int,
        secret: ByteArray,
        salt: ByteArray,
        info: ByteArray,
    ): ByteArray {
        require(length >= 0) { "length must be non-negative" }
        Arena.ofConfined().use { arena ->
            val outSeg = arena.allocate(maxOf(length, 1).toLong())
            val secretSeg = arena.copyOf(secret)
            val saltSeg = arena.copyOf(salt)
            val infoSeg = arena.copyOf(info)
            val ok =
                hkdfSha256Handle.invoke(
                    outSeg,
                    length.toLong(),
                    secretSeg,
                    secret.size.toLong(),
                    saltSeg,
                    salt.size.toLong(),
                    infoSeg,
                    info.size.toLong(),
                ) as Int
            check(ok == 1) { "HKDF-SHA256 failed (length $length exceeds 255·32?)" }
            return outSeg.readBytes(length)
        }
    }

    override fun x25519KeyPair(): Pair<ByteArray, ByteArray> {
        Arena.ofConfined().use { arena ->
            val pubSeg = arena.allocate(X25519_LEN.toLong())
            val privSeg = arena.allocate(X25519_LEN.toLong())
            x25519KeypairHandle.invoke(pubSeg, privSeg)
            return pubSeg.readBytes(X25519_LEN) to privSeg.readBytes(X25519_LEN)
        }
    }

    override fun x25519(
        privateKey: ByteArray,
        peerPublicKey: ByteArray,
    ): ByteArray {
        require(privateKey.size == X25519_LEN) { "X25519 private key must be $X25519_LEN bytes" }
        require(peerPublicKey.size == X25519_LEN) { "X25519 public key must be $X25519_LEN bytes" }
        Arena.ofConfined().use { arena ->
            val outSeg = arena.allocate(X25519_LEN.toLong())
            val privSeg = arena.copyOf(privateKey)
            val peerSeg = arena.copyOf(peerPublicKey)
            val ok = x25519Handle.invoke(outSeg, privSeg, peerSeg) as Int
            check(ok == 1) { "X25519 produced an all-zero (small-order) shared secret" }
            return outSeg.readBytes(X25519_LEN)
        }
    }

    override fun randomBytes(length: Int): ByteArray {
        require(length >= 0) { "length must be non-negative" }
        if (length == 0) return ByteArray(0)
        Arena.ofConfined().use { arena ->
            val outSeg = arena.allocate(length.toLong())
            randBytesHandle.invoke(outSeg, length.toLong())
            return outSeg.readBytes(length)
        }
    }

    /** Copy a heap array into a fresh native segment (a 1-byte stand-in for empty → valid non-null ptr). */
    private fun Arena.copyOf(bytes: ByteArray): MemorySegment {
        val seg = allocate(maxOf(bytes.size, 1).toLong())
        if (bytes.isNotEmpty()) MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.size)
        return seg
    }

    /** Read the first [len] bytes of this native segment back onto the heap. */
    private fun MemorySegment.readBytes(len: Int): ByteArray {
        val out = ByteArray(len)
        MemorySegment.copy(this, ValueLayout.JAVA_BYTE, 0L, out, 0, len)
        return out
    }

    private companion object {
        const val SHA256_LEN = 32
        const val SHA512_LEN = 64
        const val X25519_LEN = 32
        val DIGEST_DESC: FunctionDescriptor =
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    }
}
