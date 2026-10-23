// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection.credential

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DevelopmentIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `release never reads even an invalid local identity`() {
        val file = temporary.newFile().apply { writeText("invalid credential") }
        assertNull(DevelopmentIdentity.loadIfPresent(file, false))
    }

    @Test fun `missing optional identity uses normal provider`() {
        assertNull(DevelopmentIdentity.loadIfPresent(File(temporary.root, "absent.p12"), true))
    }

    @Test fun `empty oversized and directory inputs fail without fallback`() {
        for (file in listOf(temporary.newFile(), temporary.newFile().apply { writeBytes(ByteArray(65537)) }, temporary.newFolder())) {
            assertThrows(IllegalArgumentException::class.java) { DevelopmentIdentity.loadIfPresent(file, true) }
        }
    }

    @Test fun `malformed identity fails without generating a replacement`() {
        val file = temporary.newFile().apply { writeText("invalid credential") }
        assertThrows(Exception::class.java) { DevelopmentIdentity.loadIfPresent(file, true) }
        assertEquals("invalid credential", file.readText())
    }
}
