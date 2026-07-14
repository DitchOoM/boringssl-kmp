package com.ditchoom.boringssl.internal

/**
 * Resolves the [FfiBackend] once, lazily: verifies the running JDK is ≥ 21, loads the native library,
 * then instantiates the `META-INF/versions/21` implementation by name.
 *
 * The lookup is by reflection rather than a direct reference so the base slice never links a
 * `java.lang.foreign`-touching class: on JDK < 21 the version guard fires before `Class.forName`, so
 * `Panama21Backend` (absent from the classpath below 21) is never resolved.
 */
internal object Backends {
    val instance: FfiBackend by lazy { resolve() }

    private fun resolve(): FfiBackend {
        val feature = javaFeature()
        require(feature >= 21) {
            "BoringSSL FFM backend requires JDK 21 or newer (running on Java feature $feature). " +
                "Use the JNI/Android backend on older runtimes."
        }
        NativeLibraryLoader.ensureLoaded()
        val impl = Class.forName("com.ditchoom.boringssl.internal.Panama21Backend")
        return impl.getDeclaredConstructor().newInstance() as FfiBackend
    }

    /** Feature version without `Runtime.version()` (Java 9+), so the base truly runs on Java 8+. */
    private fun javaFeature(): Int {
        val v = System.getProperty("java.specification.version") ?: return 0
        return if (v.startsWith("1.")) {
            v.substring(2).toIntOrNull() ?: 0 // "1.8" -> 8
        } else {
            v.substringBefore('.').toIntOrNull() ?: 0 // "21" -> 21
        }
    }
}
