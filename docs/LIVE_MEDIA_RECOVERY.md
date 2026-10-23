# Live WebRTC media (0.4.5)

The phone decodes Android Auto's incoming H.264 frames with Android MediaCodec,
then re-encodes them as a WebRTC video track for the browser. The encoder is
configured to maintain resolution when bandwidth limits video quality; a saved
frame-rate ceiling from 10 to 30 FPS controls the maximum outgoing rate. It
defaults to 30 FPS. Android Auto's source offer remains 30 FPS, and actual
playback can run below the selected cap.

The service prepares the WebRTC source and decoder before it starts the Android
Auto receiver. This lets the decoder see the initial SPS/PPS codec metadata and
IDR frame. Its input queue holds at most eight frames. If it falls behind, it
drops queued frames, waits for a new IDR and requests a video refresh. A refresh
sends Android Auto a focused-state notification only.

When the browser leaves, its WebRTC session closes and the phone clears queued
PCM audio. The phone keeps decoding the live Android Auto stream while that AA
session stays active, including across the 30-second browser-presence grace
period. A returning browser joins the current live stream; Luma does not cache or
replay a group of old H.264 frames. When the Android Auto session itself stops or
restarts, Luma resets the decoder and forgets its SPS/PPS so the new session can
provide fresh codec metadata and an IDR frame.

This path adds phone-side decode and encode work. It can increase CPU use, heat,
battery consumption and latency; those effects have not been measured during a
long drive. The current path has not been validated in a Tesla. The browser
plays video in a native video element.

Audio remains uncompressed PCM16 stereo at 48 kHz. The existing 20-byte media
header and PCM payload are sent unchanged over an unordered data channel with
partial reliability. A short network loss can drop PCM packets; CRC acknowledgments
are diagnostics and do not make delivery reliable. Browser controls use a separate
reliable data channel. WebRTC protects media and data with DTLS-SRTP/DTLS on the
local peer connection.

The browser and phone exchange an offer and answer using authenticated,
same-origin HTTP on port 9092. ICE uses local candidates without STUN or TURN.
Pairing and signaling HTTP are unencrypted, so use a private network and do not
expose the service to the Internet.
