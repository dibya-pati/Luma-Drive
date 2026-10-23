/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.castla.projection.credential

import java.security.PrivateKey
import java.security.cert.X509Certificate

enum class HeadUnitCredentialSource {
    DEVELOPMENT_PKCS12,
    ANDROID_KEYSTORE,
    ANDROID_KEYCHAIN,
}

class HeadUnitCredential internal constructor(
    certificateChain: List<X509Certificate>,
    val privateKey: PrivateKey,
    val source: HeadUnitCredentialSource,
    val leafFingerprintSha256: String,
) {
    val certificateChain: List<X509Certificate> = certificateChain.toList()

    override fun toString(): String =
        "HeadUnitCredential(source=$source, certificateCount=${certificateChain.size}, privateKey=<redacted>)"
}
