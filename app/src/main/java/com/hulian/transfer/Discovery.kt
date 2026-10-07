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
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                Hub.reportOnce("nsd-reg", "同一 Wi-Fi 自动发现：本机注册失败", "别的手机可能自动发现不了本机（仍可扫码配对）", "NSD 错误码 $code")
            }
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
        }
        reg = r
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, r) } catch (e: Exception) { Hub.log("Discovery", e) }

        val d = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String, code: Int) {
                Hub.reportOnce("nsd-disc", "同一 Wi-Fi 自动发现：搜索失败", "本机可能发现不了别的手机（仍可扫码配对）", "NSD 错误码 $code")
            }
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
        try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, d) } catch (e: Exception) { Hub.log("Discovery", e) }
        beacon.start()
    }

    @Synchronized
    fun stop() {
        beacon.stop()
        reg?.let { try { nsd.unregisterService(it) } catch (e: Exception) { Hub.log("Discovery", e) } }
        disc?.let { try { nsd.stopServiceDiscovery(it) } catch (e: Exception) { Hub.log("Discovery", e) } }
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
                    try { handle(i) } catch (e: Exception) { Hub.log("Discovery", e) }
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
        Hub.discovered(id, name, host, si.port)
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
        } catch (e: Exception) { Hub.log("Discovery", e) }
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(udpPort))
            }
        } catch (e: Exception) {
            Hub.reportOnce("udp-bind", "局域网广播发现启动失败", "UDP 端口 $udpPort 打不开，广播方式的自动发现不可用（仍可扫码配对）", techDetail(e))
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
        try { sock?.close() } catch (e: Exception) { Hub.log("Discovery", e) }
        sock = null
        try { lock?.release() } catch (e: Exception) { Hub.log("Discovery", e) }
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
                Hub.discovered(id, j.optString("name"), host, j.getInt("port"))
            } catch (e: Exception) {
                if (s.isClosed) break
            }
        }
    }

    private fun sendAll(s: DatagramSocket) {
        val data = JSONObject().put("t", "hlb").put("id", Store.deviceId)
            .put("name", Store.deviceName).put("port", Hub.port).toString().toByteArray(Charsets.UTF_8)
        val targets = HashSet<InetAddress>()
        try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (e: Exception) { Hub.log("Discovery", e) }
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { targets.add(it) }
            }
        } catch (e: Exception) { Hub.log("Discovery", e) }
        for (t in targets) {
            try { s.send(DatagramPacket(data, data.size, t, udpPort)) } catch (e: Exception) { Hub.log("Discovery", e) }
        }
    }
}
