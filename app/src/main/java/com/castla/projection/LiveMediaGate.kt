// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

/** Extracts Annex-B codec metadata without retaining any slice NALs. */
internal object H264Metadata {
    data class Parsed(val types: Set<Int>, val codecMetadata: Map<Int, ByteArray>)

    /** One boundary scan; only SPS/PPS ranges are copied, never slice payloads. */
    fun parse(data: ByteArray): Parsed {
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        // Detect a final three-byte prefix even when it has no NAL header; it still
        // terminates the preceding NAL and must not be mistaken for that NAL's payload.
        while (i + 2 < data.size) {
            val prefix = when {
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> 3
                i + 4 <= data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix != 0) { starts.add(i to prefix); i += prefix } else i++
        }
        val types = LinkedHashSet<Int>()
        val metadata = LinkedHashMap<Int, ByteArray>()
        starts.forEachIndexed { index, (start, prefix) ->
            val end = starts.getOrNull(index + 1)?.first ?: data.size
            val header = start + prefix
            if (header < end) {
                val type = data[header].toInt() and 31
                types.add(type)
                if (type == 7 || type == 8) metadata[type] = data.copyOfRange(start, end)
            }
        }
        return Parsed(types, metadata)
    }

    fun extract(data: ByteArray): Map<Int, ByteArray> = parse(data).codecMetadata
    fun types(data: ByteArray): Set<Int> = parse(data).types
}

/** Per-viewer state: dependent frames are withheld until a fresh live IDR arrives. */
internal class LiveVideoGate {
    private var waitingForIdr = true
    @Synchronized fun resync() { waitingForIdr = true }
    @Synchronized fun accept(types: Set<Int>): Boolean {
        if (!waitingForIdr) return true
        if (5 !in types) return false
        waitingForIdr = false
        return true
    }
    @Synchronized fun isWaiting() = waitingForIdr
}
