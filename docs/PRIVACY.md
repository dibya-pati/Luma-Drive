# Privacy and development data

Luma Drive has no login, analytics SDK, advertising SDK, cloud backend, contacts,
microphone, accessibility or screen-capture permission. Approximate location is
optional for weather, as described below. Android Auto is
a separate application with its own permissions and data practices. Its projected
UI/audio can contain private account content, names, locations and media. Luma
does not redact that live content.

The app generates a random browser access token in private preferences. A
phone-issued six-digit pairing code is short-lived, single-use and rate-limited
to five attempts per issued code. It exchanges for the full token. The browser
stores that token in localStorage so pairing survives reloads; private browsing,
cleared storage or a changed origin can require pairing again. Copy browser link
copies a plain address. Private token-fragment links are still accepted; the page
removes the token from the visible URL. Do not share private token links or include
browser storage/network diagnostics in issues.

Connected pages send authenticated presence heartbeats every five seconds and
a best-effort leave event. Each document uses a random temporary identifier,
kept only in memory and discarded when it closes. Luma uses these to count
active pages and disconnect the AA session after 30 seconds without clients;
they are not advertising identifiers or a list of devices on the hotspot.

Client identities are provisioned separately. A user-selected alias stays in
private preferences, with signing performed through Android KeyChain. The
configured personal installation uses a non-exportable Android Keystore key;
its temporary import file was removed after import. Uninstalling/clearing app
data loses private credentials and pairing. Backups are disabled. No accepted
identity is supplied by the source or APK and no credential is auto-downloaded.

Debug builds support an optional private `files/development/identity.p12` bench
file. It contains a private key, must be supplied separately and must never be
published. Release builds ignore it. The fixed development container password
is public; file isolation, not that password, is its protection. The debug-only
protection action imports an approved identity into Android Keystore and removes
the temporary container.

The receiver and WebRTC transport keep bounded media queues in memory, not song
recordings. Audio diagnostics contain numeric format/count/timing/checksum facts.
They do not save song payloads or identify a track. SDP and ICE candidates are
processed in memory for local signaling and are not written to logs. Protocol
metadata omits application payloads.
Device dumps, logs, screenshots and browser captures may still contain personal
data and must stay outside the public repository.

Paired browsers automatically report playback failures using fixed stage/error
categories and bounded numeric PCM format fields. The phone retains the last
eight reports locally, with the time and app version, separately from the normal
connection history so a later reconnect does not immediately erase the evidence.
These reports exclude error-message free text, URLs, tokens, media, map content
and browser user agents. They are available under Connection history and the
authenticated local status API. The browser's own diagnostics additionally show
the native error text and user agent in memory; these are not uploaded to the
phone's persistent report history.

The connection history also keeps up to 96 recent lifecycle events, including
the app version and device uptime. The authenticated status response includes
WebRTC session, frame, decoder and dropped-PCM counters plus a bounded network
summary (network capability booleans and interface counts). These diagnostics
exclude browser IDs, IP and interface addresses, interface names, SSIDs, MAC
addresses, DNS servers, page URLs, tokens, headers, user agents, SDP, ICE
candidates and raw exception messages. The event history stays in local app
storage and is available in the phone UI and authenticated local status API;
live counters are available only through that status API.

Static pages are served locally. Status, offer/answer signaling and control
require a browser access token, with same-origin checks on signaling. Pairing
and signaling HTTP are **unencrypted** on the LAN. WebRTC encrypts video with
DTLS-SRTP and data channels with DTLS; PCM remains uncompressed. There is no
STUN/TURN relay or cloud signaling service. Possession of the token plus network
access permits pairing/signaling and may allow viewing/control. Use a private
network, do not port-forward the service, and stop projection when finished.

The public source must exclude signing stores, client keys, tokens, private
addresses, device identifiers, personal Git identity, SDK paths, raw diagnostics,
captures and generated builds. APKs are distributed separately with matching
source. Third-party credit and license information must remain intact.

## Optional local weather

Weather is off until enabled in the phone app. It requests Android's approximate
location permission and adds the location foreground-service type while a drive
session is running. It does not request fine or background location permission.
The phone sends coordinates rounded to two decimal places to Open-Meteo over
HTTPS to retrieve a forecast. Android may already return a coarser location.
Open-Meteo also receives the phone's public network address. Forecast requests
are normally spaced at least 30 minutes apart, with a new request after a
changed location; failed requests back off for five minutes.

Coordinates and place names are held only in memory and expire when the phone
cannot provide a fresh fix. Legacy saved coordinates are removed on upgrade.
Android's geocoding provider resolves the approximate coordinates to a locality
and region; its availability and network behavior depend on the phone's provider.
The forecast is kept in private app preferences. Only paired local browsers can
read the weather endpoint. It returns the current place name, weather, phone
theme, time zone, update times and availability, without latitude/longitude.
Turning weather off removes the saved forecast and stops location updates.
Stopping Luma stops location updates and weather work; saved weather can resume
with the next drive. Old or offline forecasts are explicitly marked as saved.

Weather data: https://open-meteo.com/ (CC BY 4.0 attribution). The free endpoint
is intended for non-commercial use; review their terms before commercial distribution.
