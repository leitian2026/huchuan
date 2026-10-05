package com.hulian.transfer;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 互传的加密通道与配对握手（纯 Java，不依赖 Android，便于单元测试）。
 *
 * 一、已配对设备之间的会话（mode=2）
 *   客户端 → 服务端：[mode 1字节][id长度 1字节][客户端id][客户端随机数 16字节]
 *   服务端 → 客户端：[服务端随机数 16字节]      （服务端不认识这个 id 就直接断开，不回任何东西）
 *   双方用配对密钥 K 和两个随机数、两端 id 派生出"上行/下行"两把独立密钥，
 *   之后所有数据都是 AES-256-GCM 加密帧：[4字节长度][密文+16字节认证标签]，
 *   帧的 nonce 是该方向上的帧计数器，所以重放、丢帧、乱序、篡改、反射都会导致认证失败。
 *   谁能解开对方的第一帧，谁就证明了自己持有 K（双向认证）。
 *
 * 二、首次配对（mode=3）
 *   用二维码里的一次性口令 token 代替 K，流程同上；握手成功后双方用
 *   HMAC(token, 两个随机数, 两端id) 各自算出同一个新的配对密钥 K，之后只用 K。
 */
public final class Secure {
    private Secure() {}

    public static final int MODE_SESSION = 2;
    public static final int MODE_PAIR = 3;
    public static final int NONCE_LEN = 16;
    /** 单帧明文上限（也限制了未认证对端能让我们分配的内存） */
    public static final int MAX_FRAME = 1 << 20;
    /** 传文件时每帧的明文大小 */
    public static final int CHUNK = 128 * 1024;
    private static final int TAG_LEN = 16;
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final SecureRandom RNG = new SecureRandom();

    /** 服务端查询密钥用 */
    public interface Keys {
        /** 返回与该设备的配对密钥；不认识返回 null */
        byte[] keyFor(String peerId);

        /** 当前有效的一次性配对口令；没有在等人配对就返回 null */
        byte[] pairToken();
    }

    // ---------- 小工具 ----------

    public static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] unb64(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    /** HMAC-SHA256；每个部分前面带 4 字节长度，避免拼接歧义 */
    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) {
                mac.update(ByteBuffer.allocate(4).putInt(p.length).array());
                mac.update(p);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void checkSecret(byte[] secret) throws IOException {
        if (secret == null || secret.length < 16) throw new IOException("密钥无效");
    }

    private static void checkId(String id) throws IOException {
        if (id == null || !ID.matcher(id).matches()) throw new IOException("设备 ID 非法");
    }

    private static byte[][] deriveKeys(boolean pairing, byte[] secret, byte[] cn, byte[] sn, byte[] cid, byte[] sid) {
        String label = pairing ? "hl3-pair" : "hl3-sess";
        return new byte[][]{
                hmac(secret, utf8(label + "-c2s"), cn, sn, cid, sid),
                hmac(secret, utf8(label + "-s2c"), cn, sn, cid, sid)
        };
    }

    // ---------- 加密帧通道 ----------

    public static final class Channel {
        private final DataInputStream in;
        private final OutputStream out;
        private final SecretKeySpec wk;
        private final SecretKeySpec rk;
        private final Cipher wc;
        private final Cipher rc;
        private final Object wlock = new Object();
        private final Object rlock = new Object();
        private long wn = 0;
        private long rn = 0;

        /** wkey：本端发送用的密钥；rkey：本端接收用的密钥 */
        Channel(DataInputStream in, OutputStream out, byte[] wkey, byte[] rkey) {
            this.in = in;
            this.out = out;
            this.wk = new SecretKeySpec(wkey, "AES");
            this.rk = new SecretKeySpec(rkey, "AES");
            try {
                this.wc = Cipher.getInstance("AES/GCM/NoPadding");
                this.rc = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        private static byte[] nonce(long n) {
            return ByteBuffer.allocate(12).putInt(0).putLong(n).array();
        }

        public void write(byte[] b) throws IOException {
            write(b, 0, b.length);
        }

        public void write(byte[] b, int off, int len) throws IOException {
            if (len < 0 || len > MAX_FRAME) throw new IOException("单次数据过大");
            synchronized (wlock) {
                try {
                    wc.init(Cipher.ENCRYPT_MODE, wk, new GCMParameterSpec(TAG_LEN * 8, nonce(wn++)));
                    byte[] ct = wc.doFinal(b, off, len);
                    ByteBuffer buf = ByteBuffer.allocate(4 + ct.length);
                    buf.putInt(ct.length).put(ct);
                    out.write(buf.array());
                } catch (GeneralSecurityException e) {
                    throw new IOException("加密失败", e);
                }
            }
        }

        public void flush() throws IOException {
            out.flush();
        }

        /** 读一帧并验证；认证失败说明对方没有正确的密钥，或数据被篡改/重放/乱序 */
        public byte[] read() throws IOException {
            synchronized (rlock) {
                int len = in.readInt();
                if (len < TAG_LEN || len > MAX_FRAME + TAG_LEN) throw new IOException("数据格式错误");
                byte[] ct = new byte[len];
                in.readFully(ct);
                try {
                    rc.init(Cipher.DECRYPT_MODE, rk, new GCMParameterSpec(TAG_LEN * 8, nonce(rn++)));
                    return rc.doFinal(ct);
                } catch (GeneralSecurityException e) {
                    throw new IOException("认证失败（对方未配对，或数据被篡改）");
                }
            }
        }
    }

    // ---------- 握手 ----------

    public static final class Session {
        public final boolean pairing;
        public final String peerId;
        public final Channel ch;
        /** 握手用的密钥：会话为 K；配对为一次性口令 */
        public final byte[] secret;
        private final byte[] cn;
        private final byte[] sn;
        private final byte[] cid;
        private final byte[] sid;

        Session(boolean pairing, String peerId, Channel ch, byte[] secret, byte[] cn, byte[] sn, byte[] cid, byte[] sid) {
            this.pairing = pairing;
            this.peerId = peerId;
            this.ch = ch;
            this.secret = secret;
            this.cn = cn;
            this.sn = sn;
            this.cid = cid;
            this.sid = sid;
        }

        /** 配对成功（双方都已验证过对方的第一帧）后，算出之后长期使用的配对密钥 K（32 字节） */
        public byte[] pairKey() {
            return hmac(secret, utf8("hl3-pairkey"), cn, sn, cid, sid);
        }
    }

    /** 客户端：连上 socket 之后调用。pairing=true 时 secret 是二维码里的口令，否则是配对密钥 K */
    public static Session connect(InputStream rawIn, OutputStream out, String myId, String peerId,
                                  byte[] secret, boolean pairing) throws IOException {
        checkSecret(secret);
        checkId(myId);
        checkId(peerId);
        DataInputStream in = new DataInputStream(rawIn);
        byte[] cid = utf8(myId);
        byte[] sid = utf8(peerId);
        byte[] cn = random(NONCE_LEN);
        ByteBuffer b = ByteBuffer.allocate(2 + cid.length + cn.length);
        b.put((byte) (pairing ? MODE_PAIR : MODE_SESSION)).put((byte) cid.length).put(cid).put(cn);
        out.write(b.array());
        out.flush();
        byte[] sn = new byte[NONCE_LEN];
        try {
            in.readFully(sn);
        } catch (EOFException | SocketException e) {
            throw new IOException(pairing
                    ? "对方没有在等待配对（请让对方重新打开\u201c我的二维码\u201d页面再扫）"
                    : "对方不认识本机，请删除后重新扫码配对", e);
        }
        byte[][] k = deriveKeys(pairing, secret, cn, sn, cid, sid);
        return new Session(pairing, peerId, new Channel(in, out, k[0], k[1]), secret, cn, sn, cid, sid);
    }

    /** 服务端：accept 到 socket 之后调用。不认识的设备/没有在等配对时直接抛异常，由调用者关闭连接 */
    public static Session accept(InputStream rawIn, OutputStream out, String myId, Keys keys) throws IOException {
        checkId(myId);
        DataInputStream in = new DataInputStream(rawIn);
        int mode = in.readUnsignedByte();
        if (mode != MODE_SESSION && mode != MODE_PAIR) throw new IOException("未知协议");
        int idLen = in.readUnsignedByte();
        if (idLen < 1 || idLen > 64) throw new IOException("设备 ID 非法");
        byte[] cid = new byte[idLen];
        in.readFully(cid);
        String peerId = new String(cid, StandardCharsets.UTF_8);
        checkId(peerId);
        if (peerId.equals(myId)) throw new IOException("不能连接自己");
        byte[] cn = new byte[NONCE_LEN];
        in.readFully(cn);
        boolean pairing = mode == MODE_PAIR;
        byte[] secret = pairing ? keys.pairToken() : keys.keyFor(peerId);
        if (secret == null) throw new IOException(pairing ? "当前未开放配对" : "未配对的设备");
        checkSecret(secret);
        byte[] sid = utf8(myId);
        byte[] sn = random(NONCE_LEN);
        out.write(sn);
        out.flush();
        byte[][] k = deriveKeys(pairing, secret, cn, sn, cid, sid);
        return new Session(pairing, peerId, new Channel(in, out, k[1], k[0]), secret, cn, sn, cid, sid);
    }
}
