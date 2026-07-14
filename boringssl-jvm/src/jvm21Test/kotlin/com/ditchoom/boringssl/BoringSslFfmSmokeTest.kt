package com.ditchoom.boringssl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM FFM link-smoke (RFC §8 validate-artifacts): resolves the shim symbols out of the bundled
 * `libboringsslffi.so` and calls into BoringSSL. Passing proves the whole lane — MRJAR native
 * resource → `System.load` → `SymbolLookup.loaderLookup` → downcall → correct result.
 */
class BoringSslFfmSmokeTest {
    @Test
    fun versionNumberIsNonZeroOnceLoaded() {
        // OPENSSL_VERSION_NUMBER is non-zero iff the .so loaded and the downcall reached BoringSSL.
        assertTrue(BoringSsl.versionNumber() != 0L, "version number should be non-zero")
    }

    @Test
    fun sha256AbcMatchesKnownVector() {
        val digest = BoringSsl.sha256("abc".toByteArray(Charsets.US_ASCII))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest.toHexString(),
        )
    }

    @Test
    fun sha256EmptyInputMatchesKnownVector() {
        val digest = BoringSsl.sha256(ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digest.toHexString(),
        )
    }

    private fun ByteArray.toHexString(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
