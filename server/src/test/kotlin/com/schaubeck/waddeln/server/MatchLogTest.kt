package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.BotStrength
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchLogTest {
    private val match = MatchRecord(
        room = "AB3CD",
        startedAt = 0,
        endedAt = 25 * 60_000L + 30_000,
        rounds = 4,
        scores = listOf(15, 9),
        winningTeam = 0,
        seats = listOf("Eike", null, "Jo \"J\"", null),
        bots = BotStrength.HARD,
    )

    @Test
    fun `a match is one line of JSON`() {
        assertEquals(
            """{"start":"1970-01-01 00:00:00","end":"1970-01-01 00:25:30","minutes":25,"room":"AB3CD",""" +
                """"finished":true,"winner":0,"scores":[15,9],"rounds":4,"seats":["Eike",null,"Jo \"J\"",null],"bots":"HARD"}""",
            match.toJson(ZoneOffset.UTC),
        )
    }

    @Test
    fun `the file gets one line per match`() {
        val dir = Files.createTempDirectory("matches")
        val file = dir.resolve("matches.jsonl")
        val log = MatchFile(file)
        log.record(match)
        log.record(match.copy(winningTeam = null))
        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertEquals(true, lines[1].contains("\"finished\":false,\"winner\":null"))
        file.toFile().delete()
        dir.toFile().delete()
    }

    @Test
    fun `a write error does not throw`() {
        MatchFile(Files.createTempDirectory("matches").resolve("missing/matches.jsonl")).record(match)
    }
}
