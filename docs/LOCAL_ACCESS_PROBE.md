# Local access and connection test in 0.3

The retained-interface route is now controlled from the main screen. Connect VPN
starts it, Repair VPN rebuilds a missing or unhealthy interface, Disconnect VPN
stops it, and Android's VPN disconnect is respected. Start drive includes the
route; Stop drive stops all Luma components. Another VPN may be replaced after
Android's consent prompt. No default route or remote VPN server is installed.

This technique retains an inactive TUN interface, which Android does not promise
to preserve. Presence of its address does not prove car reachability. Interface
health is checked and a lost address is rebuilt while a connection is requested.
Android-settings disconnect does not automatically reconnect behind the user.

The public echo listener on port 9093 is off until Start connection test is
pressed. Stop connection test detaches it immediately and closes sockets off
the main thread. The webpage also has Stop checking, which stops its own
heartbeat. Neither stopping the browser check nor closing a dialog is presented
as stopping the VPN. No test page contains account information, media or tokens.

Offline local naming is implemented by LocalDns/LocalNameResponder. Only the
app's own .local IPv4 name is answered. It does not forward or intercept DNS.
On a connected Wi-Fi client interface it advertises the local interface address;
on a hotspot interface it advertises the retained alias when available. It
handles name probing, conflicts, interface changes and goodbye announcements.
Actual Tesla name resolution and offline hotspot restart remain device tests.

See QUICK_START.md for startup, stop and fallback instructions.
