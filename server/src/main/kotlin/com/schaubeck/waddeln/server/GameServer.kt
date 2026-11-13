package com.schaubeck.waddeln.server

import com.schaubeck.waddeln.protocol.ClientMessage
import com.schaubeck.waddeln.protocol.MAX_NAME_LENGTH
import com.schaubeck.waddeln.protocol.PROTOCOL_VERSION
import com.schaubeck.waddeln.protocol.ROOM_CODE_ALPHABET
import com.schaubeck.waddeln.protocol.ROOM_CODE_LENGTH
import com.schaubeck.waddeln.protocol.ServerMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/** Sends messages to one connected app; must not block. */
interface Connection {
    fun send(message: ServerMessage)
    fun close()
}

/** One WebSocket connection from [ip] (see [networkOf]); [playerId] is known after [ClientMessage.Hello]. */
class Client(private val connection: Connection, val ip: String, internal val messages: TokenBucket) {
    var playerId: String? = null
        internal set
    var name: String = ""
        internal set

    /** Sent too many messages; the connection is being closed. */
    internal var flooded = false

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
    /** Room codes and shuffling: unpredictable, so that nobody can work out other rooms or hands. */
    private val random: Random = SecureRandom().asKotlinRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val limits: Limits = Limits(),
) {
    private val clients = mutableSetOf<Client>()
    private val rooms = mutableMapOf<String, Room>()
    private val playerRooms = mutableMapOf<String, Room>()
    private val quotas = mutableMapOf<String, IpQuota>()

    /** Room code → IP the room was created from, for [Limits.maxRoomsPerIp]. */
    private val roomCreators = mutableMapOf<String, String>()

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
    fun connect(connection: Connection, address: String = "local"): Client? {
        val ip = networkOf(address)
        val quota = quotas.getOrPut(ip) { IpQuota(limits, clock) }
        if (clients.size >= limits.maxConnections || quota.connections >= limits.maxConnectionsPerIp) {
            log.warn("Connection refused ({} connections, {} from this IP)", clients.size, quota.connections)
            return null
        }
        quota.connections++
        val client = Client(connection, ip, TokenBucket(limits.messageBurst, limits.messageIntervalMs, clock))
        clients += client
        // Who does not introduce themselves (or is refused as outdated) must not keep a connection busy.
        scope.launch {
            delay(limits.helloTimeoutMs)
            if (client in clients && client.playerId == null) {
                log.info("Connection without hello closed")
                client.close()
            }
        }
        return client
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
        if (!takeMessage(client)) return
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
            is ClientMessage.Cut -> room?.cut(id, message.cut)
            ClientMessage.AskPartner -> room?.askPartner(id)
            is ClientMessage.AnswerPartner -> room?.answerPartner(id, message.hint)
            ClientMessage.ProposeSchlagwechsel -> room?.proposeSchlagwechsel(id)
            is ClientMessage.AnswerSchlagwechsel -> room?.answerSchlagwechsel(id, message.accept)
            ClientMessage.AskSchadet -> room?.askSchadet(id)
            is ClientMessage.AnswerSchadet -> room?.answerSchadet(id, message.answer)
            ClientMessage.AskAdvice -> room?.askAdvice(id)
            is ClientMessage.AnswerAdvice -> room?.answerAdvice(id, message.answer)
            is ClientMessage.ShowLastTrick -> room?.showLastTrick(id, message.show)
            ClientMessage.CutSeen -> room?.cutSeen(id)
            ClientMessage.Ready -> room?.ready(id)
        }
        error?.let { client.send(ServerMessage.Error(it)) }
    }

    /** A message that could not be read; it counts against the message limit like any other. */
    fun onUnreadableMessage(client: Client) {
        if (!takeMessage(client)) return
        client.send(ServerMessage.Error("Unbekannte Nachricht."))
    }

    /** Takes one message from the client's allowance, or closes the connection of a client that floods. */
    private fun takeMessage(client: Client): Boolean {
        if (client.messages.tryTake()) return true
        // Messages already on the way arrive after closing; they are dropped without a word.
        if (client.flooded) return false
        client.flooded = true
        log.warn("Too many messages from '{}', closing connection", client.name)
        client.send(ServerMessage.Error("Zu viele Nachrichten – Verbindung wird neu aufgebaut."))
        client.close()
        return false
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
        // A connection belongs to one player: as somebody else it would keep the old seat "connected" for good.
        if (client.playerId != null && client.playerId != hello.playerId) {
            client.send(ServerMessage.Error("Du bist schon angemeldet."))
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
        if (roomCreators.values.count { it == client.ip } >= limits.maxRoomsPerIp) {
            log.warn("'{}' has too many open rooms", client.name)
            return "Von deinem Netz aus sind schon zu viele Räume offen."
        }
        if (!quota(client).roomCreations.tryTake()) {
            log.warn("'{}' created too many rooms", client.name)
            return "Du hast zu viele Räume erstellt. Warte ein paar Minuten."
        }
        leaveCurrentRoom(id)
        val room = Room(newCode(), scope, bots, timing, random, clock)
        rooms[room.code] = room
        roomCreators[room.code] = client.ip
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
        roomCreators.remove(room.code)
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

/**
 * A name as shown to others: without control and invisible format characters (e.g. a right-to-left
 * override that turns the following text around), at most [MAX_NAME_LENGTH] long; blank if nothing is left.
 * The zero width joiner stays, it holds emoji like 👨‍👩‍👧 together.
 */
internal fun cleanName(name: String): String =
    name.trim().filter { !it.isISOControl() && (Character.getType(it) != Character.FORMAT.toInt() || it == ZERO_WIDTH_JOINER) }
        .take(MAX_NAME_LENGTH).trim()

private const val ZERO_WIDTH_JOINER = '‍'

