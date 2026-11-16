package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAppTest {

    /** A web app directory next to a file that must stay unreachable. */
    private val base: Path = Files.createTempDirectory("waddeln-web").also {
        it.resolve("secret.txt").writeText("secret")
    }
    private val web: Path = base.resolve("web").createDirectories().also { dir ->
        dir.resolve("index.html").writeText("<!doctype html><title>Waddeln</title><script src=\"start.js\"></script>")
        dir.resolve("webApp.js").writeText("console.log(1)")
        dir.resolve("0c7a3ff214d646587a58.wasm").writeText("wasm")
        dir.resolve("composeResources/files").createDirectories().resolve("shuffle.wav").writeText("wav")
    }

    private fun app(dir: Path?, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val gameThread = Dispatchers.Default.limitedParallelism(1)
        val server = GameServer(CoroutineScope(SupervisorJob() + gameThread), { RuleBasedAgent() }, Timing(), Random(1))
        application { waddeln(server, gameThread, dir) }
        block()
    }

    @Test
    fun `play serves index html with a strict content security policy`() = app(web) {
        val response = client.get("/play/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue("<title>Waddeln</title>" in response.bodyAsText())
        assertTrue(response.contentType()!!.match(ContentType.Text.Html))
        val csp = response.headers["Content-Security-Policy"]!!
        assertTrue("script-src 'self' 'wasm-unsafe-eval'" in csp, csp)
        assertTrue("connect-src 'self'" in csp, csp)
        assertFalse("unsafe-eval'" in csp.replace("'wasm-unsafe-eval'", ""), csp)
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
    }

    @Test
    fun `index html names the build in the address of start js`() = app(web) {
        val page = client.get("/play/").bodyAsText()
        val version = Regex("""src="start\.js\?v=([0-9a-z]+)"""").find(page)?.groupValues?.get(1)
        assertTrue(version != null, page)
        // A new webApp.js (the next build) gives a new address.
        web.resolve("webApp.js").writeText("console.log(2) // changed")
        val again = client.get("/play/").bodyAsText()
        assertFalse("start.js?v=$version\"" in again, again)
        // Unchanged, the page is answered with 304.
        val tag = client.get("/play/").headers[HttpHeaders.ETag]!!
        assertEquals(HttpStatusCode.NotModified, client.get("/play/") { header(HttpHeaders.IfNoneMatch, tag) }.status)
    }

    @Test
    fun `head requests get the headers`() = app(web) {
        val response = client.head("/play/0c7a3ff214d646587a58.wasm")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("application/wasm", response.contentType()!!.withoutParameters().toString())
    }

    @Test
    fun `play without slash redirects`() = app(web) {
        val response = client.config { followRedirects = false }.get("/play")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/play/", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `wasm has its type and hashed files are cached for good`() = app(web) {
        val wasm = client.get("/play/0c7a3ff214d646587a58.wasm")
        assertEquals(HttpStatusCode.OK, wasm.status)
        assertEquals("application/wasm", wasm.contentType()!!.withoutParameters().toString())
        assertTrue("immutable" in wasm.headers[HttpHeaders.CacheControl]!!)

        val js = client.get("/play/webApp.js")
        assertEquals("no-cache", js.headers[HttpHeaders.CacheControl])
        assertEquals(HttpStatusCode.OK, client.get("/play/composeResources/files/shuffle.wav").status)
    }

    @Test
    fun `unchanged files are answered with 304`() = app(web) {
        val tag = client.get("/play/webApp.js").headers[HttpHeaders.ETag]!!
        val again = client.get("/play/webApp.js") { header(HttpHeaders.IfNoneMatch, tag) }
        assertEquals(HttpStatusCode.NotModified, again.status)
    }

    @Test
    fun `unknown paths are 404`() = app(web) {
        assertEquals(HttpStatusCode.NotFound, client.get("/play/missing.js").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/play/composeResources").status)
    }

    @Test
    fun `nothing outside the web directory can be reached`() = app(web) {
        for (path in listOf(
            "/play/../secret.txt",
            "/play/%2e%2e/secret.txt",
            "/play/%2E%2E%2Fsecret.txt",
            "/play/..%5Csecret.txt",
            "/play/composeResources/../../secret.txt",
        )) {
            val response = client.get(path)
            assertFalse(response.status == HttpStatusCode.OK && "secret" in response.bodyAsText(), path)
        }
    }

    @Test
    fun `without a web directory play is 404 and the rest still works`() = app(null) {
        assertEquals(HttpStatusCode.NotFound, client.get("/play/").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/play").status)
        assertEquals("ok", client.get("/health").bodyAsText())
    }
}
