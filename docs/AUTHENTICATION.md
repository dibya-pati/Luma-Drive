# Android Auto authentication

## Working personal installation

The receiver authenticated to Android Auto 17.8 using protocol 1.7/TLS 1.2 and
streamed genuine 1920×1080 projection with input and PCM audio. The signed,
non-debuggable personal release also authenticated using a separately imported,
non-exportable Android Keystore key. This establishes this configured device's
working path, not public production credential provisioning.

An earlier post-authentication disconnect was caused by an incomplete service
offer: AA reported “No audio/mic.” Adding the microphone input service descriptor
and setup replies allowed projection. Capture requests are explicitly declined;
there is no hidden microphone recording or fabricated audio.

The separately approved personal test identity is the legacy JVC Kenwood identity
published in [AASDK's Cryptor.cpp](https://github.com/f1xpl/aasdk/blob/046b3b381595509d0939fa84b14a90978f46ff63/src/Messenger/Cryptor.cpp).
Its private key is **not in the repository or APK**. The configured phone retains
it in Android Keystore for the expressly approved personal testing/use. The
temporary import container was removed. This does not grant vendor production
rights or make that identity suitable for public distribution.

## Certificate trust

A self-signed receiver certificate failed AA's client validation with communication
error 7 / trust anchor not found. Making our own trust manager permissive cannot
fix the peer's client-certificate validation. No self-signed fallback is generated
by the current app.

Read-only inspection of this AA version found an isolated trust store populated
with the Google Automotive Link CA. That is an implementation observation, not a
published universal protocol guarantee. Our receiver separately checks its peer
against the pinned CA fingerprint and performs normal chain validation; it does
not install system CAs or patch Android Auto.

## Provisioning choices

1. A user-selected RSA identity through **Settings → Client certificate** uses
   Android KeyChain. The issuer must actually be accepted by AA. This specific
   selection path has not been authenticated live.
2. A separately provisioned non-exportable Android Keystore identity is used by
   the validated personal release. It is private to the configured installation.
3. Debug builds alone can import a bounded (64 KiB) private PKCS12 bench file,
   alias `headunit`, public container password `local-development-only`. The
   loader checks certificate validity and key agreement. The debug protection
   action imports into Keystore and deletes the temporary file. Release builds
   ignore the file and reject development credential sources.

A malformed selected/provisioned identity fails explicitly. No accepted key,
credential download, issuing service or automatic enrollment is included.
Uninstalling the configured app or clearing data destroys its private identity.
The public APK installed on another phone will require separate provisioning.

Google's [DHU instructions](https://developer.android.com/training/cars/testing/dhu)
document test-server startup but provide no self-service receiver certificate
issuance process. An authorized identity/provisioning arrangement remains a gate
for publicly distributing an operational receiver product.
