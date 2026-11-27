package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.BotStrength
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One match played on the server: finished, or broken off because everybody left. */
data class MatchRecord(
    val room: String,
    val startedAt: Long,
    val endedAt: Long,
    /** Rounds dealt, including a broken-off one. */
    val rounds: Int,
    val scores: List<Int>,
    /** Null if the match was broken off. */
    val winningTeam: Int?,
    /** Human name per seat (also those who were offline at the end), null for a bot. */
    val seats: List<String?>,
    val bots: BotStrength,
) {
    /** One line of JSON; times in local time (the container runs with TZ=Europe/Berlin). */
    fun toJson(zone: ZoneId = ZoneId.systemDefault()): String = buildJsonObject {
        put("start", TIME.format(Instant.ofEpochMilli(startedAt).atZone(zone)))
        put("end", TIME.format(Instant.ofEpochMilli(endedAt).atZone(zone)))
        put("minutes", (endedAt - startedAt) / 60_000)
        put("room", room)
        put("finished", winningTeam != null)
        put("winner", winningTeam?.let(::JsonPrimitive) ?: JsonNull)
        putJsonArray("scores") { scores.forEach { add(JsonPrimitive(it)) } }
        put("rounds", rounds)
        putJsonArray("seats") { seats.forEach { add(it?.let(::JsonPrimitive) ?: JsonNull) } }
        put("bots", bots.name)
    }.toString()

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

/** Where played matches go, for statistics that survive a redeploy (unlike the container log). */
fun interface MatchLog {
    fun record(match: MatchRecord)

    companion object {
        val NONE = MatchLog {}
    }
}

/** Appends every match as one JSON line to [path]; a write error only costs that line. */
class MatchFile(private val path: Path) : MatchLog {
    override fun record(match: MatchRecord) {
        try {
            Files.writeString(path, match.toJson() + "\n", CREATE, APPEND)
        } catch (e: IOException) {
            log.warn("Could not write match to {}: {}", path, e.toString())
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MatchFile::class.java)
    }
}
