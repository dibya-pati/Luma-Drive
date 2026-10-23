// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import org.junit.Assert.*
import org.junit.Test

class PairingSessionTest {
    @Test fun `code is six digits and can be redeemed only once`() {
        val pairing = PairingSession(); val code = pairing.issue()
        assertTrue(code.matches(Regex("[0-9]{6}")))
        assertTrue(pairing.redeem(code)); assertFalse(pairing.redeem(code))
    }
    @Test fun `code expires after two minutes`() {
        var now = 0L; val pairing = PairingSession { now }; val code = pairing.issue()
        now = 120_000; assertFalse(pairing.redeem(code))
    }
    @Test fun `five wrong attempts exhaust the code`() {
        val pairing = PairingSession(); val code = pairing.issue()
        repeat(5) { assertFalse(pairing.redeem("invalid")) }; assertFalse(pairing.redeem(code))
    }
    @Test fun `no code is valid before the phone starts pairing`() {
        assertFalse(PairingSession().redeem("123456"))
    }
    @Test fun `reissuing after exhaustion enables pairing again`() {
        val pairing = PairingSession(); pairing.issue(); repeat(5) { pairing.redeem("invalid") }
        val replacement = pairing.issue(); assertTrue(pairing.redeem(replacement))
    }
}
