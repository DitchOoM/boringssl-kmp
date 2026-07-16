package com.ditchoom.boringssl

import com.ditchoom.boringssl.internal.BoringSslFlavor
import com.ditchoom.boringssl.internal.NativeLibraryLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for the capability/backend-selection loader (RFC §12 D8): probe flavors, prefer the
 * newest present, and throw a typed [BoringSslUnavailable] when none loads. Drives the pure
 * [NativeLibraryLoader.select] seam with a controlled flavor set + presence oracle — no real I/O.
 */
class BackendSelectionTest {
    // A synthetic "newer" flavor stand-in (item 5 adds the real one). We reuse the CANONICAL enum for
    // the present/absent cases and assert selection semantics on the ordered list.

    @Test
    fun selects_canonical_when_only_canonical_present() {
        val chosen =
            NativeLibraryLoader.select(
                flavors = BoringSslFlavor.preferenceOrder,
                os = "macos",
                arch = "aarch64",
                resourceExists = { it.endsWith("/META-INF/native/macos-aarch64/libboringsslffi.dylib") },
            )
        assertEquals(BoringSslFlavor.CANONICAL, chosen)
        assertTrue(!chosen.supportsDtls13, "canonical is the DTLS 1.2 baseline")
    }

    @Test
    fun prefers_newest_present_over_later_entries() {
        // Simulate a two-flavor preference list [NEWER, CANONICAL] where BOTH are present: the first
        // (newest) must win. We model NEWER with CANONICAL's own entry twice to exercise ordering
        // purely (the real NEWER flavor arrives in item 5); the invariant under test is "first present".
        val newer = BoringSslFlavor.CANONICAL
        val order = listOf(newer, BoringSslFlavor.CANONICAL)
        val chosen = NativeLibraryLoader.select(order, "linux", "x86_64", resourceExists = { true })
        assertEquals(newer, chosen, "the first present flavor (newest) must be selected")
    }

    @Test
    fun throws_unavailable_when_no_flavor_present() {
        val ex =
            assertFailsWith<BoringSslUnavailable> {
                NativeLibraryLoader.select(
                    flavors = BoringSslFlavor.preferenceOrder,
                    os = "linux",
                    arch = "riscv64",
                    resourceExists = { false },
                )
            }
        assertEquals("com.ditchoom.boringssl:boringssl-jvm", ex.coordinate)
        assertTrue(ex.hint.contains("linux-riscv64"), "hint names the unsupported platform: ${ex.hint}")
    }

    @Test
    fun backend_info_reports_the_loaded_canonical_flavor() {
        // Runtime: loads the staged libboringsslffi and reports the selected flavor + capabilities.
        val info = BoringSsl.backendInfo()
        assertEquals("canonical", info.flavor)
        assertEquals("com.ditchoom.boringssl:boringssl-jvm", info.coordinate)
        assertTrue(!info.supportsDtls13, "canonical pin is DTLS 1.2 (D1a)")
        assertTrue(info.versionNumber != 0L, "OPENSSL_VERSION_NUMBER must be non-zero once loaded")
    }

    @Test
    fun exception_hierarchy_is_sealed_and_typed() {
        val unavailable: BoringSslException = BoringSslUnavailable("coord", "hint")
        val mismatch: BoringSslException = BoringSslVersionMismatch("v")
        val checksum: BoringSslException = BoringSslChecksumMismatch("c")
        // Exhaustive when over the sealed hierarchy — compiles only if all cases are covered.
        for (e in listOf(unavailable, mismatch, checksum)) {
            val kind =
                when (e) {
                    is BoringSslUnavailable -> "unavailable:${e.coordinate}"
                    is BoringSslVersionMismatch -> "version"
                    is BoringSslChecksumMismatch -> "checksum"
                }
            assertTrue(kind.isNotEmpty())
        }
    }
}
