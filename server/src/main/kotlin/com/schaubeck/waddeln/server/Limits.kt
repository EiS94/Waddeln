package com.schaubeck.waddeln.server

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Protection against overload and guessing room codes when the server is reachable from the internet.
 * "Per IP" means per [networkOf] the address.
 */
data class Limits(
    val maxConnections: Int = 200,
    /** Several phones in one home network share an IP, so this must not be too small. */
    val maxConnectionsPerIp: Int = 12,
    /** A connection that has not introduced itself with a valid hello after this time is closed. */
    val helloTimeoutMs: Long = 10_000,
    val maxRooms: Int = 50,
    /** Open rooms created from one IP, so that a few addresses cannot take all [maxRooms]. */
    val maxRoomsPerIp: Int = 4,
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

/**
 * The network an address belongs to, for the limits per IP. A home connection gets a whole IPv6 /64
 * and can use any address in it, so for IPv6 only the first 64 bits count; IPv4 addresses stay as they are.
 */
internal fun networkOf(ip: String): String {
    // Only literals are parsed: a name (like "local" in tests) must not cause a DNS lookup.
    if (':' !in ip) return ip
    val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return ip
    if (address !is Inet6Address) return address.hostAddress // e.g. ::ffff:1.2.3.4
    val bytes = address.address
    return (0 until 8 step 2).joinToString(":", postfix = "::/64") { "%02x%02x".format(bytes[it], bytes[it + 1]) }
}

/** What one IP address is currently allowed to do. */
internal class IpQuota(limits: Limits, clock: () -> Long) {
    var connections = 0
    val roomCreations = TokenBucket(limits.roomCreationBurst, limits.roomCreationIntervalMs, clock)
    val failedJoins = TokenBucket(limits.failedJoinBurst, limits.failedJoinIntervalMs, clock)

    val unused: Boolean get() = connections == 0 && roomCreations.isFull && failedJoins.isFull
}
