// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection.protocol
import org.junit.Assert.*
import org.junit.Test
class WireTest {
    @Test fun discoveryIncludesMicrophoneServiceRequiredForStartup() {
        val services = Wire.fields(Wire.body(Wire.discovery(1920,1080,220,48000,16))).filter { it.number == 1 }
        val mic = services.map { Wire.fields(it.bytes!!) }.single { it.first { f -> f.number == 1 }.value == 7L }
        val input = Wire.fields(mic.single { it.number == 5 }.bytes!!)
        assertEquals(1L, input.single { it.number == 1 }.value)
        assertEquals(listOf(16000L,16L,1L), Wire.fields(input.single { it.number == 2 }.bytes!!).map { it.value })
    }
    @Test fun modernVersionRequestMatchesOfficialDhu() {
        assertArrayEquals(byteArrayOf(0,3,0,6,0,1,0,1,0,7), AasdkBootstrapCodec.versionRequest(7))
    }
    @Test fun mediaOfferKeepsSpeechSeparate() {
        for (depth in listOf(16,24)) {
            val services = Wire.fields(Wire.body(Wire.discovery(1920,1080,220,48000,depth))).filter { it.number == 1 }
            fun config(channel: Long): List<Wire.Field> {
                val service = services.map { Wire.fields(it.bytes!!) }.first { it.first { f -> f.number == 1 }.value == channel }
                val sink = Wire.fields(service.first { it.number == 3 }.bytes!!)
                return Wire.fields(sink.first { it.number == 3 }.bytes!!)
            }
            assertEquals(listOf(48000L, depth.toLong(), 2L), config(4).map { it.value })
            assertEquals(listOf(16000L,16L,1L), config(5).map { it.value })
        }
    }
    @Test fun discoveryOffersTheNamedThirtyFpsMode() {
        val services = Wire.fields(Wire.body(Wire.discovery(1920,1080,220,48000,16))).filter { it.number == 1 }
        val video = services.map { Wire.fields(it.bytes!!) }.single { it.first { f -> f.number == 1 }.value == 3L }
        val display = Wire.fields(video.single { it.number == 3 }.bytes!!)
        val videoConfig = Wire.fields(display.single { it.number == 4 }.bytes!!)
        assertEquals(1L, videoConfig.single { it.number == 2 }.value) // VideoFPSEnum._30
        assertEquals(30, Wire.OFFERED_VIDEO_FPS)
    }
    @Test fun truncatedMessagesAreRejected() {
        for (input in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0x0a,5,1), byteArrayOf(0)))
            assertThrows(IllegalArgumentException::class.java) { Wire.fields(input) }
    }
    @Test fun touchIncludesZeroCoordinatesAndPointer() {
        val event = Wire.fields(Wire.body(Wire.touch(0,0,0,99)))
        assertEquals(99L, event.first { it.number == 1 }.value)
        val touch = Wire.fields(event.first { it.number == 3 }.bytes!!)
        assertEquals(0L,touch.first { it.number == 3 }.value)
        assertEquals(listOf(0L,0L,0L), Wire.fields(touch.first { it.number == 1 }.bytes!!).map { it.value })
    }
    @Test fun largeFieldCountIsBounded() {
        assertThrows(IllegalArgumentException::class.java) { Wire.fields(ByteArray(8194) { if (it % 2 == 0) 8 else 0 }) }
    }
}
