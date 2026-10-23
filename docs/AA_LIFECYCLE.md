# Browser presence and Android Auto lifecycle

The launcher and AA viewer send authenticated HTTP presence heartbeats every
five seconds with a random identifier for that browser document. The AA viewer
uses WebRTC video plus separate control and PCM data channels. Page departure
closes its WebRTC session and reports that the browser left. Each browser page
counts as a client; this is not a census of devices connected to the hotspot.
Client identifiers are not written to preferences or persistent logs; the phone
records only aggregate active-client counts.

An explicit page departure starts a 30-second grace period. If a browser
vanishes without sending the departure event, the grace period is measured
from its last heartbeat. The policy rejects stale clients after 15 seconds for
counting purposes, but that detection interval does not add to the grace.
Other active clients or a return within the grace period prevent teardown.

The service prepares the WebRTC decoder off the main thread before starting the
Android Auto receiver. This lets it receive the AA stream's initial SPS/PPS
codec metadata and IDR frame even if no browser is connected yet. When a browser
leaves, Luma closes its peer and clears queued PCM audio but keeps decoding the
live stream while the same AA session remains active. A returning browser joins
the current stream without replaying a cached H.264 GOP. Video refresh sends AA
a focused-state notification only. Actual AA session stop or restart resets the
H.264 decoder and its codec metadata before the new stream begins.

When the grace expires with no other clients, the receiver sends AA's protocol
shutdown request (control channel, message 0x000f, QUIT reason), waits briefly
for the shutdown response, then closes the session and stops retrying. This
follows session disconnect semantics: it does not force-stop Maps or media
applications, clear their state, or issue extra pause/navigation commands.
Their normal AA disconnect behavior remains under Android Auto and the
individual app.

The local web server, VPN route and AA developer head-unit listener remain
available during an idle disconnect. A paired browser returning after AA has
been disconnected starts a new AA session automatically. The AA head-unit
listener itself remains running until Android Auto stops it or the phone
restarts.

## Starting the Android Auto head-unit server

Google's [DHU guide](https://developer.android.com/training/cars/testing/dhu)
documents starting this listener through Android Auto's overflow menu. It does
not publish a companion-app API for starting it. Inspection of the reference
AA package confirmed its developer head-unit network service and developer
settings activity are not exported. Ordinary apps cannot start those private
components; see [Android component access](https://developer.android.com/guide/components/intents-filters).

Luma can open Android Auto setup and reconnect to an already running listener.
It deliberately leaves that listener running during idle disconnects, so the
next browser session needs no manual restart. A phone restart or Android Auto
stopping its listener can still require the user to start it through AA setup.
This route needs no runtime ADB, root or accessibility automation. Inspecting
or changing hidden components under a privileged shell would not establish
that an ordinary installed app can perform the same operation.
