// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

/** Bounded, aged, generation-tagged PCM queue. Session IDs prevent queued audio replay to new peers. */
internal class BoundedPcmQueue(private val maxBytes: Int, private val maxAgeNs: Long) {
    data class Item internal constructor(val bytes: ByteArray, val enqueuedAtNs: Long, val epoch: Long, val sessionIds: Set<String>)
    private val items = ArrayDeque<Item>()
    private var bytes = 0
    private var epoch = 0L

    @Synchronized fun offer(packet: ByteArray, sessionIds: Set<String>, nowNs: Long): Boolean {
        if (packet.isEmpty() || packet.size > maxBytes || sessionIds.isEmpty() || bytes + packet.size > maxBytes) return false
        val copy = packet.copyOf()
        items.addLast(Item(copy, nowNs, epoch, sessionIds.toSet())); bytes += copy.size
        return true
    }

    @Synchronized fun poll(): Item? = if (items.isEmpty()) null else items.removeFirst().also { bytes -= it.bytes.size }
    @Synchronized fun isFresh(item: Item, nowNs: Long) = item.epoch == epoch && nowNs - item.enqueuedAtNs <= maxAgeNs
    @Synchronized fun reset() { epoch++; items.clear(); bytes = 0 }
    @Synchronized fun sizeBytes() = bytes
    @Synchronized fun size() = items.size
}
