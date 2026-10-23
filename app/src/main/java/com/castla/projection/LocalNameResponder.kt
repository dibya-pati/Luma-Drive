// SPDX-License-Identifier: GPL-3.0-or-later
package com.castla.projection

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Offline name on each Wi-Fi link; AP replies use the retained, car-reachable alias. */
internal class LocalNameResponder(private val context: Context) : AutoCloseable {
    companion object {
        @Volatile var hostname = "luma.local"; private set
        @Volatile var available = false; private set
        @Volatile var status = "Local name off"; private set
    }
    private val prefs = context.getSharedPreferences("local-name", Context.MODE_PRIVATE)
    private val suffix = prefs.getString("suffix", null) ?: UUID.randomUUID().toString().take(6).also { prefs.edit().putString("suffix", it).apply() }
    @Volatile private var live = false
    private var worker: Thread? = null
    private var lock: WifiManager.MulticastLock? = null
    private val channels = CopyOnWriteArrayList<Channel>()
    private data class Link(val iface: NetworkInterface, val address: Inet4Address, val answer: Inet4Address)
    fun start() {
        if (live) return
        hostname = prefs.getString("host", "luma.local") ?: "luma.local"
        live = true
        lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("LumaLocalName").apply { setReferenceCounted(false); acquire() }
        worker = Thread({
            var signature = ""
            while (live) {
                try {
                    val links = links()
                    val next = hostname + links.joinToString { "${it.iface.name}:${it.answer.hostAddress}" }
                    if (next != signature || (channels.isEmpty() && links.isNotEmpty()) || channels.any { it.socket.isClosed }) {
                        channels.forEach { it.close() }; channels.clear(); available = false
                        signature = next
                        links.take(4).forEach { link -> runCatching { Channel(link, hostname).also { channels.add(it); it.start() } } }
                        status = if (channels.isEmpty()) "No local network · use the IP address" else "Advertising $hostname"
                    }
                    Thread.sleep(3000)
                } catch (_: InterruptedException) { break }
                catch (_: Exception) { status = "Local name unavailable · use the IP address"; try { Thread.sleep(3000) } catch (_: InterruptedException) { break } }
            }
        }, "LumaLocalName").apply { isDaemon = true; start() }
    }
    private fun links(): List<Link> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifiClients = cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .mapNotNull { cm.getLinkProperties(it)?.interfaceName }.toSet()
        val alias = if (LocalAccessService.addressPresent()) InetAddress.getByName(LocalRouteSession.ADDRESS) as Inet4Address else null
        return NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress && !it.isLoopbackAddress }.map { ip ->
                Link(n, ip, if (n.name in wifiClients) ip else alias ?: ip)
            } }
    }
    private fun nameConflict() {
        val next = "luma-$suffix.local"
        if (hostname == next) { status = "Local name conflict · use the IP address"; return }
        hostname = next; prefs.edit().putString("host", next).apply(); available = false
        RecoveryJournal.record(context, "local-name-conflict-renamed")
    }
    private inner class Channel(val link: Link, val name: String) {
        val socket = MulticastSocket(null).apply {
            reuseAddress = true; bind(InetSocketAddress(5353)); networkInterface = link.iface; timeToLive = 255; soTimeout = 500
            joinGroup(InetSocketAddress("224.0.0.251", 5353), link.iface)
        }
        @Volatile private var active = true
        fun start() { Thread({
            try {
                var probes = 0; val readyAt = System.currentTimeMillis() + 800; var nextAnnouncement = readyAt; var announcements = 0; var nextProbe = 0L; var lastAnswer = 0L
                val buffer = ByteArray(1500)
                while (live && active && name == hostname) {
                    val now = System.currentTimeMillis()
                    if (probes < 3 && now >= nextProbe) { multicast(LocalDns.probe(name, link.answer.address)); probes++; nextProbe = now + 250 }
                    if (probes == 3 && now >= nextAnnouncement && announcements < 2) { multicast(LocalDns.answer(name, link.answer.address)); available = true; announcements++; nextAnnouncement = now + 1000 }
                    try {
                        val p = DatagramPacket(buffer, buffer.size); socket.receive(p)
                        val message = runCatching { LocalDns.parse(p.data.copyOfRange(p.offset, p.offset + p.length)) }.getOrNull() ?: continue
                        if (LocalDns.conflict(message, name, link.answer.address) && NetworkInterface.getByInetAddress(p.address) == null) { runCatching { multicast(LocalDns.answer(name, link.answer.address, ttl = 0)) }; nameConflict(); break }
                        if (now >= readyAt && LocalDns.requested(message, name) && now - lastAnswer >= 100) {
                            lastAnswer = now
                            val question = message.questions.first { it.name == name && it.type in listOf(1,255) }
                            val legacy = p.port != 5353
                            val answer = LocalDns.answer(name, link.answer.address, id = if (legacy) message.id else 0, legacy = if (legacy) question else null)
                            if (legacy || question.clazz and 0x8000 != 0) socket.send(DatagramPacket(answer, answer.size, p.address, p.port)) else multicast(answer)
                        }
                    } catch (_: SocketTimeoutException) {}
                }
            } catch (_: Exception) { /* Interface changes are retried by the coordinator. */ }
            finally { socket.close() }
        }, "LumaMdns-${link.iface.name}").apply { isDaemon = true; start() } }
        private fun multicast(bytes: ByteArray) { socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("224.0.0.251"), 5353)) }
        fun close() {
            active = false
            SocketCleanup.run { try { if (!socket.isClosed) multicast(LocalDns.answer(name, link.answer.address, ttl = 0)) } finally { socket.close() } }
        }
    }
    override fun close() {
        live = false; available = false; status = "Local name off"; worker?.interrupt(); worker = null
        channels.forEach { it.close() }; channels.clear()
        lock?.let { if (it.isHeld) it.release() }; lock = null
    }
}
