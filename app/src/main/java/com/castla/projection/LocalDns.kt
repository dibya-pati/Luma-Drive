// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Locale

/** Bounded IPv4 mDNS codec. Answers only our own local name; never forwards DNS. */
internal object LocalDns {
    data class Question(val name: String, val type: Int, val clazz: Int)
    data class Record(val name: String, val type: Int, val data: ByteArray)
    data class Message(val id: Int, val flags: Int, val questions: List<Question>, val records: List<Record>)
    fun parse(bytes: ByteArray): Message {
        require(bytes.size in 12..1500)
        fun u16(at: Int): Int { require(at >= 0 && at + 2 <= bytes.size); return ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255) }
        fun name(at: Int): Pair<String, Int> {
            var p = at; var end = -1; var hops = 0; var length = 0
            val labels = ArrayList<String>(); val visited = HashSet<Int>()
            while (true) {
                require(p in bytes.indices && hops++ < 64 && visited.add(p))
                val n = bytes[p].toInt() and 255
                if (n == 0) { if (end < 0) end = p + 1; break }
                if (n and 192 == 192) { val target = u16(p) and 0x3fff; if (end < 0) end = p + 2; p = target; continue }
                require(n in 1..63 && p + n + 1 <= bytes.size)
                length += n + 1; require(length <= 254)
                labels.add(String(bytes, p + 1, n, Charsets.US_ASCII)); p += n + 1
            }
            return labels.joinToString(".").lowercase(Locale.ROOT) to end
        }
        val qd = u16(4); val rr = u16(6) + u16(8) + u16(10)
        require(qd <= 16 && rr <= 64)
        var p = 12
        val questions = List(qd) { val (n, next) = name(p); p = next; val q = Question(n, u16(p), u16(p + 2)); p += 4; q }
        val records = List(rr) {
            val (n, next) = name(p); p = next
            val type = u16(p); val size = u16(p + 8); p += 10
            require(p + size <= bytes.size)
            Record(n, type, bytes.copyOfRange(p, p + size)).also { p += size }
        }
        return Message(u16(0), u16(2), questions, records)
    }
    private fun DataOutputStream.name(host: String) {
        require(host.endsWith(".local") && host.length <= 253)
        host.split('.').forEach { label -> require(label.length in 1..63 && label.all { it.isLetterOrDigit() || it == '-' }); writeByte(label.length); write(label.toByteArray(Charsets.US_ASCII)) }
        writeByte(0)
    }
    private fun packet(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()
    private fun DataOutputStream.record(host: String, address: ByteArray, ttl: Int, flush: Boolean) {
        require(address.size == 4 && ttl >= 0)
        name(host); writeShort(1); writeShort(if (flush) 0x8001 else 1); writeInt(ttl); writeShort(4); write(address)
    }
    fun probe(host: String, address: ByteArray): ByteArray = packet {
        writeShort(0); writeShort(0); writeShort(1); writeShort(0); writeShort(1); writeShort(0)
        name(host); writeShort(255); writeShort(1); record(host, address, 120, false)
    }
    fun answer(host: String, address: ByteArray, ttl: Int = 120, id: Int = 0, legacy: Question? = null): ByteArray = packet {
        writeShort(id); writeShort(0x8400); writeShort(if (legacy == null) 0 else 1); writeShort(1); writeShort(0); writeShort(0)
        if (legacy != null) { name(legacy.name); writeShort(legacy.type); writeShort(legacy.clazz and 0x7fff) }
        record(host, address, if (legacy == null) ttl else minOf(ttl, 10), legacy == null)
    }
    fun requested(message: Message, host: String) = message.flags and 0x8000 == 0 && message.questions.any {
        it.name == host && it.type in listOf(1, 255) && it.clazz and 0x7fff == 1
    }
    fun conflict(message: Message, host: String, address: ByteArray) = message.records.any {
        it.name == host && it.type == 1 && it.data.size == 4 && !it.data.contentEquals(address)
    }
}
