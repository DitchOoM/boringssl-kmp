package com.ditchoom.boringssl.internal

import java.io.File
import java.nio.file.Files
import java.util.Locale

/**
 * Extracts the bundled `libboringsslffi.{so,dylib}` for the running OS/arch out of the jar
 * (`META-INF/native/<os>-<arch>/`) to a temp file and `System.load`s it, exactly once.
 *
 * FFM's `SymbolLookup.loaderLookup()` (used by the JDK-21 backend) resolves symbols from libraries
 * loaded via `System.load` on the calling class's loader — so this must run before the backend binds
 * its downcall handles. Referencing no `java.lang.foreign` type, this loader is part of the JVM-8
 * base slice and runs on any JDK.
 */
internal object NativeLibraryLoader {
    @Volatile private var loaded = false

    /** OS token used in the resource path: `linux`, `macos`, or `windows`. */
    private fun osToken(): String {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            os.contains("linux") -> "linux"
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("windows") -> "windows"
            else -> error("Unsupported OS for BoringSSL FFM: $os")
        }
    }

    /** Arch token used in the resource path: `x86_64` or `aarch64` (K/N-triple-aligned). */
    private fun archToken(): String {
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        return when (arch) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> error("Unsupported architecture for BoringSSL FFM: $arch")
        }
    }

    private fun libFileName(os: String): String =
        when (os) {
            "macos" -> "libboringsslffi.dylib"
            "windows" -> "boringsslffi.dll"
            else -> "libboringsslffi.so"
        }

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        val os = osToken()
        val arch = archToken()
        val fileName = libFileName(os)
        val resourcePath = "/META-INF/native/$os-$arch/$fileName"
        val stream =
            NativeLibraryLoader::class.java.getResourceAsStream(resourcePath)
                ?: error(
                    "BoringSSL FFM native library not bundled for $os-$arch (missing resource $resourcePath). " +
                        "This boringssl-jvm build did not include a libboringsslffi for this platform.",
                )
        val tmpDir = Files.createTempDirectory("boringssl-ffi").toFile().apply { deleteOnExit() }
        val out = File(tmpDir, fileName).apply { deleteOnExit() }
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        @Suppress("UnsafeDynamicallyLoadedCode") // absolute path to our just-extracted, checksum-pinned lib
        System.load(out.absolutePath)
        loaded = true
    }
}
