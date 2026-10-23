// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

/** Stores numeric transport evidence only. Never retains or logs music samples. */
internal class AudioProbe(val rate: Int, val bits: Int, val channels: Int) {
    private var packets = 0L; private var bytes = 0L; private var firstAt = 0L; private var lastAt = 0L
    private var firstPts: Long? = null; private var lastPts: Long? = null; private var bytesAfterFirst = 0L
    private var unaligned = 0L; private var nonzero = 0L; private var lowByteNonzero = 0L
    @Synchronized fun accept(pcm: ByteArray, pts: Long?, nowMs: Long) {
        if (packets == 0L) firstAt = nowMs else bytesAfterFirst += pcm.size
        packets++; bytes += pcm.size; lastAt = nowMs
        if (firstPts == null && pts != null) firstPts = pts
        if (pts != null) lastPts = pts
        if (pcm.size % (channels * bits / 8) != 0) unaligned++
        pcm.forEachIndexed { index, b -> if (b != 0.toByte()) { nonzero++; if (bits == 24 && index % 3 == 0) lowByteNonzero++ } }
    }
    @Synchronized fun facts(): Map<String, Any> = linkedMapOf(
        "advertisedSampleRate" to rate, "advertisedBitDepth" to bits, "channels" to channels,
        "packets" to packets, "payloadBytes" to bytes, "nonzeroBytes" to nonzero,
        "unalignedPacketsForAdvertisedFormat" to unaligned, "lowByteNonzeroFor24BitInterpretation" to lowByteNonzero,
        "wallClockBytesPerSecond" to if (lastAt > firstAt) bytesAfterFirst * 1000.0 / (lastAt - firstAt) else 0.0,
        "timestampSpanRaw" to ((lastPts ?: 0) - (firstPts ?: 0)),
        "bytesAfterFirstPacket" to bytesAfterFirst,
        "expectedBytesPerSecond" to rate * channels * bits / 8,
        "precisionVerified" to false,
        "interpretation" to "Format is the receiver offer. Peer setup and byte/timestamp evidence must be inspected; 24-bit precision is not inferred from a label.")
}
