package com.hulian.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** 同一 Wi-Fi 下用 mDNS(NSD) 互相发现 */
class Discovery(ctx: Context) {
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
    }

    @Synchronized
    fun stop() {
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
