package com.hulian.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS 层（设备身份、自签名证书、双向认证、指纹固定、配对）的测试。
 * 每条测试检查的是一条"必须成立的安全/正确性规则"，名字就是这条规则。
 */
public class TlsTest {
    private static final Tls.Identity SERVER = ident("server");
    private static final Tls.Identity CLIENT = ident("client");
    private static final Tls.Identity OTHER = ident("other");

    private static Tls.Identity ident(String name) {
        try {
            return Tls.generateIdentity(name);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] x) {
        return new String(x, StandardCharsets.UTF_8);
    }

    private static Tls.Trust only(final String... fps) {
        return new Tls.Trust() {
            @Override
            public boolean accept(String fp) {
                return Arrays.asList(fps).contains(fp);
            }
        };
    }

    private static Tls.Trust any() {
        return new Tls.Trust() {
            @Override
            public boolean accept(String fp) {
                return true;
            }
        };
    }

    /** 本机起一个只接一次连接的 TLS 服务端线程 */
    private static class TlsSrv implements AutoCloseable {
        interface Handler {
            Object run(SSLSocket s) throws Exception;
        }

        final SSLServerSocket ss;
        final Thread t;
        volatile Throwable err;
        volatile Object result;

        TlsSrv(Tls.Identity id, Tls.Trust trust, final Handler h) throws IOException {
            ss = Tls.server(id, trust);
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (SSLSocket c = (SSLSocket) ss.accept()) {
                        c.setSoTimeout(5000);
                        c.startHandshake();
                        result = h.run(c);
                    } catch (Throwable e) {
                        err = e;
                    }
                }
            });
            t.start();
        }

        InetSocketAddress addr() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), ss.getLocalPort());
        }

        void join() throws Exception {
            t.join(10000);
        }

        @Override
        public void close() throws IOException {
            ss.close();
        }
    }

    private static SSLSocket connect(TlsSrv srv, Tls.Identity me, String expectedFp) throws IOException {
        SSLSocket c = Tls.connect(srv.addr(), 3000, 5000, me, expectedFp);
        c.setSoTimeout(5000);
        return c;
    }

    private static Wire wire(SSLSocket x) throws IOException {
        return new Wire(x.getInputStream(), x.getOutputStream());
    }

    // ---------- 设备身份与证书 ----------

    @Test
    public void 自签名证书能被标准库解析且签名正确() throws Exception {
        Tls.Identity id = ident("hulian-abc123");
        X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(id.certDer()));
        c.verify(c.getPublicKey()); // 自签名：用自己的公钥验自己的签名，失败会抛异常
        assertEquals("1.2.840.10045.4.3.2", c.getSigAlgOID());
        assertEquals(-1, c.getBasicConstraints()); // 不是 CA
        assertTrue(c.getKeyUsage()[0]); // digitalSignature
        assertTrue(c.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.1"));
        assertTrue(c.getExtendedKeyUsage().contains("1.3.6.1.5.5.7.3.2"));
        assertTrue(c.getSubjectX500Principal().getName().contains("hulian-abc123"));
        long now = System.currentTimeMillis();
        assertTrue(c.getNotBefore().getTime() < now);
        assertTrue(c.getNotAfter().getTime() > now + 10L * 365 * 24 * 3600 * 1000);
        assertEquals(256, ((ECPublicKey) c.getPublicKey()).getParams().getOrder().bitLength());
    }

    @Test
    public void 指纹是43个字符且同一身份稳定不同身份不同() {
        assertEquals(43, SERVER.fingerprint().length());
        assertEquals(SERVER.fingerprint(), SERVER.fingerprint());
        assertTrue(!SERVER.fingerprint().equals(CLIENT.fingerprint()));
    }

    @Test
    public void 身份保存后能原样恢复() throws Exception {
        Tls.Identity r = Tls.loadIdentity(SERVER.keyPkcs8(), SERVER.certDer());
        assertEquals(SERVER.fingerprint(), r.fingerprint());
    }

    @Test
    public void 私钥和证书不配套时拒绝加载() throws Exception {
        try {
            Tls.loadIdentity(SERVER.keyPkcs8(), CLIENT.certDer());
            fail("不配套的私钥和证书不应该能加载");
        } catch (GeneralSecurityException expected) {
            // 正确
        }
    }

    // ---------- 握手与认证 ----------

    @Test
    public void 双向认证握手成功且协商的是TLS13并能收发消息() throws Exception {
        try (TlsSrv srv = new TlsSrv(SERVER, only(CLIENT.fingerprint()), new TlsSrv.Handler() {
            @Override
            public Object run(SSLSocket x) throws Exception {
                Wire w = wire(x);
                String fp = Tls.peerFingerprint(x);
                w.write(b("echo:" + s(w.read())));
                w.flush();
                return new Object[]{fp, x.getSession().getProtocol()};
            }
        })) {
            try (SSLSocket c = connect(srv, CLIENT, SERVER.fingerprint())) {
                Wire w = wire(c);
                w.write(b("你好，互传"));
                w.flush();
                assertEquals("echo:你好，互传", s(w.read()));
                assertEquals("TLSv1.3", c.getSession().getProtocol());
                assertEquals(SERVER.fingerprint(), Tls.peerFingerprint(c));
            }
            srv.join();
            assertNull(srv.err);
            Object[] r = (Object[]) srv.result;
            assertEquals(CLIENT.fingerprint(), r[0]);
            assertEquals("TLSv1.3", r[1]);
        }
    }

    @Test
    public void 客户端固定的指纹不对就拒绝连接() throws Exception {
        // 冒充者拿着自己的证书，客户端要找的是 SERVER：必须在握手阶段就被拒绝
        try (TlsSrv srv = new TlsSrv(OTHER, any(), new TlsSrv.Handler() {
            @Override
            public Object run(SSLSocket x) {
                return null;
            }
        })) {
            try {
                connect(srv, CLIENT, SERVER.fingerprint()).close();
                fail("指纹不符的服务端不应该被接受");
            } catch (IOException expected) {
                // 正确
            }
        }
    }

    @Test
    public void 没有配对指纹为空时不会发起连接() throws Exception {
        try {
            Tls.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), 9), 500, 500, CLIENT, "");
            fail("没有对方指纹不应该连接");
        } catch (IOException expected) {
            // 正确
        }
    }

    @Test
    public void 服务端不认识的客户端被拒绝() throws Exception {
        try (TlsSrv srv = new TlsSrv(SERVER, only(OTHER.fingerprint()), new TlsSrv.Handler() {
            @Override
            public Object run(SSLSocket x) throws Exception {
                Wire w = wire(x);
                w.read();
                w.write(b("不该走到这里"));
                w.flush();
                return "不该走到这里";
            }
        })) {
            boolean failed = false;
            try (SSLSocket c = connect(srv, CLIENT, SERVER.fingerprint())) {
                Wire w = wire(c);
                w.write(b("hi"));
                w.flush();
                w.read(); // TLS 1.3 里客户端往往要到第一次读写才知道被拒绝
            } catch (IOException e) {
                failed = true;
            }
            assertTrue("陌生客户端应该被拒绝", failed);
            srv.join();
            assertTrue(srv.err instanceof IOException);
            assertNull(srv.result);
        }
    }

    @Test
    public void 不出示客户端证书的连接被拒绝() throws Exception {
        try (TlsSrv srv = new TlsSrv(SERVER, any(), new TlsSrv.Handler() {
            @Override
            public Object run(SSLSocket x) throws Exception {
                Wire w = wire(x);
                w.read();
                w.write(b("不该走到这里"));
                w.flush();
                return "不该走到这里";
            }
        })) {
            SSLContext c = SSLContext.getInstance("TLSv1.3");
            c.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            SSLSocket x = (SSLSocket) c.getSocketFactory().createSocket();
            boolean failed = false;
            try {
                x.connect(srv.addr(), 3000);
                x.setSoTimeout(5000);
                x.startHandshake();
                Wire w = wire(x);
                w.write(b("hi"));
                w.flush();
                w.read();
            } catch (IOException e) {
                failed = true;
            } finally {
                x.close();
            }
            assertTrue("没有客户端证书应该被拒绝", failed);
            srv.join();
            assertTrue(srv.err instanceof IOException);
            assertNull(srv.result);
        }
    }

    @Test
    public void 只接受TLS13不接受旧版本() throws Exception {
        try (TlsSrv srv = new TlsSrv(SERVER, any(), new TlsSrv.Handler() {
            @Override
            public Object run(SSLSocket x) throws Exception {
                Wire w = wire(x);
                w.read();
                w.write(b("不该走到这里"));
                w.flush();
                return "不该走到这里";
            }
        })) {
            SSLContext c = Tls.context(CLIENT, any());
            SSLSocket x = (SSLSocket) c.getSocketFactory().createSocket();
            x.setEnabledProtocols(new String[]{"TLSv1.2"});
            boolean failed = false;
            try {
                x.connect(srv.addr(), 3000);
                x.setSoTimeout(5000);
                x.startHandshake();
                Wire w = wire(x);
                w.write(b("hi"));
                w.flush();
                w.read();
            } catch (IOException e) {
                failed = true;
            } finally {
                x.close();
            }
            assertTrue("TLS 1.2 应该被拒绝", failed);
            srv.join();
            assertNull("服务端不应处理 TLS 1.2 的连接", srv.result);
        }
    }

    @Test
    public void 大文件分帧经TLS传输后内容完全一致() throws Exception {
        final byte[] data = new byte[5 * 1024 * 1024 + 11];
        new Random(7).nextBytes(data);
        final byte[] expect = MessageDigest.getInstance("SHA-256").digest(data);
        try (TlsSrv srv = new TlsSrv(SERVER, only(CLIENT.fingerprint()), new TlsSrv.Handler() {
            @Override
            public Object run(SSLSocket x) throws Exception {
                Wire w = wire(x);
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long got = 0;
                while (got < data.length) {
                    byte[] f = w.read();
                    md.update(f);
                    got += f.length;
                }
                w.write(md.digest());
                w.flush();
                return got;
            }
        })) {
            try (SSLSocket c = connect(srv, CLIENT, SERVER.fingerprint())) {
                Wire w = wire(c);
                int off = 0;
                while (off < data.length) {
                    int n = Math.min(Wire.CHUNK, data.length - off);
                    w.write(data, off, n);
                    off += n;
                }
                w.flush();
                assertTrue(Arrays.equals(expect, w.read()));
            }
            srv.join();
            assertNull(srv.err);
            assertEquals((long) data.length, ((Long) srv.result).longValue());
        }
    }

    // ---------- 首次配对 ----------

    /** 跑一次配对：客人发 MAC，主机核对；返回 [主机是否通过, 主机验证码, 客人验证码] */
    private static Object[] pairingRun(final byte[] hostToken, byte[] guestToken) throws Exception {
        try (TlsSrv srv = new TlsSrv(SERVER, any(), new TlsSrv.Handler() {
            @Override
            public Object run(SSLSocket x) throws Exception {
                Wire w = wire(x);
                String guestFp = Tls.peerFingerprint(x);
                byte[] mac = w.read();
                boolean ok = Secure.constantTimeEquals(Tls.pairMac(hostToken, guestFp, SERVER.fingerprint()), mac);
                w.write(new byte[]{(byte) (ok ? 1 : 0)});
                w.flush();
                return new Object[]{ok, Tls.sas(hostToken, guestFp, SERVER.fingerprint())};
            }
        })) {
            try (SSLSocket c = connect(srv, CLIENT, SERVER.fingerprint())) {
                Wire w = wire(c);
                w.write(Tls.pairMac(guestToken, CLIENT.fingerprint(), SERVER.fingerprint()));
                w.flush();
                w.read();
                srv.join();
                assertNull(srv.err);
                Object[] h = (Object[]) srv.result;
                return new Object[]{h[0], h[1], Tls.sas(guestToken, CLIENT.fingerprint(), SERVER.fingerprint())};
            }
        }
    }

    @Test
    public void 持有口令的客人配对通过且两边验证码一致() throws Exception {
        byte[] token = Secure.random(16);
        Object[] r = pairingRun(token, token);
        assertTrue((Boolean) r[0]);
        assertEquals(r[1], r[2]);
        assertTrue(((String) r[1]).matches("\\d{6}"));
    }

    @Test
    public void 口令不对的客人配对不通过() throws Exception {
        Object[] r = pairingRun(Secure.random(16), Secure.random(16));
        assertTrue(!((Boolean) r[0]));
    }

    @Test
    public void 配对凭证绑定双方证书换一个证书就不成立() {
        byte[] token = Secure.random(16);
        byte[] m = Tls.pairMac(token, CLIENT.fingerprint(), SERVER.fingerprint());
        assertTrue(!Arrays.equals(m, Tls.pairMac(token, OTHER.fingerprint(), SERVER.fingerprint())));
        assertTrue(!Arrays.equals(m, Tls.pairMac(token, CLIENT.fingerprint(), OTHER.fingerprint())));
    }

    @Test
    public void 验证码与口令和双方证书有关() {
        byte[] t1 = Secure.random(16);
        byte[] t2 = Secure.random(16);
        String a = Tls.sas(t1, CLIENT.fingerprint(), SERVER.fingerprint());
        assertEquals(a, Tls.sas(t1, CLIENT.fingerprint(), SERVER.fingerprint()));
        assertTrue(a.matches("\\d{6}"));
        // 换口令或换证书，验证码就变（6 位数字有百万分之一的偶然重合，取三组都相同才算失败）
        int same = 0;
        if (a.equals(Tls.sas(t2, CLIENT.fingerprint(), SERVER.fingerprint()))) same++;
        if (a.equals(Tls.sas(t1, OTHER.fingerprint(), SERVER.fingerprint()))) same++;
        if (a.equals(Tls.sas(t1, CLIENT.fingerprint(), OTHER.fingerprint()))) same++;
        assertTrue(same < 3);
    }

    // ---------- 消息分帧 ----------

    @Test
    public void 声明超大或负数长度的帧会被直接拒绝() throws Exception {
        for (int len : new int[]{0x7fffffff, -1, Wire.MAX_FRAME + 1}) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            new DataOutputStream(o).writeInt(len);
            Wire w = new Wire(new ByteArrayInputStream(o.toByteArray()), new ByteArrayOutputStream());
            try {
                w.read();
                fail("长度 " + len + " 应该被拒绝");
            } catch (IOException expected) {
                // 正确
            }
        }
    }

    @Test
    public void 被截断的帧会报错() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(o);
        d.writeInt(10);
        d.write(new byte[]{1, 2, 3});
        Wire w = new Wire(new ByteArrayInputStream(o.toByteArray()), new ByteArrayOutputStream());
        try {
            w.read();
            fail("截断的帧应该报错");
        } catch (IOException expected) {
            // 正确
        }
    }

    @Test
    public void 超过上限的帧不能发出() throws Exception {
        Wire w = new Wire(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
        try {
            w.write(new byte[Wire.MAX_FRAME + 1]);
            fail("应该拒绝");
        } catch (IOException expected) {
            // 正确
        }
    }
}
