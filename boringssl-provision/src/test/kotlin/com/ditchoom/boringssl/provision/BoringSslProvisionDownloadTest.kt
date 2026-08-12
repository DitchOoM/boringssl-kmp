package com.ditchoom.boringssl.provision

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * The REMOTE half of [BoringSslProvisionExtension]'s resolve pipeline — the download itself.
 *
 * Every other test in this module drives the `localDist` path, so before this file the download had no
 * coverage whatsoever. That is not incidental to the bug it now guards: release runs died four times in
 * a row on `Unexpected end of file from server` while the release asset was healthy, because the fetch
 * was a bare `URL.openStream().copyTo(...)` — one attempt, no timeouts, writing straight to the cache
 * path. A test that only ever read a local file could not see any of that.
 *
 * The server here is a JDK [HttpServer] on loopback, so the failures are produced rather than waited
 * for — see [Failure] for the two shapes, which are genuinely different: one raises an `IOException`
 * and the other used to raise nothing at all.
 */
class BoringSslProvisionDownloadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val testVersion = "0.0.1-test"
    private val triple = "linuxX64"
    private val tarName get() = "boringssl-$testVersion-$triple.tar.gz"

    private lateinit var project: Project
    private lateinit var extension: BoringSslProvisionExtension
    private lateinit var server: HttpServer
    private lateinit var bundle: ByteArray

    /** How many times the handler has been asked for the bundle — the retry count, observed. */
    private val requests = AtomicInteger(0)

    /** How the handler should break the next [failFirst] requests; the rest are served in full. */
    private var failure = Failure.HARD_EOF

    /** Requests to break before serving the body in full; the rest are honoured. */
    private var failFirst = 0

    /**
     * The two ways this download observably breaks, kept apart because they reach the client through
     * different mechanisms and only one of them used to raise anything at all.
     */
    private enum class Failure {
        /** Close with no response — the client reports an unexpected EOF. The CI symptom. */
        HARD_EOF,

        /** Headers promise a length the body never reaches: no exception, just a short file. */
        SHORT_BODY,
    }

    @Before
    fun setUp() {
        bundle = createBundleTarball().readBytes()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> serve(exchange) }
        server.start()

        project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("project")).build()
        project.pluginManager.apply(BoringSslProvisionPlugin::class.java)
        extension = project.extensions.getByType(BoringSslProvisionExtension::class.java)
        extension.version = testVersion
        extension.cacheRoot = tmp.newFolder("cache")
        extension.localDist = null
        // `<baseUrl><version>/<tarName>` — the real baseUrl carries the trailing `v` of the tag name,
        // and the handler answers whatever path that composes to.
        extension.baseUrl = "http://127.0.0.1:${server.address.port}/"
        extension.checksums = mapOf(triple to sha256(bundle))
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    /** Serve the bundle, breaking the first [failFirst] responses in the manner of [failure]. */
    private fun serve(exchange: HttpExchange) {
        val nth = requests.incrementAndGet()
        if (nth > failFirst) {
            exchange.sendResponseHeaders(200, bundle.size.toLong())
            exchange.responseBody.use { it.write(bundle) }
            return
        }
        when (failure) {
            Failure.HARD_EOF -> exchange.close()
            Failure.SHORT_BODY -> {
                exchange.sendResponseHeaders(200, bundle.size.toLong())
                exchange.responseBody.use { it.write(bundle.copyOfRange(0, bundle.size / 2)) }
            }
        }
    }

    /**
     * Deliberately [Failure.SHORT_BODY] rather than the CI symptom, and the reason is the whole point of
     * the assertion. `HttpURLConnection` transparently retries an idempotent GET once when the
     * connection itself fails, so a [Failure.HARD_EOF] here would be absorbed by the JDK and this test
     * would pass with the plugin's retry loop never entered — green, and proving somebody else's code.
     * A short body is a *complete* response by the transport's reckoning, so nothing beneath us retries
     * it, and recovery can only be ours.
     */
    @Test
    fun `a broken first response is retried by us, and the bundle still provisions`() {
        failure = Failure.SHORT_BODY
        failFirst = 1

        val dir = extension.boringsslDir(triple)

        assertTrue("the bundle must extract after the retry", File(dir, "lib").isDirectory)
        assertTrue(File(dir, "include").isDirectory)
        assertEquals(
            "the retry is the point: one failed attempt then one good one, not a single lucky request",
            2,
            requests.get(),
        )
    }

    @Test
    fun `a download that fails every attempt names the url and the attempt count`() {
        failFirst = Int.MAX_VALUE

        val e = assertThrows(GradleException::class.java) { extension.boringsslDir(triple) }

        val message = e.message.orEmpty()
        assertTrue("message must name the url, was: $message", message.contains(tarName))
        assertTrue("message must name the attempt count, was: $message", message.contains("3 attempts"))
        // NOT an equality: the JDK retries an idempotent GET once on a connection-level failure, so each
        // of our three attempts reaches the handler twice. Six is the number to expect and neither six
        // nor three is the property worth pinning — that all three of OUR attempts were genuinely spent
        // against the network rather than short-circuited after the first.
        assertTrue(
            "all three attempts must reach the server, saw ${requests.get()}",
            requests.get() >= 3,
        )
    }

    /**
     * The atomicity guarantee, and the reason this is a separate assertion rather than a clause of the
     * one above. Writing the body straight to the cache path left a TRUNCATED file there on failure;
     * the next invocation saw it exist, skipped the download, and failed the sha256 check instead —
     * reported as a checksum mismatch, which reads as corruption rather than as a partial fetch, and
     * which no retry clears until somebody deletes the cache by hand. So a failed download must leave
     * the cache exactly as it found it.
     */
    @Test
    fun `a failed download leaves nothing behind for the next run to trip over`() {
        failFirst = Int.MAX_VALUE
        assertThrows(GradleException::class.java) { extension.boringsslDir(triple) }

        val downloads = File(extension.cacheRoot, "downloads")
        assertFalse(
            "a partial body must never be visible at the cache path — the next run would take it as a hit",
            File(downloads, tarName).exists(),
        )
        assertEquals(
            "and no scratch file may survive either: ${downloads.listFiles().orEmpty().map { it.name }}",
            0,
            downloads.listFiles().orEmpty().size,
        )
    }

    /**
     * The recovery the atomicity buys: the run after a total failure downloads cleanly, because there is
     * no poisoned cache entry standing in its way. Without the atomic move this fails on a checksum
     * mismatch against the half-file the previous test's run left behind.
     */
    @Test
    fun `a run after a failed download recovers on its own`() {
        failFirst = Int.MAX_VALUE
        assertThrows(GradleException::class.java) { extension.boringsslDir(triple) }

        failFirst = 0
        val dir = extension.boringsslDir(triple)

        assertTrue("the next run must provision with no manual cache surgery", File(dir, "lib").isDirectory)
    }

    /**
     * The quiet half, and the one this suite found rather than set out to guard. A body cut short after
     * its headers raises nothing: `copyTo` reads to EOF and cannot distinguish "the body ended" from
     * "the connection did", so the short file was written, cached, and only rejected later by the sha256
     * check — as a *checksum mismatch*, which points the reader at a corrupt or tampered release asset
     * when the asset was fine and the transfer was not. Comparing against the declared Content-Length
     * turns it back into what it is: a transfer failure, retried like any other, named for what happened.
     */
    @Test
    fun `a body cut short after its headers is a transfer failure, not a checksum mismatch`() {
        failure = Failure.SHORT_BODY
        failFirst = Int.MAX_VALUE

        val e = assertThrows(GradleException::class.java) { extension.boringsslDir(triple) }

        val message = "${e.message} / ${e.cause?.message}"
        assertTrue("must be reported as a truncated transfer, was: $message", message.contains("truncated response"))
        assertFalse(
            "must NOT surface as a checksum mismatch — that sends the reader after the asset: $message",
            message.contains("checksum mismatch"),
        )
        assertEquals("a short body is retried like any other transfer failure", 3, requests.get())
    }

    /** A minimal but structurally real bundle: `lib/` + `include/`, tarred the way the producer does. */
    private fun createBundleTarball(): File {
        val stage = tmp.newFolder("stage")
        File(stage, "lib").mkdirs()
        File(stage, "include").mkdirs()
        File(stage, "lib/libcrypto.a").writeText("stub-crypto")
        File(stage, "lib/libssl.a").writeText("stub-ssl")
        File(stage, "include/openssl.h").writeText("stub-header")
        val tarball = File(tmp.newFolder("dist"), tarName)
        val code =
            ProcessBuilder("tar", "czf", tarball.absolutePath, "-C", stage.absolutePath, "lib", "include")
                .inheritIO().start().waitFor()
        check(code == 0) { "tar czf failed with $code" }
        return tarball
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
