package com.hulian.transfer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * 传输层安全：TLS 1.3 双向认证 + 证书指纹固定（Syncthing / KDE Connect / LocalSend 同类做法）。
 *
 * - 每台设备有一对长期的 EC(P-256) 密钥和自签名证书，"设备身份" = 证书的 SHA-256 指纹。
 * - 连接双方都要出示证书；本方只接受"已配对设备的指纹"（客户端则只接受它要找的那台设备的指纹）。
 * - 握手、密钥交换、加密、前向保密、防重放、防篡改，全部由平台自带的 TLS 1.3 实现负责。
 * - 首次配对：扫码一方从二维码里拿到主机的指纹并固定它（中间人无法冒充）；
 *   主机一方在"配对窗口"内暂时接受陌生证书，再由一次性口令 + 人工核对验证码确认。
 */
public final class Tls {
    private Tls() {}

    private static final String[] PROTOCOLS = {"TLSv1.3"};
    private static final String SIG_ALG = "SHA256withECDSA";
    private static final String SIG_OID = "1.2.840.10045.4.3.2"; // ecdsa-with-SHA256

    /** 决定"这个指纹是不是我愿意跟他通信的对象" */
    public interface Trust {
        boolean accept(String fingerprint);
    }

    // ---------- 本机身份 ----------

    public static final class Identity {
        public final PrivateKey key;
        public final X509Certificate cert;

        Identity(PrivateKey key, X509Certificate cert) {
            this.key = key;
            this.cert = cert;
        }

        public String fingerprint() {
            return Tls.fingerprint(cert);
        }

        public byte[] keyPkcs8() {
            return key.getEncoded();
        }

        public byte[] certDer() {
            try {
                return cert.getEncoded();
            } catch (CertificateException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** 生成新的设备身份：EC 密钥对 + 自签名证书（有效期约 20 年） */
    public static Identity generateIdentity(String commonName) throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        X509Certificate cert = parseCert(buildSelfSignedCert(kp, commonName));
        return new Identity(kp.getPrivate(), cert);
    }

    /** 从保存的私钥 + 证书恢复身份；两者必须配套，否则抛异常 */
    public static Identity loadIdentity(byte[] pkcs8, byte[] certDer) throws GeneralSecurityException {
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        X509Certificate cert = parseCert(certDer);
        byte[] probe = Secure.random(32);
        Signature s = Signature.getInstance(SIG_ALG);
        s.initSign(key);
        s.update(probe);
        byte[] sig = s.sign();
        Signature v = Signature.getInstance(SIG_ALG);
        v.initVerify(cert);
        v.update(probe);
        if (!v.verify(sig)) throw new GeneralSecurityException("私钥与证书不匹配");
        return new Identity(key, cert);
    }

    /** 证书指纹：整张证书 DER 的 SHA-256，base64url（43 个字符） */
    public static String fingerprint(X509Certificate cert) {
        try {
            return Secure.b64(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static X509Certificate parseCert(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    // ---------- 自签名证书（DER 编码） ----------

    private static byte[] lenBytes(int n) {
        if (n < 128) return new byte[]{(byte) n};
        if (n < 256) return new byte[]{(byte) 0x81, (byte) n};
        if (n < 65536) return new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
        throw new IllegalArgumentException("too long");
    }

    private static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        byte[] content = body.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        byte[] l = lenBytes(content.length);
        out.write(l, 0, l.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, parts);
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, parts);
    }

    private static byte[] derInt(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    private static byte[] derBool(boolean v) {
        return tlv(0x01, new byte[]{(byte) (v ? 0xFF : 0x00)});
    }

    private static byte[] derOctets(byte[] v) {
        return tlv(0x04, v);
    }

    private static byte[] derBits(byte[] v, int unused) {
        byte[] c = new byte[v.length + 1];
        c[0] = (byte) unused;
        System.arraycopy(v, 0, c, 1, v.length);
        return tlv(0x03, c);
    }

    private static byte[] derUtf8(String s) {
        return tlv(0x0C, Secure.utf8(s));
    }

    private static byte[] derUtcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(d).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static byte[] derOid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            int n = 1;
            while ((v >> (7 * n)) != 0) n++;
            for (int j = n - 1; j >= 0; j--) {
                int b = (int) ((v >> (7 * j)) & 0x7F);
                out.write(j > 0 ? (b | 0x80) : b);
            }
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] buildSelfSignedCert(KeyPair kp, String cn) throws GeneralSecurityException {
        long now = System.currentTimeMillis();
        byte[] sigAlg = seq(derOid(SIG_OID));
        byte[] name = seq(set(seq(derOid("2.5.4.3"), derUtf8(cn))));
        byte[] serialBytes = Secure.random(16);
        serialBytes[0] &= 0x7F; // 保证是正数
        byte[] extensions = tlv(0xA3, seq(
                // BasicConstraints: 不是 CA
                seq(derOid("2.5.29.19"), derBool(true), derOctets(seq())),
                // KeyUsage: digitalSignature
                seq(derOid("2.5.29.15"), derBool(true), derOctets(derBits(new byte[]{(byte) 0x80}, 7))),
                // ExtendedKeyUsage: serverAuth, clientAuth
                seq(derOid("2.5.29.37"), derOctets(seq(derOid("1.3.6.1.5.5.7.3.1"), derOid("1.3.6.1.5.5.7.3.2"))))
        ));
        byte[] tbs = seq(
                tlv(0xA0, derInt(BigInteger.valueOf(2))), // version v3
                derInt(new BigInteger(1, serialBytes)),
                sigAlg,
                name,
                seq(derUtcTime(new Date(now - 24L * 3600 * 1000)), derUtcTime(new Date(now + 20L * 365 * 24 * 3600 * 1000))),
                name,
                kp.getPublic().getEncoded(), // SubjectPublicKeyInfo
                extensions
        );
        Signature s = Signature.getInstance(SIG_ALG);
        s.initSign(kp.getPrivate());
        s.update(tbs);
        return seq(tbs, sigAlg, derBits(s.sign(), 0));
    }

    // ---------- TLS 上下文 ----------

    private static final String ALIAS = "hulian";

    /** 只有一把钥匙：本机身份，不管对方要什么类型都用它 */
    private static final class FixedKeys extends X509ExtendedKeyManager {
        private final Identity id;

        FixedKeys(Identity id) {
            this.id = id;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return ALIAS;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return ALIAS;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return ALIAS;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return ALIAS;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return ALIAS.equals(alias) ? new X509Certificate[]{id.cert} : null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return ALIAS.equals(alias) ? id.key : null;
        }
    }

    /** 不看证书链、不看有效期、不看主机名：只看指纹是不是我们信任的那个。身份由 TLS 握手里对私钥的证明来保证 */
    private static final class PinTrust extends X509ExtendedTrustManager {
        private final Trust trust;

        PinTrust(Trust trust) {
            this.trust = trust;
        }

        private void check(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("对方没有出示证书");
            if (!trust.accept(fingerprint(chain[0]))) throw new CertificateException("对方不是已配对的设备");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    public static SSLContext context(Identity id, Trust trust) throws GeneralSecurityException {
        SSLContext c = SSLContext.getInstance("TLSv1.3");
        c.init(new KeyManager[]{new FixedKeys(id)}, new TrustManager[]{new PinTrust(trust)}, new SecureRandom());
        return c;
    }

    /** 客户端：连到对方并完成 TLS 握手；只接受指纹等于 expectedFp 的服务端 */
    public static SSLSocket connect(InetSocketAddress addr, int connectTimeoutMs, int handshakeTimeoutMs,
                                    Identity id, final String expectedFp) throws IOException {
        if (expectedFp == null || expectedFp.isEmpty()) throw new IOException("尚未配对，请扫码重新配对");
        SSLSocket s = null;
        try {
            SSLContext c = context(id, new Trust() {
                @Override
                public boolean accept(String fingerprint) {
                    return fingerprint.equals(expectedFp);
                }
            });
            s = (SSLSocket) c.getSocketFactory().createSocket();
            s.setEnabledProtocols(PROTOCOLS);
            s.setTcpNoDelay(true);
            try {
                s.setSendBufferSize(1 << 20);
            } catch (Exception ignored) {
                // 非关键优化
            }
            s.connect(addr, connectTimeoutMs);
            s.setSoTimeout(handshakeTimeoutMs);
            s.startHandshake();
            return s;
        } catch (GeneralSecurityException e) {
            closeQuietly(s);
            throw new IOException("无法建立安全连接", e);
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
    }

    /** 服务端：返回还没绑定端口的 TLS 服务端 socket（调用者负责 bind）。必须出示客户端证书 */
    public static SSLServerSocket server(Identity id, Trust trust) throws IOException {
        try {
            SSLContext c = context(id, trust);
            SSLServerSocket ss = (SSLServerSocket) c.getServerSocketFactory().createServerSocket();
            ss.setEnabledProtocols(PROTOCOLS);
            ss.setNeedClientAuth(true);
            ss.setReuseAddress(true);
            return ss;
        } catch (GeneralSecurityException e) {
            throw new IOException("无法启动安全服务", e);
        }
    }

    /** 握手完成后，对方证书的指纹 */
    public static String peerFingerprint(SSLSocket s) throws IOException {
        Certificate[] certs = s.getSession().getPeerCertificates();
        if (certs == null || certs.length == 0 || !(certs[0] instanceof X509Certificate)) {
            throw new IOException("对方没有出示证书");
        }
        return fingerprint((X509Certificate) certs[0]);
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 忽略
            }
        }
    }

    // ---------- 配对 ----------

    /** 客人向主机证明"我持有二维码里的口令"：口令 + 双方指纹的 MAC（换一个证书就不成立，转发也没用） */
    public static byte[] pairMac(byte[] token, String guestFp, String hostFp) {
        return Secure.hmac(token, Secure.utf8("hl5-pair"), Secure.utf8(guestFp), Secure.utf8(hostFp));
    }

    /** 配对验证码（6 位数字），两台手机上显示的必须一致 */
    public static String sas(byte[] token, String guestFp, String hostFp) {
        byte[] h = Secure.hmac(token, Secure.utf8("hl5-sas"), Secure.utf8(guestFp), Secure.utf8(hostFp));
        long v = (((h[0] & 0xffL) << 24) | ((h[1] & 0xffL) << 16) | ((h[2] & 0xffL) << 8) | (h[3] & 0xffL)) % 1_000_000L;
        return String.format("%06d", v);
    }
}
