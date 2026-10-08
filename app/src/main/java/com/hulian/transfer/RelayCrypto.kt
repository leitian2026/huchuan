package com.hulian.transfer

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 远程中转的端到端加密：内容在手机上用两台手机共有的密钥加密，网盘和 Cloudflare 只能看到密文。
 *
 * 密钥（32 字节随机数）在两台手机直连配对时通过 TLS 通道交换，从不经过网盘或 Cloudflare。
 *
 * 文件格式： "HLR1" + 8 字节随机前缀 + 若干块，每块 = 4 字节长度 + AES-256-GCM 密文（含 16 字节校验）。
 * 第 0 块是消息头（类型、文件名、大小、发送者…），后面是数据块（每块最多 64KB）。
 * 每块的 nonce = 随机前缀 + 块序号：改动、调换、删除任何一块都会校验失败；
 * 文件被截断时，收到的数据量会和消息头里（已校验）声明的大小对不上。
 */
object RelayCrypto {
    const val CHUNK = 64 * 1024
    private const val TAG = 16
    private const val MAX_FRAME = Wire.MAX_FRAME + TAG
    private val MAGIC = byteArrayOf('H'.code.toByte(), 'L'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte())

    fun newKey(): String {
        val b = ByteArray(32)
        SecureRandom().nextBytes(b)
        return Secure.b64(b)
    }

    fun validKey(s: String): Boolean = try { Secure.unb64(s).size == 32 } catch (e: Exception) { false }

    fun key(rk: String): SecretKeySpec {
        val raw = Secure.unb64(rk)
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("hulian-relay-v1".toByteArray() + raw), "AES")
    }

    private fun nonce(prefix: ByteArray, counter: Int): ByteArray {
        val n = ByteArray(12)
        System.arraycopy(prefix, 0, n, 0, 8)
        n[8] = (counter ushr 24).toByte()
        n[9] = (counter ushr 16).toByte()
        n[10] = (counter ushr 8).toByte()
        n[11] = counter.toByte()
        return n
    }

    private fun writeInt(out: OutputStream, v: Int) {
        out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v)
    }

    /** 加密后的总字节数（上传时要先告诉网盘 Content-Length） */
    fun encryptedSize(metaLen: Int, dataLen: Long): Long {
        val frames = if (dataLen == 0L) 0L else (dataLen + CHUNK - 1) / CHUNK
        return MAGIC.size + 8L + (4 + metaLen + TAG) + frames * (4 + TAG) + dataLen
    }

    fun encrypt(key: SecretKeySpec, meta: ByteArray, data: InputStream?, out: OutputStream, onProgress: (Long) -> Unit) {
        val prefix = ByteArray(8).also { SecureRandom().nextBytes(it) }
        out.write(MAGIC)
        out.write(prefix)
        var counter = 0
        fun frame(plain: ByteArray, len: Int) {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter++)))
            val ct = c.doFinal(plain, 0, len)
            writeInt(out, ct.size)
            out.write(ct)
        }
        frame(meta, meta.size)
        if (data != null) {
            val buf = ByteArray(CHUNK)
            var total = 0L
            while (true) {
                val n = data.read(buf)
                if (n < 0) break
                if (n == 0) continue
                frame(buf, n)
                total += n
                onProgress(total)
            }
        }
        out.flush()
    }

    class Reader(input: InputStream) {
        private val ins = DataInputStream(input)
        private val prefix = ByteArray(8)
        private var counter = 0
        private var key: SecretKeySpec? = null

        init {
            val m = ByteArray(4)
            try { ins.readFully(m) } catch (e: EOFException) { throw IOException("中转文件是空的", e) }
            if (!m.contentEquals(MAGIC)) throw IOException("不是互传的中转文件（文件头不对）")
            ins.readFully(prefix)
        }

        private fun rawFrame(): ByteArray? {
            val len = try { ins.readInt() } catch (e: EOFException) { return null }
            if (len < TAG || len > MAX_FRAME) throw IOException("中转文件损坏：数据块长度异常（$len）")
            val b = ByteArray(len)
            ins.readFully(b)
            return b
        }

        private fun dec(k: SecretKeySpec, raw: ByteArray, ctr: Int): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(prefix, ctr)))
            return c.doFinal(raw)
        }

        /** 用候选密钥依次试解第 0 块（消息头），解开的那个就是发送者。返回（候选序号, 消息头）；都解不开返回 null */
        fun open(candidates: List<SecretKeySpec>): Pair<Int, ByteArray>? {
            val raw = rawFrame() ?: throw IOException("中转文件是空的")
            for ((i, k) in candidates.withIndex()) {
                try {
                    val meta = dec(k, raw, 0)
                    key = k
                    counter = 1
                    return i to meta
                } catch (e: GeneralSecurityException) {
                    // 不是这把密钥
                }
            }
            return null
        }

        /** 下一个数据块；没有了返回 null */
        fun next(): ByteArray? {
            val raw = rawFrame() ?: return null
            return try {
                dec(key!!, raw, counter++)
            } catch (e: GeneralSecurityException) {
                throw IOException("数据校验失败：中转文件被改动过，或传输中损坏了", e)
            }
        }
    }
}
