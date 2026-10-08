package com.hulian.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 前台服务：保持收发服务在后台/锁屏时继续运行，并在网络变化时自动重新搜索设备 */
class TransferService : Service() {
    private var discovery: Discovery? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("svc", "互传运行状态", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "svc")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("互传正在运行")
            .setContentText("可接收附近设备发来的文件和消息")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        try {
            Server.start()
        } catch (e: Exception) {
            Hub.report("无法启动接收服务", e, "别的手机将无法给本机发送文字和文件")
        }
        if (discovery == null) {
            discovery = Discovery(this).also {
                Hub.discovery = it
                it.start()
            }
        }
        Hub.startSweeper()
        Hub.probeKnown()
        Relay.start()

        if (netCb == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 连上新网络后稍等一下再重新搜索，避免网络还没稳定
                    Hub.scope.launch {
                        delay(1200)
                        Hub.onNetworkChanged()
                    }
                }
            }
            netCb = cb
            try { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) } catch (e: Exception) { Hub.log("TransferService", e) }
        }
        return START_STICKY
    }

    /**
     * 用户从最近任务里划掉 app：很多系统紧接着就会杀进程（状态栏的“正在运行”也随之消失），onDestroy 来不及走。
     * 这里先给还在线的对方发“下线”通知，让对方马上标为离线。
     * 如果进程其实没被杀（原生 Android 上服务会继续运行），3 秒后重连一次，Cloudflare 会重新推送在线状态，对方又会看到在线
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Relay.goodbye()
        Hub.scope.launch {
            delay(3000)
            if (Hub.discovery != null) Relay.restart()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        netCb?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (e: Exception) { Hub.log("TransferService", e) } }
        netCb = null
        Relay.goodbye()
        Relay.stop()
        discovery?.stop()
        discovery = null
        Hub.discovery = null
        Server.stop()
        super.onDestroy()
    }
}
