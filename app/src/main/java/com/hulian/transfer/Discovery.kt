package com.hulian.transfer

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** 同一 Wi-Fi 下用 mDNS(NSD) 互相发现 */
class Discovery(ctx: Context) {
    private val beacon = Beacon(ctx.applicationContext)
    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var reg: NsdManager.RegistrationListener? = null
    private var disc: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val names = HashMap<String, String>()
    private val type = "_hulian._tcp."
    private val myService = "hl-" + Store.deviceId

    @Synchronized
    fun start() {
        val info = NsdServiceInfo().apply {
            serviceName = myService
            serviceType = type
            port = Hub.port
            setAttribute("id", Store.deviceId)
            setAttribute("name", Store.deviceName)
        }
        val r = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
        }
        reg = r
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, r) } catch (_: Exception) {}

        val d = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, code: Int) {}
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                if (s.serviceName.startsWith("hl-") && s.serviceName != myService) enqueue(s)
            }
            override fun onServiceLost(s: NsdServiceInfo) {
                synchronized(names) { names.remove(s.serviceName) }?.let { Hub.setOffline(it) }
            }
        }
        disc = d
        try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, d) } catch (_: Exception) {}
        beacon.start()
    }

    @Synchronized
    fun stop() {
        beacon.stop()
        reg?.let { try { nsd.unregisterService(it) } catch (_: Exception) {} }
        disc?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        reg = null
        disc = null
        synchronized(queue) { queue.clear(); resolving = false }
    }

    fun restart() {
        stop()
        start()
    }

    private fun enqueue(s: NsdServiceInfo) {
        synchronized(queue) { queue.addLast(s) }
        next()
    }

    @Suppress("DEPRECATION")
    private fun next() {
        val s: NsdServiceInfo
        synchronized(queue) {
            if (resolving) return
            s = queue.removeFirstOrNull() ?: return
            resolving = true
        }
        try {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, code: Int) = done()
                override fun onServiceResolved(i: NsdServiceInfo) {
                    try { handle(i) } catch (_: Exception) {}
                    done()
                }
            })
        } catch (e: Exception) {
            done()
        }
    }

    private fun done() {
        synchronized(queue) { resolving = false }
        next()
    }

    @Suppress("DEPRECATION")
    private fun handle(si: NsdServiceInfo) {
        val id = si.attributes["id"]?.let { String(it, Charsets.UTF_8) } ?: return
        if (id == Store.deviceId) return
        val name = si.attributes["name"]?.let { String(it, Charsets.UTF_8) } ?: "未知设备"
        val host = si.host?.hostAddress ?: return
        synchronized(names) { names[si.serviceName] = id }
        Hub.upsertPeer(id, name, host, si.port)
    }
}

/** UDP 广播发现：每 3 秒在局域网喊一声"我在这"，比 mDNS 更快更稳，作为 NSD 的补充 */
class Beacon(private val ctx: Context) {
    private val udpPort = 45454
    private var sock: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val jobs = ArrayList<Job>()

    @Synchronized
    fun start() {
        stop()
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wm.createMulticastLock("hulian").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(udpPort))
            }
        } catch (e: Exception) {
            return
        }
        sock = s
        jobs.add(Hub.scope.launch { receiveLoop(s) })
        jobs.add(Hub.scope.launch {
            while (isActive) {
                sendAll(s)
                delay(3000)
            }
        })
    }

    @Synchronized
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        try { sock?.close() } catch (_: Exception) {}
        sock = null
        try { lock?.release() } catch (_: Exception) {}
        lock = null
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(1024)
        val pkt = DatagramPacket(buf, buf.size)
        while (!s.isClosed) {
            try {
                pkt.length = buf.size
                s.receive(pkt)
                val j = JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                if (j.optString("t") != "hlb") continue
                val id = j.getString("id")
                if (id == Store.deviceId) continue
                val host = pkt.address.hostAddress ?: continue
                Hub.upsertPeer(id, j.optString("name"), host, j.getInt("port"))
            } catch (e: Exception) {
                if (s.isClosed) break
            }
        }
    }

    private fun sendAll(s: DatagramSocket) {
        val data = JSONObject().put("t", "hlb").put("id", Store.deviceId)
            .put("name", Store.deviceName).put("port", Hub.port).toString().toByteArray(Charsets.UTF_8)
        val targets = HashSet<InetAddress>()
        try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { targets.add(it) }
            }
        } catch (_: Exception) {}
        for (t in targets) {
            try { s.send(DatagramPacket(data, data.size, t, udpPort)) } catch (_: Exception) {}
        }
    }
}
