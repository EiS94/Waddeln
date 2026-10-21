package com.schaubeck.waddeln.protocol

import com.schaubeck.waddeln.rules.Action
import com.schaubeck.waddeln.rules.Observation
import com.schaubeck.waddeln.rules.PLAYER_COUNT
import com.schaubeck.waddeln.rules.agent.PartnerHint
import com.schaubeck.waddeln.rules.agent.SchadetAnswer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Messages between app and server over one WebSocket, as JSON text frames with a `type` field.
 *
 * Flow: the client sends [ClientMessage.Hello] after every (re)connect. The server answers with a
 * [ServerMessage.Table] if the player still has a seat in a room, otherwise with [ServerMessage.NoRoom].
 * From then on the server sends a fresh [ServerMessage.Table] after every change.
 */
const val PROTOCOL_VERSION = 1

/** Path of the WebSocket endpoint. */
const val WS_PATH = "/ws"

/** Room codes: 5 characters, ~33 million possibilities. */
const val ROOM_CODE_LENGTH = 5

/** Without easily confused characters (0/O, 1/I). */
const val ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/** Strength of the server bots, chosen by the host. */
@Serializable
enum class BotStrength(
    /** Randomness of the ML bot (0 = always the best move); null = rule-based bot. */
    val temperature: Double?,
) {
    EASY(null),
    MEDIUM(0.7),
    HARD(0.0),
}

@Serializable
sealed interface ClientMessage {
    /**
     * [playerId] is a random id kept on the device; it identifies the player across reconnects
     * and is never shown to others.
     */
    @Serializable @SerialName("hello")
    data class Hello(val playerId: String, val name: String, val version: Int = PROTOCOL_VERSION) : ClientMessage

    @Serializable @SerialName("create_room")
    data object CreateRoom : ClientMessage

    @Serializable @SerialName("join_room")
    data class JoinRoom(val code: String) : ClientMessage

    /** Move to another free seat (only before the match starts). */
    @Serializable @SerialName("take_seat")
    data class TakeSeat(val seat: Int) : ClientMessage

    /** Host only. */
    @Serializable @SerialName("set_bot_strength")
    data class SetBotStrength(val strength: BotStrength) : ClientMessage

    /** Host only: renames the bot of a seat no human sits on. */
    @Serializable @SerialName("set_bot_name")
    data class SetBotName(val seat: Int, val name: String) : ClientMessage

    /** Host only: start the match; empty seats are played by bots. */
    @Serializable @SerialName("start")
    data object StartMatch : ClientMessage

    @Serializable @SerialName("act")
    data class Act(val action: Action) : ClientMessage

    /**
     * During a Schub against the own team: ask the partner how good their cards are. A bot answers
     * at once, a human with [AnswerPartner]. Everybody at the table hears question and answer.
     */
    @Serializable @SerialName("ask_partner")
    data object AskPartner : ClientMessage

    /** Answer to the partner's [AskPartner]. */
    @Serializable @SerialName("answer_partner")
    data class AnswerPartner(val hint: PartnerHint) : ClientMessage

    /** Before announcing the Schlag: propose a Schlagwechsel to the dealer. */
    @Serializable @SerialName("propose_schlagwechsel")
    data object ProposeSchlagwechsel : ClientMessage

    /** The dealer's answer to [ProposeSchlagwechsel]. */
    @Serializable @SerialName("answer_schlagwechsel")
    data class AnswerSchlagwechsel(val accept: Boolean) : ClientMessage

    /** Before leading the first trick (with the Haubi or as a bluff): ask the partner "Schadder?". */
    @Serializable @SerialName("ask_schadet")
    data object AskSchadet : ClientMessage

    /** Answer to the partner's [AskSchadet]. */
    @Serializable @SerialName("answer_schadet")
    data class AnswerSchadet(val answer: SchadetAnswer) : ClientMessage

    /** Shows ([show] = true) or hides the last completed trick for the whole table; only allowed for the player to move. */
    @Serializable @SerialName("show_last_trick")
    data class ShowLastTrick(val show: Boolean) : ClientMessage

    /** After a round or match: ready for the next one. It starts once all connected players are ready. */
    @Serializable @SerialName("ready")
    data object Ready : ClientMessage

    @Serializable @SerialName("leave")
    data object LeaveRoom : ClientMessage
}

@Serializable
sealed interface ServerMessage {
    /** The player is not (or no longer) in a room. */
    @Serializable @SerialName("no_room")
    data object NoRoom : ServerMessage

    /** Complete view of the room for one player; sent after every change. */
    @Serializable @SerialName("table")
    data class Table(val room: RoomInfo, val yourSeat: Int, val game: GameView? = null) : ServerMessage

    /** A request could not be fulfilled; [message] is a German text for the user. */
    @Serializable @SerialName("error")
    data class Error(val message: String) : ServerMessage
}

@Serializable
data class RoomInfo(
    val code: String,
    val seats: List<SeatInfo>,
    val botStrength: BotStrength,
    /** True once the host started the first match. */
    val started: Boolean,
)

@Serializable
data class SeatInfo(
    /** Nickname of the human, or null if a bot plays this seat. */
    val name: String? = null,
    /** False while the human is disconnected (a bot plays for them meanwhile). */
    val connected: Boolean = false,
    val host: Boolean = false,
    /** Name of the bot that plays this seat while no human sits on it. */
    val botName: String? = null,
) {
    val isHuman: Boolean get() = name != null
}

/** Longest nickname or bot name; longer ones are cut. */
const val MAX_NAME_LENGTH = 16

@Serializable
data class GameView(
    val observation: Observation,
    val legalActions: List<Action>,
    /** The action that led to this view, if any (e.g. to show who answered a Schub). */
    val lastAction: SeatAction? = null,
    /** Per seat: confirmed to continue after the round (only meaningful while it is over). */
    val ready: List<Boolean> = List(PLAYER_COUNT) { false },
    /** Time left for the human decision the game waits for; afterwards a bot decides. Null if nobody is waited for. */
    val timeLeftMs: Long? = null,
    /** True if the partner can be asked about their cards ([ClientMessage.AskPartner]) now. */
    val canAskPartner: Boolean = false,
    /** Seat that asked its partner during the open Schub, or null. */
    val partnerQuestion: Int? = null,
    /** The partner's answer during the open Schub, or null. */
    val partnerHint: SeatHint? = null,
    /** True if we lead the first trick and may ask "Schadder?" ([ClientMessage.AskSchadet]). */
    val canAskSchadet: Boolean = false,
    /** Seat that asked "Schadder?" in this round, or null. */
    val schadetQuestion: Int? = null,
    /** The partner's answer to it, or null. */
    val schadetAnswer: SeatSchadet? = null,
    /** True if we may propose a Schlagwechsel ([ClientMessage.ProposeSchlagwechsel]); the talk itself is in the observation. */
    val canProposeSchlagwechsel: Boolean = false,
    /** True if we may show the last completed trick ([ClientMessage.ShowLastTrick]) now. */
    val canShowLastTrick: Boolean = false,
    /** Seat that shows the last completed trick to the table right now, or null. */
    val lastTrickShownBy: Int? = null,
)

@Serializable
data class SeatAction(val seat: Int, val action: Action)

@Serializable
data class SeatHint(val seat: Int, val hint: PartnerHint)

@Serializable
data class SeatSchadet(val seat: Int, val answer: SchadetAnswer)

/** The JSON format used on both ends. */
val ProtocolJson = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun ClientMessage.encode(): String = ProtocolJson.encodeToString(ClientMessage.serializer(), this)
fun ServerMessage.encode(): String = ProtocolJson.encodeToString(ServerMessage.serializer(), this)
fun decodeClientMessage(text: String): ClientMessage = ProtocolJson.decodeFromString(ClientMessage.serializer(), text)
fun decodeServerMessage(text: String): ServerMessage = ProtocolJson.decodeFromString(ServerMessage.serializer(), text)
