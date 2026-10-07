package com.hulian.transfer

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android 密钥库里的 AES 密钥加密保存配对密钥。
 * 加密用的密钥留在系统的安全存储里、导不出来，所以 peers.json 即使被别的程序读走或拷走也解不开。
 */
object KeyVault {
    private const val ALIAS = "hulian_peer_keys_v1"
    private const val PREFIX = "v1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return g.generateKey()
    }

    /** 明文密钥 → "v1:iv:密文"。密钥库不可用时退回明文，宁可降级也不丢掉配对 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Secure.b64(c.iv) + ":" + Secure.b64(ct)
        } catch (e: Exception) {
            Hub.reportOnce("keyvault-enc", "私钥加密保存失败", "系统的密钥库不可用，私钥将以未加密的形式保存在本机", techDetail(e))
            plain
        }
    }

    /** 没有 v1: 前缀的是旧版本留下的明文，原样返回（下次保存时自动变成加密的）；解不开就当作未配对 */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val p = stored.removePrefix(PREFIX).split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Secure.unb64(p[0])))
            String(c.doFinal(Secure.unb64(p[1])), Charsets.UTF_8)
        } catch (e: Exception) {
            Hub.log("私钥解密", e)
            ""
        }
    }
}
