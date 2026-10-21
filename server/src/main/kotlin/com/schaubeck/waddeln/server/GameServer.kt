package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.ClientMessage
import com.schaubeck.waddeln.protocol.MAX_NAME_LENGTH
import com.schaubeck.waddeln.protocol.PROTOCOL_VERSION
import com.schaubeck.waddeln.protocol.ROOM_CODE_ALPHABET
import com.schaubeck.waddeln.protocol.ROOM_CODE_LENGTH
import com.schaubeck.waddeln.protocol.ServerMessage
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import kotlin.random.Random

/** Sends messages to one connected app; must not block. */
interface Connection {
    fun send(message: ServerMessage)
    fun close()
}

/** One WebSocket connection from [ip]; [playerId] is known after [ClientMessage.Hello]. */
class Client(private val connection: Connection, val ip: String, internal val messages: TokenBucket) {
    var playerId: String? = null
        internal set
    var name: String = ""
        internal set

    fun send(message: ServerMessage) = connection.send(message)
    fun close() = connection.close()
}

/**
 * Manages connections and rooms. Not thread-safe: all calls must come from one game thread,
 * which is also the thread of [scope] (bots run there).
 *
 * Built so that a public matchmaking queue can be added later: it would only create rooms
 * and seat waiting players via [Room.join].
 */
class GameServer(
    private val scope: CoroutineScope,
    private val bots: BotProvider,
    private val timing: Timing = Timing(),
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limits: Limits = Limits(),
) {
    private val clients = mutableSetOf<Client>()
    private val rooms = mutableMapOf<String, Room>()
    private val playerRooms = mutableMapOf<String, Room>()
    private val quotas = mutableMapOf<String, IpQuota>()

    val roomCount: Int get() = rooms.size

    /** One line for the log: how much is going on. */
    val status: String
        get() {
            val players = clients.count { it.playerId != null }
            val playing = rooms.values.count { it.started }
            return "${clients.size} connections, $players players, ${rooms.size} rooms ($playing playing)"
        }

    /** True if anybody is connected or a room is open, so a status line is worth logging. */
    val active: Boolean get() = clients.isNotEmpty() || rooms.isNotEmpty()

    /** Registers a new connection, or returns null if the server or this [ip] has too many. */
    fun connect(connection: Connection, ip: String = "local"): Client? {
        val quota = quotas.getOrPut(ip) { IpQuota(limits, clock) }
        if (clients.size >= limits.maxConnections || quota.connections >= limits.maxConnectionsPerIp) {
            log.warn("Connection refused ({} connections, {} from this IP)", clients.size, quota.connections)
            return null
        }
        quota.connections++
        return Client(connection, ip, TokenBucket(limits.messageBurst, limits.messageIntervalMs, clock)).also { clients += it }
    }

    fun disconnect(client: Client) {
        if (!clients.remove(client)) return
        quotas[client.ip]?.let { it.connections-- }
        val id = client.playerId ?: return
        val room = playerRooms[id]
        if (room == null) {
            log.info("'{}' disconnected ({})", client.name, status)
            return
        }
        val member = room.members.first { it.playerId == id }
        if (member.client === client) {
            log.info("'{}' disconnected from room {} ({})", client.name, room.code, status)
            member.client = null
            room.connectionChanged()
        }
    }

    fun onMessage(client: Client, message: ClientMessage) {
        if (!client.messages.tryTake()) {
            log.warn("Too many messages from '{}', closing connection", client.name)
            client.send(ServerMessage.Error("Zu viele Nachrichten – Verbindung wird neu aufgebaut."))
            client.close()
            return
        }
        if (message is ClientMessage.Hello) return hello(client, message)
        val id = client.playerId ?: return client.send(ServerMessage.Error("Bitte zuerst anmelden."))
        val room = playerRooms[id]
        val error = when (message) {
            is ClientMessage.Hello -> null
            ClientMessage.CreateRoom -> createRoom(client, id)
            is ClientMessage.JoinRoom -> joinRoom(client, id, message.code)
            ClientMessage.LeaveRoom -> leaveRoom(client, id)
            is ClientMessage.TakeSeat -> room?.takeSeat(id, message.seat)
            is ClientMessage.SetBotStrength -> room?.setStrength(id, message.strength)
            is ClientMessage.SetBotName -> room?.setBotName(id, message.seat, message.name)
            ClientMessage.StartMatch -> room?.start(id)
            is ClientMessage.Act -> room?.act(id, message.action)
            ClientMessage.AskPartner -> room?.askPartner(id)
            is ClientMessage.AnswerPartner -> room?.answerPartner(id, message.hint)
            ClientMessage.AskSchadet -> room?.askSchadet(id)
            is ClientMessage.AnswerSchadet -> room?.answerSchadet(id, message.answer)
            is ClientMessage.ShowLastTrick -> room?.showLastTrick(id, message.show)
            ClientMessage.Ready -> room?.ready(id)
        }
        error?.let { client.send(ServerMessage.Error(it)) }
    }

    /** Removes rooms nobody has been connected to for a while and forgets unused IPs. Called periodically. */
    fun cleanup() {
        val now = clock()
        rooms.values.filter { r ->
            val maxIdle = if (r.started) limits.idleRoomMs else limits.idleLobbyMs
            r.abandonedSince?.let { now - it >= maxIdle } == true
        }.forEach {
            log.info("Room {} abandoned", it.code)
            removeRoom(it)
        }
        quotas.values.removeIf { it.unused }
    }

    private fun hello(client: Client, hello: ClientMessage.Hello) {
        if (hello.version != PROTOCOL_VERSION) {
            log.info("Outdated app (protocol {}, server {}) refused", hello.version, PROTOCOL_VERSION)
            client.send(ServerMessage.Error("Bitte aktualisiere die App, um online zu spielen."))
            return
        }
        if (hello.playerId.isBlank() || hello.playerId.length > 64) {
            client.send(ServerMessage.Error("Ungültige Geräte-ID."))
            return
        }
        client.playerId = hello.playerId
        client.name = cleanName(hello.name).ifBlank { "Spieler" }
        // The same player connected again (e.g. new network): the old connection is dropped.
        clients.filter { it !== client && it.playerId == hello.playerId }.forEach {
            it.playerId = null
            it.close()
        }
        val room = playerRooms[hello.playerId]
        if (room == null) {
            log.info("'{}' connected ({})", client.name, status)
            client.send(ServerMessage.NoRoom)
            return
        }
        val member = room.members.first { it.playerId == hello.playerId }
        member.client = client
        member.name = client.name
        log.info("'{}' reconnected to room {} ({})", client.name, room.code, status)
        room.connectionChanged()
    }

    private fun createRoom(client: Client, id: String): String? {
        if (rooms.size >= limits.maxRooms) {
            log.warn("Room limit reached, '{}' could not create a room", client.name)
            return "Gerade sind zu viele Räume offen. Versuch es später noch einmal."
        }
        if (!quota(client).roomCreations.tryTake()) {
            log.warn("'{}' created too many rooms", client.name)
            return "Du hast zu viele Räume erstellt. Warte ein paar Minuten."
        }
        leaveCurrentRoom(id)
        val room = Room(newCode(), scope, bots, timing, random, clock)
        rooms[room.code] = room
        log.info("Room {} created by '{}' ({})", room.code, client.name, status)
        return enter(room, client, id)
    }

    private fun joinRoom(client: Client, id: String, rawCode: String): String? {
        val code = rawCode.trim().uppercase().take(ROOM_CODE_LENGTH)
        val attempts = quota(client).failedJoins
        // Guessing codes: after too many wrong ones, even right ones are refused for a while.
        if (!attempts.available) {
            log.warn("'{}' is blocked after too many wrong room codes", client.name)
            return "Zu viele falsche Codes. Warte eine Minute."
        }
        val room = rooms[code] ?: return "Raum $code gibt es nicht.".also {
            log.info("'{}' tried unknown room code {}", client.name, code)
            attempts.tryTake()
        }
        if (playerRooms[id] === room) {
            room.connectionChanged()
            return null
        }
        if (room.members.size >= 4) return "Der Raum $code ist voll."
        leaveCurrentRoom(id)
        return enter(room, client, id)
    }

    private fun enter(room: Room, client: Client, id: String): String? {
        val error = room.join(Member(id, client.name, client))
        if (error == null) {
            playerRooms[id] = room
            log.info("'{}' joined room {} ({} players there)", client.name, room.code, room.members.size)
        }
        return error
    }

    private fun leaveRoom(client: Client, id: String): String? {
        leaveCurrentRoom(id)
        client.send(ServerMessage.NoRoom)
        return null
    }

    private fun leaveCurrentRoom(id: String) {
        val room = playerRooms.remove(id) ?: return
        val name = room.members.first { it.playerId == id }.name
        room.leave(id)
        log.info("'{}' left room {} ({} players there)", name, room.code, room.members.size)
        if (room.isEmpty) removeRoom(room)
    }

    private fun removeRoom(room: Room) {
        room.close()
        rooms.remove(room.code)
        room.members.forEach { playerRooms.remove(it.playerId) }
        log.info("Room {} closed ({})", room.code, status)
    }

    private fun newCode(): String {
        while (true) {
            val code = String(CharArray(ROOM_CODE_LENGTH) { ROOM_CODE_ALPHABET[random.nextInt(ROOM_CODE_ALPHABET.length)] })
            if (code !in rooms) return code
        }
    }

    private fun quota(client: Client) = quotas.getOrPut(client.ip) { IpQuota(limits, clock) }

    companion object {
        private val log = LoggerFactory.getLogger(GameServer::class.java)
    }
}

/** A name as shown to others: without control characters, at most [MAX_NAME_LENGTH] long; blank if nothing is left. */
internal fun cleanName(name: String): String =
    name.trim().filter { !it.isISOControl() }.take(MAX_NAME_LENGTH).trim()
