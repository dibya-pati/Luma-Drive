// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection.credential

import android.content.Context
import android.security.KeyChain
import android.security.keystore.KeyProtection
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Separately provisioned client identities; no keys are shipped in the APK. */
internal object LocalIdentity {
    private const val IMPORTED_ALIAS = "projection-imported-client-v1"
    fun load(context: Context): HeadUnitCredential {
        val selected = context.getSharedPreferences("identity", Context.MODE_PRIVATE).getString("keyChainAlias", null)
        if (selected != null) {
            val key = KeyChain.getPrivateKey(context, selected) ?: error("Selected identity key is unavailable")
            val chain = KeyChain.getCertificateChain(context, selected)?.toList().orEmpty()
            require(chain.isNotEmpty()) { "Selected identity certificate chain is unavailable" }
            chain.forEach { it.checkValidity() }
            require(key.algorithm.equals("RSA", ignoreCase = true)) { "An RSA client identity is required" }
            val hash = MessageDigest.getInstance("SHA-256").digest(chain.first().encoded).joinToString("") { "%02x".format(it) }
            return HeadUnitCredential(chain, key, HeadUnitCredentialSource.ANDROID_KEYCHAIN, hash)
        }

        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(IMPORTED_ALIAS)) {
            val entry = store.getEntry(IMPORTED_ALIAS, null) as KeyStore.PrivateKeyEntry
            val chain = entry.certificateChain.map { it as X509Certificate }
            chain.forEach { it.checkValidity() }
            val hash = MessageDigest.getInstance("SHA-256").digest(chain.first().encoded).joinToString("") { "%02x".format(it) }
            return HeadUnitCredential(chain, entry.privateKey, HeadUnitCredentialSource.ANDROID_KEYSTORE, hash)
        }
        DevelopmentIdentity.loadIfPresent(java.io.File(context.filesDir, "development/identity.p12"),
            com.castla.projection.BuildConfig.DEBUG)?.let { return it }
        error("No client identity installed. Choose an accepted client certificate first.")
    }

    /** Move an explicitly supplied personal identity into non-exportable app-scoped key storage. */
    fun protectPersonalIdentity(context: Context) {
        check(com.castla.projection.BuildConfig.DEBUG)
        val file = java.io.File(context.filesDir, "development/identity.p12")
        val identity = DevelopmentIdentity.loadIfPresent(file, true) ?: error("No external identity to protect")
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            // Conscrypt applies TLS RSA-PSS padding before its raw RSA Keystore upcall.
            // https://github.com/google/conscrypt/issues/1201
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS).build()
        store.setEntry(IMPORTED_ALIAS, KeyStore.PrivateKeyEntry(identity.privateKey, identity.certificateChain.toTypedArray()), protection)
        val entry = store.getEntry(IMPORTED_ALIAS, null) as KeyStore.PrivateKeyEntry
        check(entry.privateKey.encoded == null) { "Imported identity key must be non-exportable" }
        getSelectedPreferences(context).edit().remove("keyChainAlias").apply()
        check(file.delete()) { "Protected identity imported, but the temporary container could not be removed" }
        // Retire only obsolete keys belonging to this application's old development build.
        listOf("projection-local-development-tls-v2", "projection-local-development").forEach {
            if (store.containsAlias(it)) store.deleteEntry(it)
        }
    }

    private fun getSelectedPreferences(context: Context) = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
}
