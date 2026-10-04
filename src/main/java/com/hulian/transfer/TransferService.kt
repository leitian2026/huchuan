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

        Server.start()
        if (discovery == null) {
            discovery = Discovery(this).also {
                Hub.discovery = it
                it.start()
            }
        }
        Hub.startSweeper()
        Hub.probeKnown()

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
            try { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) } catch (_: Exception) {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        netCb?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCb = null
        discovery?.stop()
        discovery = null
        Hub.discovery = null
        Server.stop()
        super.onDestroy()
    }
}
