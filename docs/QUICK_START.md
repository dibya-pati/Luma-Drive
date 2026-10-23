# Luma Drive 0.4.5: quick start

## One-time setup

1. Enable Android Auto developer mode in its own settings, then select
   Start head unit server from the main settings overflow menu. This is separate
   from Android USB/wireless debugging. Repeat server startup after a reboot.
2. In Luma, tap Start drive and review Android's VPN permission prompt.
3. Enable the phone hotspot and connect the Tesla to it. In the Tesla's saved
   hotspot settings, enable Remain Connected in Drive.
4. Open the complete browser address shown in Luma. Try the local name first;
   use the numeric fallback if the Tesla cannot resolve the local name.
5. Choose Android Auto on the Luma launcher; its display starts automatically.
   Tap Pair code in Luma on the phone and enter it in the browser. A new
   hostname/origin needs its own pairing. If browser policy blocks playback or
   fullscreen, open Controls and tap Enable audio or Full screen.

## Starting and stopping

With the hotspot and AA server ready, tap Start drive. It starts Luma's VPN route,
AA receiver and local hostname. Stop drive or Stop all Luma services stops all
Luma-owned components, including the echo test. A hotspot started automatically
by Luma is also requested to stop; a manually started hotspot is left alone.
Google's AA server is a separate system component.

On the launcher, the **Frame rate** slider sets the maximum frame rate for the
live display on the phone. It is shared by paired browsers and starts at 30 FPS.
Android Auto's source offer stays at 30 FPS, and playback can run below the cap.
Lower settings can reduce network and decoding load.

VPN Connect/Repair and Disconnect are always visible on Luma's main screen.
After disconnecting from Android's VPN settings, open Luma and tap Connect VPN
or Start drive; review a renewed permission prompt if Android requests one.
Do not enable Android's Block connections without VPN for this local route.
Always-on VPN is intentionally unsupported.

The connection test is off by default. Start connection test opens the echo
listener. Stop connection test shuts it down without disconnecting the VPN.
Stop checking on the test webpage stops only that browser's heartbeat.

## Optional automatic hotspot

In Settings & diagnostics, enable Start hotspot automatically. On supported
Android 16+ phones, the next Start drive can request Android's system-settings
permission and then start tethering through the public API. Carrier restrictions
or OS policy may still require manual hotspot settings. No root or ADB is used.
This setting is off by default, so a home-Wi-Fi test does not change networks.

## Display and recovery

The browser address opens Luma's app launcher. Android Auto opens at /aa;
an existing bookmark to /aa opens it directly. The launcher starts no projection
stream or audio. In Android Auto, open Controls → Luma to return to the launcher.
Returning closes this browser's display and audio; the phone receiver stays running.
The additional-app space is a placeholder, with no other providers implemented.

720p is the default; choose 1080p for a sharper display if the car keeps up.
The phone decodes and re-encodes the AA video for WebRTC, and the browser plays
it in a native video element. This adds phone processing, heat, battery use and
latency. Actual Drive/Reverse behavior must be checked on the target Tesla before
relying on it. Entertainment video lockouts are separate from this AA navigation display.

Luma retries recoverable AA connection failures. If Google's head-unit server
is stopped, open Android Auto setup and start it; Luma will retry. Certificate
errors require attention in Settings. The browser exposes a visible recovery
message and Reconnect display button rather than hiding errors behind old frames.
Connection history records lifecycle events and exception classes, not media,
account information, device addresses or credentials.
The phone keeps the last eight browser playback-failure reports. Audio failures
leave video and controls available; retry from Controls → Enable audio. After a
browser-only failure, review Settings & diagnostics → Connection history on
the phone for the stage, error category and format.

## Offline name

The app advertises luma.local using IPv4 multicast DNS. Name conflicts cause a
stable suffixed name to be displayed. No account, paid domain, internet DNS or
cloud server is required for that lookup. Both the hotspot's multicast delivery
and the Tesla's resolver must support mDNS; use the numeric fallback otherwise.
The retained-interface route remains dependent on Android's lifecycle behavior.
The initial browser pairing can also happen offline while the service is running.

No runtime ADB connection is needed. Debugging may be used temporarily to install
an update, then disabled before testing. Do not uninstall the existing personal
app: an uninstall removes its separately provisioned client identity.

## Weather dashboard

The launcher shows the time/date, Android Auto and local weather. Pair from
Connect phone on the launcher, or choose Android Auto and pair there when asked.
Pairing now uses the whole display with a responsive landscape layout.

On the phone, tap Enable phone weather and allow approximate location. The phone
fetches the forecast, so the Tesla needs only its local connection to Luma.
Current conditions, a short forecast summary, the next hours, wind and humidity
appear on the launcher. Tap the temperature-unit button to switch °C/°F.
Fresh forecasts require the phone's internet connection; saved forecasts show
an update time and stale state. No forecast or location is invented when either
is unavailable. Turn weather off in the phone app to stop updates and remove
its saved forecast. Location and place names are not persisted. Start a drive from the visible phone app to
resume location after an OS restart or permission change.

The launcher follows the phone's system light/dark theme. Its location heading
shows the current city/area and region when a fresh approximate fix and place
name are available. It checks the phone every five seconds and removes the name
if the phone disconnects or the location becomes stale. Android controls how
often approximate location fixes are supplied; unavailable data is labeled.

## Browser presence grace period

Luma counts paired browser pages on both the launcher and AA display. After the
last page leaves, it waits 30 seconds before ending the Android Auto session.
A brief reload or Wi-Fi interruption inside that window does not end AA. If a
browser disappears without a close event, the timer runs from its last heartbeat.
Opening a paired Luma page again reconnects AA automatically. Counts refer to
browser pages, not every device joined to the hotspot.

The idle action sends AA's normal session shutdown request and closes its
transport. It does not force-stop Maps/music, erase navigation, or add separate
media commands. The phone and media app decide their normal disconnect behavior.
The page server, local VPN and Android Auto head-unit server remain available.

After a phone restart, Android Auto may require its head-unit server to be
started manually again: Android Auto setup → overflow → Start head unit server.
Luma cannot invoke that private service through a supported public Android API;
Start drive reconnects when the server is available.

## Media transport

Browser video, PCM and controls now use a local WebRTC peer connection. HTTP
pairing and offer/answer signaling remain on the local network; ICE uses no
STUN/TURN server. Video is decoded and encoded on the phone before browser
playback. Audio stays uncompressed PCM16 stereo at 48 kHz over an unordered,
partially reliable data channel, and controls use a reliable data channel.
Short media gaps can occur during recovery. The current release has been
bench-tested; Tesla validation remains pending.
