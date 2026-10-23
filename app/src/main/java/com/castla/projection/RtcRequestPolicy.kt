// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

/** Pure validation shared by the HTTP signaling routes. */
internal object RtcRequestPolicy {
    fun validClientId(value: String?): String? = value?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,80}")) }
    fun validSession(value: String?): String? = value?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,128}")) }
    fun sameOrigin(origin: String?, host: String?): Boolean = origin != null && host != null && origin == "http://$host"
    fun validBodyLength(length: Long?, maxBytes: Int): Boolean = length != null && length in 1..maxBytes.toLong()
    fun ownsSession(ownerClientId: String?, requestedClientId: String?): Boolean = ownerClientId != null && ownerClientId == requestedClientId
}
