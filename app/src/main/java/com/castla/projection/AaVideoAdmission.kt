// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

/** Pure state for retaining Annex-B parameter sets and admitting only independently decodable starts. */
internal class AaVideoAdmission {
    private var waitingForIdr = true
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /** Always records SPS/PPS, including metadata-only AA packets. Returns true when active codec config changed. */
    fun updateMetadata(metadata: Map<Int, ByteArray>): Boolean {
        var changed = false
        metadata[7]?.let { if (!it.contentEquals(sps)) { sps = it.copyOf(); changed = true } }
        metadata[8]?.let { if (!it.contentEquals(pps)) { pps = it.copyOf(); changed = true } }
        return changed
    }

    fun admit(types: Set<Int>): Boolean {
        if (waitingForIdr && 5 !in types) return false
        if (5 in types) waitingForIdr = false
        return true
    }

    /** Returns true to have the caller request a fresh upstream IDR. */
    fun resync(): Boolean { waitingForIdr = true; return true }
    fun clear() { waitingForIdr = true; sps = null; pps = null }
    fun waitingForIdr() = waitingForIdr
    fun parameterSets(): Pair<ByteArray, ByteArray>? = if (sps != null && pps != null) sps!!.copyOf() to pps!!.copyOf() else null

    companion object {
        fun ensureAnnexBStartCode(nal: ByteArray): ByteArray {
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 0.toByte() && nal[3] == 1.toByte()) return nal.copyOf()
        if (nal.size >= 4 && nal[0] == 0.toByte() && nal[1] == 0.toByte() && nal[2] == 1.toByte()) return byteArrayOf(0, 0, 0, 1) + nal.copyOfRange(3, nal.size)
            return byteArrayOf(0, 0, 0, 1) + nal
        }
    }
}
