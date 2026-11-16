package com.schaubeck.waddeln.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Address of the browser version: `https://waddeln.schaumler.de/play/` (a room: `/play/#CODE`). */
const val WEB_PATH = "/play"

/**
 * Only our own files, scripts and WebAssembly; the game server is the page's own origin. No inline
 * scripts (the start script is a file of its own); inline styles only in index.html's loading screen.
 */
internal const val WEB_CSP = "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; connect-src 'self'; " +
    "img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; object-src 'none'; " +
    "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

/** Files whose name carries a content hash (e.g. `0c7a3ff214d646587a58.wasm`) never change. */
private val HASHED = Regex("""(^|[.-])[0-9a-f]{16,}\.(wasm|js)$""")

private val TYPES = mapOf(
    "html" to ContentType.Text.Html.withParameter("charset", "utf-8"),
    "js" to ContentType.parse("text/javascript; charset=utf-8"),
    "wasm" to ContentType.parse("application/wasm"),
    "webp" to ContentType.parse("image/webp"),
    "png" to ContentType.Image.PNG,
    "wav" to ContentType.parse("audio/wav"),
    "mp3" to ContentType.parse("audio/mpeg"),
    "json" to ContentType.Application.Json,
    "txt" to ContentType.Text.Plain.withParameter("charset", "utf-8"),
)

/**
 * Serves the web app (the `:webApp:wasmJsBrowserDistribution` output) from [dir] under [WEB_PATH].
 * Without a directory `/play/` answers 404 and everything else runs as before. Nothing outside [dir]
 * can be reached.
 */
fun Route.webApp(dir: Path?) {
    val root = dir?.takeIf { it.isDirectory() }?.toRealPath()
    get(WEB_PATH) {
        // The fragment (#CODE) stays: browsers keep it across a redirect.
        if (root == null) call.respond(HttpStatusCode.NotFound) else call.respondRedirect("$WEB_PATH/")
    }
    get("$WEB_PATH/{path...}") {
        val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
        val file = root?.let { resolve(it, segments) }
        if (file == null) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        serve(file)
    }
}

/** The file for [segments] below [root], or null if there is none or it would lie outside. */
internal fun resolve(root: Path, segments: List<String>): Path? {
    if (segments.any { it == "." || it == ".." || '\\' in it || ':' in it }) return null
    val path = if (segments.isEmpty()) root.resolve("index.html") else root.resolve(segments.joinToString("/"))
    val file = path.normalize()
    if (!file.startsWith(root) || !file.isRegularFile()) return null
    // No way out through links either.
    return file.toRealPath().takeIf { it.startsWith(root) }
}

private suspend fun RoutingContext.serve(file: Path) {
    val type = TYPES[file.extension.lowercase()] ?: ContentType.Application.OctetStream
    // index.html is never cached anywhere (Cloudflare does not cache HTML), but Cloudflare gives the
    // other non-hashed files a browser cache of 4 hours, whatever we send. So index.html names the
    // version of this build in the address of start.js, which passes it on to webApp.js: after an
    // update every browser loads the new program instead of an old one that no longer fits the texts.
    val page = if (file.name == "index.html") withVersion(Files.readString(file), file.parent) else null
    val tag = if (page != null) {
        "\"p-${page.hashCode().toUInt().toString(16)}\""
    } else {
        "\"${Files.size(file).toString(16)}-${Files.getLastModifiedTime(file).toMillis().toString(16)}\""
    }
    call.response.header(HttpHeaders.ETag, tag)
    call.response.header(
        HttpHeaders.CacheControl,
        // Hashed files forever; the rest (index.html, webApp.js, images, texts, the model) is asked
        // for again each time, so a new version is seen at once; unchanged files cost only a 304.
        if (HASHED.containsMatchIn(file.name)) "public, max-age=31536000, immutable" else "no-cache",
    )
    call.response.header("X-Content-Type-Options", "nosniff")
    call.response.header("Referrer-Policy", "no-referrer")
    if (type.match(ContentType.Text.Html)) call.response.header("Content-Security-Policy", WEB_CSP)
    if (call.request.header(HttpHeaders.IfNoneMatch) == tag) {
        call.respond(HttpStatusCode.NotModified)
        return
    }
    if (page != null) call.respondText(page, type) else call.respond(LocalFileContent(file.toFile(), type))
}

/** [html] with `start.js?v=<version>`, the version taken from webApp.js of the same build in [dir]. */
internal fun withVersion(html: String, dir: Path): String {
    val app = dir.resolve("webApp.js")
    if (!Files.isRegularFile(app)) return html
    val version = (Files.size(app) * 31 + Files.getLastModifiedTime(app).toMillis()).toULong().toString(36)
    return html.replace("src=\"start.js\"", "src=\"start.js?v=$version\"")
}
