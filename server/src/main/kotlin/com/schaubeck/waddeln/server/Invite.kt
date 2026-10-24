package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.INVITE_HOST
import com.schaubeck.waddeln.protocol.INVITE_PATH
import com.schaubeck.waddeln.protocol.parseRoomCode
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Package name of the app that opens invitation links. */
private const val APP_PACKAGE = "com.schaubeck.waddeln"

/**
 * SHA-256 fingerprints of the certificates the app is signed with: the release key (also used by
 * Google Play, since we uploaded our own signing key) and the debug key for testing over USB.
 * They are public: every installed app shows its certificate.
 */
private val APP_CERTIFICATES = listOf(
    "94:6A:5C:5C:7F:55:9C:6F:51:F3:C2:0D:EC:68:41:E4:3B:6F:E3:CB:57:6A:23:A0:A1:3C:69:54:2C:EF:C7:32",
    "3E:6C:B8:B4:E6:F9:BB:FC:61:7F:82:14:EF:AD:B5:05:3D:DB:A9:77:D1:57:F3:5A:DB:22:C9:F0:BC:9F:9B:D9",
)

/** Lets Android check that links to this host may open the app (Digital Asset Links). */
internal val ASSET_LINKS = """
    [{
      "relation": ["delegate_permission/common.handle_all_urls"],
      "target": {
        "namespace": "android_app",
        "package_name": "$APP_PACKAGE",
        "sha256_cert_fingerprints": [${APP_CERTIFICATES.joinToString { "\"$it\"" }}]
      }
    }]
""".trimIndent()

/**
 * Invitation links. With the app installed, Android opens the app instead of this page; the page is
 * only seen without the app. It shows nothing but the code from the link (no lookup of the room).
 */
fun Route.invites() {
    get("/.well-known/assetlinks.json") {
        call.respondText(ASSET_LINKS, ContentType.Application.Json)
    }
    get("$INVITE_PATH/{code}") {
        val code = call.parameters["code"]?.let(::parseRoomCode)
        if (code == null) {
            call.respondText("Kein gültiger Raum-Code.", status = HttpStatusCode.NotFound)
            return@get
        }
        // Static page without scripts or external content.
        call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.respondText(invitePage(code), ContentType.Text.Html)
    }
}

/** [code] has been checked by [parseRoomCode], so it is safe to put into the HTML. */
private fun invitePage(code: String): String {
    // Opens the app from browsers that show the page instead (e.g. inside a messenger).
    val appLink = "intent://$INVITE_HOST$INVITE_PATH/$code#Intent;scheme=https;package=$APP_PACKAGE;end"
    return """
        <!doctype html>
        <html lang="de">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="robots" content="noindex">
        <title>Einladung zu Waddeln</title>
        <style>
          body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
                 background: #3b2a1a; color: #fff; font-family: sans-serif; text-align: center; }
          main { padding: 24px; max-width: 420px; }
          .code { font: bold 48px monospace; color: #ffd54f; letter-spacing: 4px; margin: 12px 0 20px; }
          a.button { display: inline-block; padding: 14px 24px; border-radius: 10px; background: #2e5e2e;
                     color: #fff; text-decoration: none; font-size: 18px; }
          p { line-height: 1.5; opacity: .85; }
        </style>
        </head>
        <body><main>
        <h1>Du bist zu Waddeln eingeladen!</h1>
        <p>Raum-Code</p>
        <div class="code">$code</div>
        <p><a class="button" href="$appLink">In der App öffnen</a></p>
        <p>Hat sich die App nicht geöffnet? Starte Waddeln, tippe auf „Online spielen“ und gib den Code bei
        „Raum beitreten“ ein.</p>
        </main></body>
        </html>
    """.trimIndent()
}
