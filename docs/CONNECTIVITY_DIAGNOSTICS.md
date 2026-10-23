# Connectivity diagnostics

The 0.4.5 status endpoint includes a bounded local history for browser and
Android Auto connection lifecycle events. Each new event includes the app
version and device uptime; the phone retains at most the latest 96 events.
Existing entries remain readable and roll out as new events arrive.

Presence history records active client count changes and AA idle disconnect/resume
decisions. The browser display uses authenticated WebRTC offer/answer signaling.
The WebRTC transport section reports active session count, the selected AA
input frame-rate ceiling, AA video packets offered to the decoder
(`videoInputFrames`), frames forwarded by the frame-rate gate, frame-rate drops,
PCM packets sent or dropped, sender-policy application failures, and phone
decoder counters (`decodedFrames`, `decoderDrops`, `decoderQueue`, plus
frame-rate-gate counters). The browser transport summary also reports PCM
checksum matches/mismatches and whether video transcoding is active. These are
phone-side counters; they do not report encoded WebRTC video frame totals. SDP,
ICE candidates, candidate addresses, credentials and control messages are not
logged.

The status response includes a compact network summary: default network
transport and capability flags, interface counts, whether the compatibility
address is present, and supported Wi-Fi tethering state. A registered Android
callback passively tracks the tether upstream's transport and capability
changes separately from the default network; it does not send probes or infer
reachability. The response does not expose addresses, interface names, SSIDs,
MAC addresses, DNS servers, or tether client identities. Capability
availability is reported separately from capability values. Android's
tethering callback is available only on API 36 and may be
unavailable on an OEM build because of restricted system access; private DUN
upstream capabilities can also be withheld. In those cases the relevant value
is unknown and a fixed availability reason is reported. Luma does not infer
hotspot health from missing capability data.

The browser retries failed peer connections and closes the bound server session
when the page leaves. The existing 30-second presence grace tolerates reloads
and brief Wi-Fi interruptions; it does not repair a phone hotspot or restart
Android Auto's head-unit server.

Lifecycle history and playback reports are local to the phone. The status API
requires the paired browser token and same-origin request checks. Do not share
raw status exports outside a trusted support conversation; they can reveal
connection timing and local network capabilities even though they omit personal
identifiers and addresses.

## WebRTC media transport (0.4.5)

The phone decodes Android Auto's H.264 input and supplies frames to WebRTC for
browser-facing video encoding. The output codec is negotiated with the browser;
VP8 was selected in the current tested browser session. The phone's decoder
queue holds at most eight AA frames. On overload it drops queued frames,
requests an IDR and resumes from a fresh frame. The AA input ceiling defaults
to 30 FPS and can use the saved 15 FPS setting. PCM16 stereo remains unchanged
and travels over a partially reliable data channel. This can produce brief gaps
and adds phone processing load. Browser controls use a reliable data channel.
Pairing and SDP signaling use local HTTP; media/data use DTLS-SRTP/DTLS. The AA
offer remains at 30 FPS. Local diagnostics report input/gate/decoder counters,
not browser encoder output totals. These implementation details are not a
certification or long-duration compatibility claim for every head unit.
