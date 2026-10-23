# Luma Drive 0.4.5

This release provides a phone-side Android Auto receiver with local browser
playback. The Android Auto projected interface is rendered by Android Auto; Luma
Drive does not mirror the phone screen.

## Included

- Local pairing and WebRTC browser display with touch and control input.
- Phone-side H.264 decode and WebRTC encode; 720p default, optional 1080p, and a
  shared 10–30 FPS ceiling (30 FPS default).
- PCM16, 48 kHz stereo audio over a WebRTC data channel.
- Warm decoder lifecycle while Android Auto remains active, browser presence
  handling, connection history, and playback diagnostics.
- Optional phone weather and hotspot controls.

The receiver has been bench-tested for startup, recovery, and locked-screen
operation. Tesla browser, hotspot, speaker, in-motion, and long-drive behavior
have not been validated.

## Requirements and setup

- Android 9 (API 28) or newer and Android Auto's developer head-unit server.
- A private local network shared by the phone and browser device. The app uses
  Android VPN permission for its local route; mDNS availability varies by
  network, with a numeric address available as fallback.
- Start the Android Auto head-unit server manually after a phone reboot. In
  Luma, tap **Start drive** and grant the VPN prompt, enable the phone hotspot,
  and open the address shown by Luma on the browser device.
- Choose **Android Auto** in the launcher and pair with the short-lived code
  shown by **Pair code** on the phone. The display starts automatically. Use
  **Controls → Enable audio** or **Full screen** if browser policy blocks it.

Local pairing and signaling use HTTP. WebRTC media and data use encrypted
transport. There is no cloud signaling or STUN/TURN service. Do not expose the
local service to the Internet.

## Build

Source builds require JDK 17, Android SDK platform/build tools 35, Node.js 18 or
newer, and Python 3. Initial Gradle dependencies may require network access.

```sh
./gradlew testDebugUnitTest assembleDebug
node --test tests/*.test.cjs
python3 tests/audio_route_test.py
python3 tools/audit_public.py
```

Release signing uses an external PKCS12 store (alias `projection`) supplied via
`PROJECTION_SIGNING_STORE` and `PROJECTION_SIGNING_PASSWORD`, followed by
`./gradlew testDebugUnitTest assembleRelease`. Do not commit signing material.

## Receiver identity and limitations

**The public source and APK do not contain an Android Auto receiver identity.
A fresh installation cannot authenticate until an accepted identity is
separately and lawfully provisioned.** The personal bench device uses a legacy
AASDK receiver identity stored in Android Keystore. It is a personal test
identity, not a production credential or an identity supplied for public use.
APK signing and Android Auto receiver authentication are separate.

Luma declines microphone capture and does not provide voice input. Android Auto
selects supported apps and layouts; arbitrary phone apps and forced pane layouts
are not provided. Phone-side video transcoding adds heat, battery use and
latency. Tesla compatibility and long-drive behavior remain unverified.

Luma Drive is GPL-3.0-or-later except for separately licensed components. See
[LICENSE](../LICENSE), [third-party notices](../THIRD_PARTY_NOTICES.md),
[authentication details](AUTHENTICATION.md), and the [quick start](QUICK_START.md).
