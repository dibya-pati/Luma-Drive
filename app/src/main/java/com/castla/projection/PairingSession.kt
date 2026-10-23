// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import java.security.MessageDigest
import java.security.SecureRandom

/** A phone-issued, single-use pairing code. The browser subsequently uses the full random token. */
internal class PairingSession(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var code: ByteArray? = null
    private var expiresAt = 0L
    private var attempts = 0
    @Synchronized fun issue(): String {
        code?.fill(0)
        val value = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        code = value.toByteArray(Charsets.US_ASCII)
        expiresAt = clock() + 120_000
        attempts = 5
        return value
    }
    @Synchronized fun redeem(value: String): Boolean {
        val expected = code ?: return false
        if (clock() >= expiresAt || attempts <= 0) { expected.fill(0); code = null; return false }
        attempts--
        val valid = value.matches(Regex("[0-9]{6}")) && MessageDigest.isEqual(expected, value.toByteArray(Charsets.US_ASCII))
        if (valid || attempts == 0) { expected.fill(0); code = null }
        return valid
    }
}
