package com.schaubeck.waddeln.server

/** Protection against overload and guessing room codes when the server is reachable from the internet. */
data class Limits(
    val maxConnections: Int = 200,
    /** Several phones in one home network share an IP, so this must not be too small. */
    val maxConnectionsPerIp: Int = 12,
    val maxRooms: Int = 50,
    /** Messages per connection: a burst, then one per [messageIntervalMs]. */
    val messageBurst: Int = 40,
    val messageIntervalMs: Long = 100,
    /** Rooms created per IP: a burst, then one per [roomCreationIntervalMs]. */
    val roomCreationBurst: Int = 6,
    val roomCreationIntervalMs: Long = 2 * 60_000,
    /** Joins with an unknown code per IP: a burst, then one per [failedJoinIntervalMs]. */
    val failedJoinBurst: Int = 10,
    val failedJoinIntervalMs: Long = 6_000,
    /** Rooms nobody is connected to are closed after this time; before the start sooner. */
    val idleRoomMs: Long = 30 * 60_000,
    val idleLobbyMs: Long = 5 * 60_000,
)

/** Token bucket: up to [capacity] at once, refilled by one token every [intervalMs]. */
class TokenBucket(private val capacity: Int, private val intervalMs: Long, private val clock: () -> Long) {
    private var tokens = capacity.toDouble()
    private var updated = clock()

    fun tryTake(): Boolean {
        refill()
        if (tokens < 1) return false
        tokens -= 1
        return true
    }

    val isFull: Boolean get() = refill().let { tokens >= capacity }

    /** At least one token left (without taking it). */
    val available: Boolean get() = refill().let { tokens >= 1 }

    private fun refill() {
        val now = clock()
        tokens = minOf(capacity.toDouble(), tokens + (now - updated).toDouble() / intervalMs)
        updated = now
    }
}

/** What one IP address is currently allowed to do. */
internal class IpQuota(limits: Limits, clock: () -> Long) {
    var connections = 0
    val roomCreations = TokenBucket(limits.roomCreationBurst, limits.roomCreationIntervalMs, clock)
    val failedJoins = TokenBucket(limits.failedJoinBurst, limits.failedJoinIntervalMs, clock)

    val unused: Boolean get() = connections == 0 && roomCreations.isFull && failedJoins.isFull
}
