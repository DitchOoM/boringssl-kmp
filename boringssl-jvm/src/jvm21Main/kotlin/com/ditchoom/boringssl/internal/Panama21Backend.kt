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

    // void boringssl_ffi_sha256(const uint8_t* data, size_t len, uint8_t out[32])
    private val sha256Handle: MethodHandle =
        handle(
            "boringssl_ffi_sha256",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
        )

    override fun versionNumber(): Long = versionHandle.invoke() as Long

    override fun sha256(input: ByteArray): ByteArray {
        Arena.ofConfined().use { arena ->
            // allocate(0) is illegal; a 1-byte buffer with len 0 gives a valid non-null pointer.
            val inSeg = arena.allocate(maxOf(input.size, 1).toLong())
            if (input.isNotEmpty()) {
                MemorySegment.copy(input, 0, inSeg, ValueLayout.JAVA_BYTE, 0L, input.size)
            }
            val outSeg = arena.allocate(SHA256_DIGEST_LENGTH.toLong())
            sha256Handle.invoke(inSeg, input.size.toLong(), outSeg)
            val digest = ByteArray(SHA256_DIGEST_LENGTH)
            MemorySegment.copy(outSeg, ValueLayout.JAVA_BYTE, 0L, digest, 0, SHA256_DIGEST_LENGTH)
            return digest
        }
    }

    private companion object {
        const val SHA256_DIGEST_LENGTH = 32
    }
}
