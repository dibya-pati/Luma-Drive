// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import org.junit.Assert.*
import org.junit.Test

class PlaybackFaultTest {
    private fun fault(stage:String="createBuffer",kind:String="encoding",name:String="NotSupportedError",renderer:String="software",channel:Int=4,bits:Int=16,channels:Int=2,rate:Long=48000) =
        PlaybackFault.summarize(stage,kind,name,renderer,channel,bits,channels,rate,false)
    @Test fun retainsOnlyFixedLabelsAndFormatNumbers() {
        assertEquals("createBuffer:encoding:NotSupportedError:software:4/16/2/48000/0",fault())
    }
    @Test fun rejectsFreeTextUrlsTokensAndUnrecognizedLabels() {
        assertNull(fault(stage="https://private.invalid/?token=secret"))
        assertNull(fault(kind="account name"));assertNull(fault(name="song title"));assertNull(fault(renderer="location"))
    }
    @Test fun boundsEveryWireFormatField() {
        assertNull(fault(channel=256));assertNull(fault(bits=-2));assertNull(fault(channels=256));assertNull(fault(rate=4_294_967_296L))
        assertNotNull(fault(channel=255,bits=255,channels=255,rate=4_294_967_295L))
    }
    @Test fun capturesVideoFailuresWithoutPretendingTheyHaveAnAudioFormat() {
        assertEquals("decodeVideo:encoding:NotSupportedError:webcodecs:-1/-1/-1/-1/0",fault(stage="decodeVideo",renderer="webcodecs",channel=-1,bits=-1,channels=-1,rate=-1))
    }
    @Test fun acceptsWebRtcPlaybackAndSignalingFailuresWithBoundedLabels() {
        assertEquals("playVideo:unavailable:NotAllowedError:webrtc:-1/-1/-1/-1/0", fault(stage="playVideo",kind="unavailable",name="NotAllowedError",renderer="webrtc",channel=-1,bits=-1,channels=-1,rate=-1))
        assertEquals("signaling:timeout:Error:webrtc:-1/-1/-1/-1/0", fault(stage="signaling",kind="timeout",name="Error",renderer="webrtc",channel=-1,bits=-1,channels=-1,rate=-1))
        assertNull(fault(stage="playVideo",renderer="https://private.invalid"))
    }
}
