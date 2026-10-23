// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection.credential

import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate

/** Optional local bench identity. Never read from assets, external storage, or release builds. */
internal object DevelopmentIdentity {
    fun loadIfPresent(file: File, debug: Boolean): HeadUnitCredential? {
        if (!debug || !file.exists()) return null
        require(file.isFile && file.length() in 1..65536) { "Invalid development identity file" }
        val bytes = file.inputStream().use { it.readBytesBounded() }
        // Public bench-container password, not a secret or a protection mechanism.
        // Android's PKCS12 provider rejects some encrypted empty-password archives.
        val password = "local-development-only".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            bytes.inputStream().use { load(it, password) }
        }
        val entry = store.getEntry("headunit", KeyStore.PasswordProtection(password)) as? KeyStore.PrivateKeyEntry
            ?: error("Development identity requires the headunit key entry")
        val chain = entry.certificateChain.map { it as X509Certificate }
        require(chain.isNotEmpty() && chain.size <= 8) { "Invalid development identity chain" }
        chain.forEach { it.checkValidity() }
        require(entry.privateKey.algorithm == "RSA") { "Development identity requires an RSA key" }
        val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(entry.privateKey); update(challenge); sign()
        }
        require(Signature.getInstance("SHA256withRSA").run {
            initVerify(chain.first()); update(challenge); verify(signature)
        }) { "Development identity certificate and key do not match" }
        val hash = MessageDigest.getInstance("SHA-256").digest(chain.first().encoded)
            .joinToString("") { "%02x".format(it) }
        return HeadUnitCredential(chain, entry.privateKey, HeadUnitCredentialSource.DEVELOPMENT_PKCS12, hash)
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer, 0, minOf(buffer.size, 65537 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= 65536) { "Invalid development identity file size" }
        }
        val bytes = output.toByteArray()
        require(bytes.size in 1..65536) { "Invalid development identity file size" }
        return bytes
    }
}
