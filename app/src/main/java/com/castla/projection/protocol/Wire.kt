// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection.protocol

import java.io.ByteArrayOutputStream

/** Small bounded protobuf reader/writer for the receiver's supported messages. */
internal object Wire {
    const val OFFERED_VIDEO_FPS = 30
    private const val OFFERED_VIDEO_FPS_ENUM = 1 // VideoFPSEnum._30
    data class Field(val number: Int, val value: Long? = null, val bytes: ByteArray? = null)
    fun v(field: Int, value: Long): ByteArray = varint((field shl 3).toLong()) + varint(value)
    fun b(field: Int, bytes: ByteArray): ByteArray = varint(((field shl 3) or 2).toLong()) + varint(bytes.size.toLong()) + bytes
    fun s(field: Int, text: String) = b(field, text.toByteArray(Charsets.UTF_8))
    private fun varint(value: Long): ByteArray {
        require(value >= 0)
        val out = ByteArrayOutputStream(); var n = value
        do { val low = (n and 127).toInt(); n = n ushr 7; out.write(low or if (n != 0L) 128 else 0) } while (n != 0L)
        return out.toByteArray()
    }
    fun fields(bytes: ByteArray): List<Field> {
        require(bytes.size <= 1_048_576) { "Protobuf exceeds limit" }
        var offset = 0
        fun read(): Long {
            var result = 0L
            for (shift in 0..63 step 7) {
                require(offset < bytes.size) { "Truncated varint" }
                val next = bytes[offset++].toInt() and 255
                require(shift < 63 || next <= 1) { "Varint overflow" }
                result = result or ((next and 127).toLong() shl shift)
                if (next and 128 == 0) return result
            }
            error("Varint overflow")
        }
        return buildList {
            while (offset < bytes.size) {
                require(size < 4096) { "Too many fields" }
                val tag = read(); val number = (tag ushr 3).toInt(); require(number > 0)
                when ((tag and 7).toInt()) {
                    0 -> add(Field(number, value = read()))
                    2 -> { val count = read(); require(count in 0..(bytes.size - offset).toLong())
                        add(Field(number, bytes = bytes.copyOfRange(offset, offset + count.toInt()))); offset += count.toInt() }
                    1, 5 -> { val count = if (tag and 7 == 1L) 8 else 4; require(offset + count <= bytes.size); offset += count }
                    else -> error("Unsupported protobuf field")
                }
            }
        }
    }
    fun first(bytes: ByteArray, field: Int): Long? = fields(bytes).firstOrNull { it.number == field }?.value
    fun id(bytes: ByteArray): Int { require(bytes.size >= 2); return ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255) }
    fun body(bytes: ByteArray) = bytes.copyOfRange(2, bytes.size)
    fun msg(id: Int, body: ByteArray = byteArrayOf()) = byteArrayOf((id ushr 8).toByte(), id.toByte()) + body
    fun ack(session: Int) = msg(0x8004, v(1, session.toLong()) + v(2, 1))
    fun touch(x: Int, y: Int, action: Int, timeMicros: Long) = msg(0x8001,
        v(1, timeMicros) + b(3, b(1, v(1, x.toLong()) + v(2, y.toLong()) + v(3, 0)) + v(2, 0) + v(3, action.toLong())))
    fun key(code: Int, down: Boolean, timeMicros: Long) = msg(0x8001,
        v(1, timeMicros) + b(4, b(1, v(1, code.toLong()) + v(2, if (down) 1 else 0) + v(3, 0) + v(4, 0))))
    fun discovery(width: Int, height: Int, dpi: Int, rate: Int, bits: Int): ByteArray {
        val resolution = when (width to height) { 1280 to 720 -> 2L; 1920 to 1080 -> 3L; else -> error("Unsupported resolution") }
        fun audio(channel: Int, type: Int, hz: Int, depth: Int, channels: Int) = b(1,
            v(1, channel.toLong()) + b(3, v(1, 1) + v(2, type.toLong()) +
                b(3, v(1, hz.toLong()) + v(2, depth.toLong()) + v(3, channels.toLong())) + v(5, 1)))
        val inputCodes = listOf(3, 4, 19, 20, 21, 22, 23, 84, 85, 87, 88, 126, 127)
            .map { v(1, it.toLong()) }.fold(byteArrayOf()) { a, c -> a + c }
        val services =
            b(1, v(1, 7) + b(5, v(1, 1) + b(2, v(1, 16000) + v(2, 16) + v(3, 1)))) +
            audio(4, 3, rate, bits, 2) + audio(5, 1, 16000, 16, 1) + audio(6, 2, 16000, 16, 1) +
            b(1, v(1, 2) + b(2, b(1, v(1, 10)))) +
            b(1, v(1, 3) + b(3, v(1, 3) + b(4, v(1, resolution) + v(2, OFFERED_VIDEO_FPS_ENUM.toLong()) + v(5, dpi.toLong())) + v(5, 1))) +
            b(1, v(1, 1) + b(4, inputCodes + b(2, v(1, width.toLong()) + v(2, height.toLong()))))
        return msg(6, services + s(2, "Luma Drive") + s(3, "Projection Receiver") + s(4, "2026") +
            s(5, "local") + v(6, 1) + s(7, "Luma Drive") + s(8, "Projection") + s(9, "1") + s(10, "0.2.0"))
    }
}
