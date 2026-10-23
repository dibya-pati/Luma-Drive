# Audio evidence and UHD investigation

Keep four stages separate:

1. The music service's track/source quality and its Android output.
2. Android Auto's internal capture/mixing path.
3. The PCM published over the projection protocol.
4. The browser and car's output path, including any resampling or Bluetooth codec.

A music app reporting 24-bit/48 kHz at stage 1 does not prove stages 2–4 preserve
that precision. Browser AudioContext running at 48 kHz does not prove bit-perfect
speaker output either.

The public [AASDK AudioConfig schema](https://github.com/f1xpl/aasdk/blob/master/aasdk_proto/AudioConfigData.proto)
contains sample rate, bit depth and channel count. That establishes that these
fields can be offered, not that Android Auto implements every value. The receiver
sends these configurations in service discovery. It records numeric peer setup
and start messages, packet sizes and raw timestamp spans. Those replies may only
select a configuration index; they are not an independent precision certificate.

For stereo PCM, expected continuous payload throughput is:

| Offer | Bytes per second |
| --- | ---: |
| 48 kHz / 16-bit | 192,000 |
| 48 kHz / packed 24-bit | 288,000 |
| 96 kHz / packed 24-bit | 576,000 |

Timestamp units and any framing overhead must be established before using those
rates to infer format. Short wall-clock windows can be distorted by buffering.
Low-order bytes being nonzero is supporting evidence only: misinterpreted
16-bit data or dither can also produce nonzero bytes.

The bridge copies received PCM unchanged into its WebRTC data-channel packet.
CRC32 acknowledgments compare receiver bytes with browser bytes for each received
audio packet. The channel is unordered and partially reliable, so packets can be
dropped under congestion. Matching CRCs verify acknowledged packets across that hop; they do
not establish that every sent packet arrived,
cryptographic integrity, source precision, AudioContext output, or speaker output.
The baseline renderer converts signed PCM16 to Float32 for Web Audio. A tested
packed PCM24 decoder is present, but 24-bit probe sessions remain muted until
Android Auto's actual output is established.

## Validation sequence

- Start with 16-bit/48 kHz and a playing music app. Record numeric setup/start
  messages, byte count and timestamp deltas over a stable interval.
- Correlate the active Android Auto capture track with Android's audio diagnostics.
  Keep raw device logs private; publish only relevant sanitized numeric findings.
- Repeat with a 24-bit/48 kHz offer. Check rejection, silence, selected config,
  throughput, sample boundaries and capture format. Do not relabel PCM16 as PCM24.
- Test 24-bit/96 kHz only if the earlier test gives useful evidence.
- If packed 24-bit appears to work, use a controlled lossless test signal with
  meaningful low-order bits before claiming precision. Commercial music packets
  and a UI badge are insufficient for bit-perfect validation.
- Separately validate browser and final car output. No software flag upgrades a
  lossy Bluetooth path into verified UHD.

## Measured baseline

In a working Google Desktop Head Unit 2.0 session with Android Auto 17.8,
Android's active audio diagnostics showed:

| Stage | Active format |
| --- | --- |
| Amazon Music playback track | PCM16, 48 kHz, stereo |
| Android Auto media capture track | PCM16, 48 kHz, stereo |
| Remote-submix media route | PCM16, 48 kHz, stereo |

The reported remote-submix profiles exposed PCM16. This is a current-device
observation, not a claim about all Android devices or future AA configurations.
Android Auto's developer audio setting was **Prefer PCM**. The theoretical
stereo PCM payload rate for this format is 192,000 bytes/s (1.536 Mbit/s).
That is a calculation, not a measured wire bitrate.

These observations establish a 16-bit stage in this tested path. They do not
establish lossless preservation of a 24-bit source, and no source/output sample
comparison was performed. The conversion point is not isolated: the music app's
active output was already PCM16. A previous music-app UI badge on another output
route cannot override the active audio-track evidence.

The phone-side Luma Drive receiver subsequently reached working media setup.
The measured baseline published stereo PCM16 at 48 kHz, with approximately
192,015 payload bytes/s over a stable observation interval (expected 192,000;
short-window/timestamp measurement explains the small difference). The direct
Wi-Fi browser received non-silent audio through a 48 kHz AudioContext. More than
3,000 packet CRC32 acknowledgments matched with zero mismatches in a bench
session. This verifies the checked PCM packets over the receiver-to-browser hop,
not the fidelity of unobserved packets, AA's capture stage, browser mixer or car
speakers. The user confirmed audible laptop playback. The baseline is uncompressed
PCM; a lossy audio codec is not added by this WebRTC path. The data channel uses
DTLS encryption, but PCM is not encrypted by an application-layer codec.

24-bit AA negotiation and UHD output remain unverified. The native release UI
exposes only the tested PCM16/48 kHz playback setting. Lower-level 24-bit probe
code remains for investigation and deliberately mutes experimental playback.
The previous laptop/DHU bridge also showed a 16-bit stage, rather than evidence
of 24-bit preservation. No sample-by-sample lossless source comparison or actual
Tesla speaker measurement has been completed.

To reproduce a privacy-filtered active-track summary:

```sh
adb shell dumpsys media.audio_flinger | python3 tools/summarize_audio_route.py
```

The summarizer emits only active format facts for Android Auto and Amazon Music;
it omits process IDs, UIDs, addresses and unrelated applications. Raw dumps and
live screens can contain private data and must stay out of Git.

Current status: standard uncompressed PCM capture observed; end-to-end source
losslessness and UHD are unverified. A receiver-side 24-bit label cannot restore
precision already lost upstream.
