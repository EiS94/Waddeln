package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.parseRoomCode
import com.schaubeck.waddeln.rules.agent.RuleBasedAgent
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteTest {

    private fun invites(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = testApplication {
        val gameThread = Dispatchers.Default.limitedParallelism(1)
        val server = GameServer(CoroutineScope(SupervisorJob() + gameThread), { RuleBasedAgent() }, Timing(), Random(1))
        application { waddeln(server, gameThread) }
        block()
    }

    @Test
    fun `asset links are valid JSON`() = invites {
        val response = client.get("/.well-known/assetlinks.json")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.contentType()!!.match(ContentType.Application.Json))
        assertEquals(1, Json.parseToJsonElement(response.bodyAsText()).jsonArray.size)
    }

    @Test
    fun `invitation page shows the code`() = invites {
        val response = client.get("/r/k7qmx")
        assertEquals(HttpStatusCode.OK, response.status)
        val page = response.bodyAsText()
        assertTrue("K7QMX" in page)
        assertTrue(response.headers["Content-Security-Policy"]!!.startsWith("default-src 'none'"))
    }

    @Test
    fun `invalid codes are not put into the page`() = invites {
        for (path in listOf("/r/%3Cscript%3E", "/r/K7QM", "/r/K7QMX1", "/r/K0QMX")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.NotFound, response.status, path)
            assertFalse("<" in response.bodyAsText(), path)
        }
    }

    @Test
    fun `room codes are parsed strictly`() {
        assertEquals("K7QMX", parseRoomCode(" k7qmx "))
        assertNull(parseRoomCode("K7QM"))
        assertNull(parseRoomCode("K7QMXX"))
        assertNull(parseRoomCode("K7QM0")) // 0 is not in the alphabet
        assertNull(parseRoomCode("K7Q-X"))
    }
}
