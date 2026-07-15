package com.ditchoom.boringssl.internal

import com.ditchoom.boringssl.BoringSslUnavailable
import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * Capability/backend-selection loader (RFC §12 D8). Probes the [BoringSslFlavor.preferenceOrder]
 * flavors for the running OS/arch, `System.load`s the FIRST one whose native library is bundled
 * (`<resourceBase>/<os>-<arch>/libboringsslffi.{so,dylib}`), and records which flavor won. When none is
 * present it throws [BoringSslUnavailable] with the coordinate + an actionable hint.
 *
 * FFM's `SymbolLookup.loaderLookup()` (the JDK-21 backend) resolves symbols from libraries loaded via
 * `System.load` on the calling class's loader — so this must run before the backend binds its downcall
 * handles. Referencing no `java.lang.foreign` type, this loader is part of the JVM-8 base slice.
 */
internal object NativeLibraryLoader {
    @Volatile private var selectedFlavor: BoringSslFlavor? = null

    /** The flavor chosen by [ensureLoaded]; null until a successful load. */
    val selected: BoringSslFlavor? get() = selectedFlavor

    /** OS token used in the resource path: `linux`, `macos`, or `windows`. */
    private fun osToken(): String {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            os.contains("linux") -> "linux"
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("windows") -> "windows"
            else -> throw BoringSslUnavailable(
                coordinate = BoringSslFlavor.CANONICAL.coordinate,
                hint = "unsupported OS '$os' for the BoringSSL FFM backend.",
            )
        }
    }

    /** Arch token used in the resource path: `x86_64` or `aarch64` (K/N-triple-aligned). */
    private fun archToken(): String {
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        return when (arch) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> throw BoringSslUnavailable(
                coordinate = BoringSslFlavor.CANONICAL.coordinate,
                hint = "unsupported architecture '$arch' for the BoringSSL FFM backend.",
            )
        }
    }

    private fun libFileName(os: String): String =
        when (os) {
            "macos" -> "libboringsslffi.dylib"
            "windows" -> "boringsslffi.dll"
            else -> "libboringsslffi.so"
        }

    /** Resource path of [flavor]'s native library for [os]/[arch] (leading-slash absolute). */
    private fun resourcePath(
        flavor: BoringSslFlavor,
        os: String,
        arch: String,
    ): String = "/${flavor.resourceBase}/$os-$arch/${libFileName(os)}"

    /**
     * PURE selection: pick the first flavor (preference order, newest first) whose native library is
     * present per [resourceExists]. Throws [BoringSslUnavailable] if none. Extracted from I/O so it is
     * unit-testable with a controlled flavor set + presence oracle.
     */
    internal fun select(
        flavors: List<BoringSslFlavor>,
        os: String,
        arch: String,
        resourceExists: (String) -> Boolean,
    ): BoringSslFlavor {
        for (flavor in flavors) {
            if (resourceExists(resourcePath(flavor, os, arch))) return flavor
        }
        val canonical = BoringSslFlavor.CANONICAL
        val tried = flavors.joinToString(", ") { it.flavorName }
        throw BoringSslUnavailable(
            coordinate = canonical.coordinate,
            hint =
                "no BoringSSL native library bundled for $os-$arch (tried flavors: $tried). " +
                    "This boringssl-jvm build did not include a libboringsslffi for this platform.",
        )
    }

    @Synchronized
    fun ensureLoaded() {
        if (selectedFlavor != null) return
        val os = osToken()
        val arch = archToken()
        val flavor =
            select(BoringSslFlavor.preferenceOrder, os, arch) { path ->
                NativeLibraryLoader::class.java.getResource(path) != null
            }

        val fileName = libFileName(os)
        val resourcePath = resourcePath(flavor, os, arch)
        val stream =
            NativeLibraryLoader::class.java.getResourceAsStream(resourcePath)
                ?: throw BoringSslUnavailable(
                    coordinate = flavor.coordinate,
                    hint = "native library resource $resourcePath vanished after selection.",
                )
        val tmpDir = Files.createTempDirectory("boringssl-ffi").toFile().apply { deleteOnExit() }
        val out = File(tmpDir, fileName).apply { deleteOnExit() }
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        @Suppress("UnsafeDynamicallyLoadedCode") // absolute path to our just-extracted, checksum-pinned lib
        System.load(out.absolutePath)
        selectedFlavor = flavor
    }
}
