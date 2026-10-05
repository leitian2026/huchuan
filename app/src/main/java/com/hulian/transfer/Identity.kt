package com.hulian.transfer

import android.content.Context
import java.io.File

/**
 * 本机的长期身份：EC 密钥对 + 自签名证书。设备身份 = 证书指纹。
 * 私钥用 Android 密钥库里的 AES 密钥加密后保存；证书本身是公开信息。
 * 第一次运行（或身份文件损坏、密钥库被清除）时会重新生成 —— 此时之前配对过的设备需要重新扫码配对。
 */
object Identity {
    lateinit var tls: Tls.Identity
        private set

    /** 本机证书指纹（放进二维码，让扫码的一方固定它） */
    val fp: String get() = tls.fingerprint()

    fun init(ctx: Context) {
        val keyFile = File(ctx.filesDir, "identity.key")
        val certFile = File(ctx.filesDir, "identity.crt")
        try {
            val k = KeyVault.decrypt(keyFile.readText())
            if (k.isEmpty()) throw IllegalStateException("私钥无法解密")
            tls = Tls.loadIdentity(Secure.unb64(k), Secure.unb64(certFile.readText()))
            return
        } catch (e: Exception) {
            // 第一次运行，或身份文件不完整/不配套：重新生成
        }
        val id = Tls.generateIdentity("hulian-" + Store.deviceId)
        keyFile.writeText(KeyVault.encrypt(Secure.b64(id.keyPkcs8())))
        certFile.writeText(Secure.b64(id.certDer()))
        tls = id
    }
}
