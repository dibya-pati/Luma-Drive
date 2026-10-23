// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

/** Only fixed labels and bounded format numbers may enter persistent diagnostics. */
internal object PlaybackFault {
    private val stages = setOf("createContext", "resumeContext", "decodePcm", "createBuffer", "copySamples", "scheduleBuffer", "configureVideo", "decodeVideo", "renderVideo", "softwareVideo", "watchdog", "playVideo", "signaling")
    private val kinds = setOf("encoding", "format", "permission", "unsupported", "unavailable", "timeout", "other")
    private val names = setOf("Error", "NotSupportedError", "TypeError", "RangeError", "InvalidStateError", "NotAllowedError", "SecurityError", "AbortError", "OperationError", "unknown")
    private val renderers = setOf("none", "software", "webcodecs", "webrtc")
    fun summarize(stage: String, kind: String, name: String, renderer: String, channel: Int, bits: Int, channels: Int, rate: Long, secure: Boolean): String? {
        if (stage !in stages || kind !in kinds || name !in names || renderer !in renderers) return null
        if (channel !in -1..255 || bits !in -1..255 || channels !in -1..255 || rate !in -1..4_294_967_295L) return null
        return "$stage:$kind:$name:$renderer:$channel/$bits/$channels/$rate/${if(secure)1 else 0}"
    }
}
