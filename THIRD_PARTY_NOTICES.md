# Third-party notices

This is a modified, independent receiver project. It does not include the
NavOnWeb application, its UI, billing, accounts, backend, or bundled credentials.

## Android Auto transport primitives and protocol tests

The following files were adapted from [kdm1362/NavOnWeb_OSS](https://github.com/kdm1362/NavOnWeb_OSS),
revision `6e161bf82a8d6c8fa5b0d68134ac3d2ee1b139cb`, under GPL-3.0-or-later:

- `protocol/AasdkFrameCodec.kt`
- `protocol/AasdkBootstrapCodec.kt`
- `protocol/AasdkTransport.kt`
- `protocol/AasdkTlsEngine.kt`
- `protocol/AasdkProjectionSession.kt`
- `credential/HeadUnitCredential.kt`
- `protocol/AasdkFrameCodecTest.kt` and `protocol/AasdkTlsEngineTest.kt` in the test source tree

Changes include package names, integration with separately provisioned Android Keystore
or user-selected KeyChain identities, TLS key matching in development,
protocol-version selection, connection-stage diagnostics and release trust guards. Existing SPDX
and provenance comments are retained. The original public source identifies
[EigenKodex](https://github.com/kdm1362) as its developer.

Those protocol primitives credit [f1xpl/aasdk](https://github.com/f1xpl/aasdk),
revision `046b3b381595509d0939fa84b14a90978f46ff63`, for frame/TLS layout.
The GPL text is included in `LICENSE`. Corresponding modified source is supplied
with this project.

## Android WebRTC runtime

The Android runtime uses `io.getstream:stream-webrtc-android:1.3.9`, published
by [GetStream](https://github.com/GetStream/webrtc-android). GetStream's wrapper
is licensed under Apache-2.0; the upstream license is included at
[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt). The precompiled AAR embeds
Google WebRTC native code under its BSD-style license, along with patches from
Sora Android SDK (Apache-2.0) and webrtc-sdk/webrtc (BSD-style). The AAR does
not bundle its license or third-party notice files. The WebRTC BSD license text
and patent grant are included at [`licenses/WebRTC-BSD-3-Clause.txt`](licenses/WebRTC-BSD-3-Clause.txt)
and [`licenses/WebRTC-PATENTS.txt`](licenses/WebRTC-PATENTS.txt). These notices
are based on the pinned [GetStream 1.3.9 source](https://github.com/GetStream/webrtc-android/tree/1.3.9),
whose [license notes](https://github.com/GetStream/webrtc-android/blob/1.3.9/README.md#license)
identify the embedded WebRTC, Sora and webrtc-sdk licenses. Google publishes a
[WebRTC third-party license index](https://webrtc.googlesource.com/src/+/refs/heads/main/LICENSE_THIRD_PARTY),
but the published AAR contains no corresponding notice file. This source tree
does not claim to have independently enumerated every license in that native
binary; distributors should preserve the upstream notices and review applicable
requirements for their release.
The application uses WebRTC for local peer connections, video encoding and
DTLS-SRTP/data-channel transport. Android Auto supplies H.264 input to the
phone decoder. WebRTC negotiates the browser-facing video codec with the peer;
VP8 was selected in the current tested browser session, so the application does
not claim that its browser-facing encoder is H.264-only. No Stream service or
account is used.

## Broadway H.264 canvas decoder (historical)

The former 0.3 browser decoder bundled Player.js, YUVCanvas.js, Decoder.js and
avc.wasm from
[mbebenita/Broadway](https://github.com/mbebenita/Broadway), revision
`b8f055f5ad709db3c5042cff5938237ac0e1b5d5`.
Its BSD-3-Clause license and AUTHORS were included beside the bundle. That
browser bundle was removed in the 0.4.x release line, which sends video through
WebRTC instead. The Android-origin AVC decoder code is Apache-2.0; that license
is included in licenses/Apache-2.0.txt.

## Build and runtime dependencies

Gradle wrapper 8.7, Android Gradle Plugin 8.5.2, Kotlin 1.9.22,
kotlinx.coroutines-core 1.7.3, NanoHTTPD/NanoWSD 2.3.1 and JUnit 4.13.2 are referenced
by the build. NanoHTTPD and NanoWSD are distributed under the BSD 3-Clause
license; the upstream 2.3.1 license text is included at
[`licenses/NanoHTTPD-BSD.txt`](licenses/NanoHTTPD-BSD.txt). Their other upstream
licenses and transitive dependency notices continue to apply. This file is not
a complete license inventory for every transitive artifact. Build caches and
downloaded SDKs are not part of this source repository.
The wrapper scripts carry their Apache-2.0 notices; a copy is in `licenses/Apache-2.0.txt`.

Android Auto, Google Maps, YouTube Music, Amazon Music and Tesla names refer to
their respective owners. Their apps, assets, credentials and media are not
redistributed with this source.
