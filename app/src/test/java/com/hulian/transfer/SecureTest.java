package com.hulian.transfer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/**
 * 加密通道与配对握手的测试。
 * 每条测试检查的是一条"必须成立的安全/正确性规则"，名字就是这条规则。
 */
public class SecureTest {
    private static final byte[] K = Secure.random(32);

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Secure.Keys keys(final String id, final byte[] key, final byte[] token) {
        return new Secure.Keys() {
            @Override
            public byte[] keyFor(String peerId) {
                return peerId.equals(id) ? key : null;
            }

            @Override
            public byte[] pairToken() {
                return token;
            }
        };
    }

    /** 在本机起一个只接一次连接的服务端线程 */
    private static class Srv implements AutoCloseable {
        interface Handler {
            Object run(Socket s) throws Exception;
        }

        final ServerSocket ss;
        final Thread t;
        volatile Throwable err;
        volatile Object result;

        Srv(final Handler h) throws IOException {
            ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            t = new Thread(() -> {
                try (Socket c = ss.accept()) {
                    c.setSoTimeout(5000);
                    result = h.run(c);
                } catch (Throwable e) {
                    err = e;
                }
            });
            t.start();
        }

        Socket client() throws IOException {
            Socket c = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort());
            c.setSoTimeout(5000);
            return c;
        }

        void join() throws Exception {
            t.join(10000);
        }

        @Override
        public void close() throws IOException {
            ss.close();
        }
    }

    /** 记录客户端发出的所有字节，用来做重放测试 */
    private static class Tee extends OutputStream {
        final OutputStream a;
        final ByteArrayOutputStream rec = new ByteArrayOutputStream();

        Tee(OutputStream a) {
            this.a = a;
        }

        @Override
        public void write(int x) throws IOException {
            a.write(x);
            rec.write(x);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len);
            rec.write(buf, off, len);
        }

        @Override
        public void flush() throws IOException {
            a.flush();
        }
    }

    private static byte[] frames(byte[] wkey, byte[] rkey, byte[]... msgs) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        Secure.Channel w = new Secure.Channel(new DataInputStream(new ByteArrayInputStream(new byte[0])), o, wkey, rkey);
        for (byte[] m : msgs) w.write(m);
        return o.toByteArray();
    }

    private static Secure.Channel reader(byte[] wkey, byte[] rkey, byte[] data) {
        return new Secure.Channel(new DataInputStream(new ByteArrayInputStream(data)), new ByteArrayOutputStream(), wkey, rkey);
    }

    private static void assertReadFails(Secure.Channel ch) {
        try {
            ch.read();
            fail("应该读取失败，但读到了数据");
        } catch (IOException expected) {
            // 正确：认证失败或格式错误
        }
    }

    // ---------- 已配对设备之间的会话 ----------

    @Test
    public void 已配对设备能互相收发加密消息() throws Exception {
        try (Srv srv = new Srv(sock -> {
            Secure.Session ses = Secure.accept(sock.getInputStream(), sock.getOutputStream(), "SERVER01", keys("CLIENT01", K, null));
            byte[] req = ses.ch.read();
            ses.ch.write(b("echo:" + s(req)));
            ses.ch.flush();
            return ses.peerId;
        })) {
            try (Socket c = srv.client()) {
                Secure.Session ses = Secure.connect(c.getInputStream(), c.getOutputStream(), "CLIENT01", "SERVER01", K, false);
                ses.ch.write(b("你好，互传"));
                ses.ch.flush();
                assertEquals("echo:你好，互传", s(ses.ch.read()));
            }
            srv.join();
            assertNull(srv.err);
            assertEquals("CLIENT01", srv.result);
        }
    }

    @Test
    public void 大文件分帧传输后内容完全一致() throws Exception {
        final byte[] data = new byte[3 * 1024 * 1024 + 7];
        new Random(42).nextBytes(data);
        final byte[] expect = MessageDigest.getInstance("SHA-256").digest(data);
        try (Srv srv = new Srv(sock -> {
            Secure.Session ses = Secure.accept(sock.getInputStream(), sock.getOutputStream(), "SERVER01", keys("CLIENT01", K, null));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long got = 0;
            while (got < data.length) {
                byte[] f = ses.ch.read();
                md.update(f);
                got += f.length;
            }
            ses.ch.write(md.digest());
            ses.ch.flush();
            return got;
        })) {
            try (Socket c = srv.client()) {
                Secure.Session ses = Secure.connect(c.getInputStream(), c.getOutputStream(), "CLIENT01", "SERVER01", K, false);
                int off = 0;
                while (off < data.length) {
                    int n = Math.min(100_000, data.length - off);
                    ses.ch.write(data, off, n);
                    off += n;
                }
                ses.ch.flush();
                assertArrayEquals(expect, ses.ch.read());
            }
            srv.join();
            assertNull(srv.err);
            assertEquals((long) data.length, ((Long) srv.result).longValue());
        }
    }

    @Test
    public void 密钥不对的客户端什么都读不到也发不进() throws Exception {
        try (Srv srv = new Srv(sock -> {
            Secure.Session ses = Secure.accept(sock.getInputStream(), sock.getOutputStream(), "SERVER01", keys("CLIENT01", K, null));
            ses.ch.read(); // 应抛认证失败
            return "不该走到这里";
        })) {
            try (Socket c = srv.client()) {
                Secure.Session ses = Secure.connect(c.getInputStream(), c.getOutputStream(), "CLIENT01", "SERVER01", Secure.random(32), false);
                ses.ch.write(b("我是冒充的"));
                ses.ch.flush();
                assertReadFails(ses.ch);
            }
            srv.join();
            assertTrue("服务端应该拒绝", srv.err instanceof IOException);
            assertNull(srv.result);
        }
    }

    @Test
    public void 服务端不认识的设备直接被断开() throws Exception {
        try (Srv srv = new Srv(sock -> {
            Secure.accept(sock.getInputStream(), sock.getOutputStream(), "SERVER01", keys("SOMEONE1", K, null));
            return "不该走到这里";
        })) {
            try (Socket c = srv.client()) {
                try {
                    Secure.connect(c.getInputStream(), c.getOutputStream(), "CLIENT01", "SERVER01", K, false);
                    fail("未配对设备不应该能完成握手");
                } catch (IOException expected) {
                    // 正确
                }
            }
            srv.join();
            assertTrue(srv.err instanceof IOException);
        }
    }

    @Test
    public void 假冒的服务端无法让客户端相信() throws Exception {
        // 冒充者不知道 K：它回复的帧，客户端读取时必须认证失败
        try (Srv srv = new Srv(sock -> {
            Secure.Session ses = Secure.accept(sock.getInputStream(), sock.getOutputStream(), "SERVER01", keys("CLIENT01", Secure.random(32), null));
            ses.ch.write(b("好的，已收到"));
            ses.ch.flush();
            return null;
        })) {
            try (Socket c = srv.client()) {
                Secure.Session ses = Secure.connect(c.getInputStream(), c.getOutputStream(), "CLIENT01", "SERVER01", K, false);
                ses.ch.write(b("机密"));
                ses.ch.flush();
                assertReadFails(ses.ch);
            }
        }
    }

    // ---------- 帧层面的攻击 ----------

    @Test
    public void 被改动一个字节的数据帧会被发现() throws Exception {
        byte[] a = Secure.random(32), bk = Secure.random(32);
        byte[] data = frames(a, bk, b("hello"));
        data[data.length - 20] ^= 1;
        assertReadFails(reader(bk, a, data));
    }

    @Test
    public void 被截断的数据帧会被发现() throws Exception {
        byte[] a = Secure.random(32), bk = Secure.random(32);
        byte[] data = frames(a, bk, b("hello"));
        assertReadFails(reader(bk, a, Arrays.copyOf(data, data.length - 3)));
    }

    @Test
    public void 丢掉一帧会被发现() throws Exception {
        byte[] a = Secure.random(32), bk = Secure.random(32);
        byte[] data = frames(a, bk, b("one"), b("two"));
        int firstLen = 4 + ByteBuffer.wrap(data).getInt();
        assertReadFails(reader(bk, a, Arrays.copyOfRange(data, firstLen, data.length)));
    }

    @Test
    public void 重复发送同一帧会被发现() throws Exception {
        byte[] a = Secure.random(32), bk = Secure.random(32);
        byte[] one = frames(a, bk, b("one"));
        byte[] twice = new byte[one.length * 2];
        System.arraycopy(one, 0, twice, 0, one.length);
        System.arraycopy(one, 0, twice, one.length, one.length);
        Secure.Channel r = reader(bk, a, twice);
        assertEquals("one", s(r.read()));
        assertReadFails(r);
    }

    @Test
    public void 把对方发的帧原样反射回去会被发现() throws Exception {
        byte[] a = Secure.random(32), bk = Secure.random(32);
        byte[] data = frames(a, bk, b("hello"));
        assertReadFails(reader(a, bk, data)); // 方向相同的一端读自己发出的帧
    }

    @Test
    public void 声明超大长度的帧会被直接拒绝而不是去分配内存() throws Exception {
        byte[] a = Secure.random(32), bk = Secure.random(32);
        byte[] evil = ByteBuffer.allocate(8).putInt(0x7fffffff).putInt(0).array();
        assertReadFails(reader(bk, a, evil));
    }

    @Test
    public void 录下来的旧连接数据不能重放到新连接() throws Exception {
        final Tee[] tee = new Tee[1];
        try (Srv srv = new Srv(sock -> {
            Secure.Session ses = Secure.accept(sock.getInputStream(), sock.getOutputStream(), "SERVER01", keys("CLIENT01", K, null));
            ses.ch.read();
            ses.ch.write(b("ok"));
            ses.ch.flush();
            return null;
        })) {
            try (Socket c = srv.client()) {
                tee[0] = new Tee(c.getOutputStream());
                Secure.Session ses = Secure.connect(c.getInputStream(), tee[0], "CLIENT01", "SERVER01", K, false);
                ses.ch.write(b("转账 100 元"));
                ses.ch.flush();
                assertEquals("ok", s(ses.ch.read()));
            }
            srv.join();
            assertNull(srv.err);
        }
        // 攻击者把录下来的字节原样发给一个新的服务端连接
        Secure.Session replay = Secure.accept(
                new ByteArrayInputStream(tee[0].rec.toByteArray()), new ByteArrayOutputStream(),
                "SERVER01", keys("CLIENT01", K, null));
        assertReadFails(replay.ch);
    }

    @Test
    public void 冒充的服务端把客户端的帧原样回给客户端会被发现() throws Exception {
        // 冒充者完成握手后，不解密，直接把客户端发来的第一帧原样当作"服务端回复"发回去
        try (Srv srv = new Srv(sock -> {
            Secure.accept(sock.getInputStream(), sock.getOutputStream(), "SERVER01", keys("CLIENT01", K, null));
            DataInputStream raw = new DataInputStream(sock.getInputStream());
            int len = raw.readInt();
            byte[] ct = new byte[len];
            raw.readFully(ct);
            sock.getOutputStream().write(ByteBuffer.allocate(4 + len).putInt(len).put(ct).array());
            sock.getOutputStream().flush();
            return null;
        })) {
            try (Socket c = srv.client()) {
                Secure.Session ses = Secure.connect(c.getInputStream(), c.getOutputStream(), "CLIENT01", "SERVER01", K, false);
                ses.ch.write(b("x"));
                ses.ch.flush();
                assertReadFails(ses.ch);
            }
            srv.join();
        }
    }

    @Test
    public void 非法的设备ID会被拒绝() throws Exception {
        try {
            Secure.connect(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), "bad id!", "SERVER01", K, false);
            fail("应该拒绝");
        } catch (IOException expected) {
            // 正确
        }
    }

    // ---------- 首次配对 ----------

    @Test
    public void 扫码配对后双方得到同一个密钥并能用它正常通信() throws Exception {
        final byte[] token = Secure.random(16);
        byte[] guestKey;
        byte[] hostKey;
        try (Srv srv = new Srv(sock -> {
            Secure.Session ses = Secure.accept(sock.getInputStream(), sock.getOutputStream(), "HOST0001", keys("X", K, token));
            assertTrue(ses.pairing);
            assertEquals("GUEST001", ses.peerId);
            byte[] hello = ses.ch.read(); // 能解开，说明对方持有口令
            assertEquals("guest-info", s(hello));
            ses.ch.write(b("host-info"));
            ses.ch.flush();
            return ses.pairKey();
        })) {
            try (Socket c = srv.client()) {
                Secure.Session ses = Secure.connect(c.getInputStream(), c.getOutputStream(), "GUEST001", "HOST0001", token, true);
                ses.ch.write(b("guest-info"));
                ses.ch.flush();
                assertEquals("host-info", s(ses.ch.read()));
                guestKey = ses.pairKey();
            }
            srv.join();
            assertNull(srv.err);
            hostKey = (byte[]) srv.result;
        }
        assertEquals(32, guestKey.length);
        assertArrayEquals(guestKey, hostKey);

        // 用配对得到的密钥建立普通会话
        final byte[] pk = hostKey;
        try (Srv srv = new Srv(sock -> {
            Secure.Session ses = Secure.accept(sock.getInputStream(), sock.getOutputStream(), "HOST0001", keys("GUEST001", pk, null));
            ses.ch.write(b("pong:" + s(ses.ch.read())));
            ses.ch.flush();
            return null;
        })) {
            try (Socket c = srv.client()) {
                Secure.Session ses = Secure.connect(c.getInputStream(), c.getOutputStream(), "GUEST001", "HOST0001", guestKey, false);
                ses.ch.write(b("ping"));
                ses.ch.flush();
                assertEquals("pong:ping", s(ses.ch.read()));
            }
            srv.join();
            assertNull(srv.err);
        }
    }

    @Test
    public void 口令不对的人配不上对() throws Exception {
        final byte[] token = Secure.random(16);
        try (Srv srv = new Srv(sock -> {
            Secure.Session ses = Secure.accept(sock.getInputStream(), sock.getOutputStream(), "HOST0001", keys("X", K, token));
            ses.ch.read(); // 应抛认证失败
            return "不该走到这里";
        })) {
            try (Socket c = srv.client()) {
                Secure.Session ses = Secure.connect(c.getInputStream(), c.getOutputStream(), "GUEST001", "HOST0001", Secure.random(16), true);
                ses.ch.write(b("guest-info"));
                ses.ch.flush();
                assertReadFails(ses.ch);
            }
            srv.join();
            assertTrue(srv.err instanceof IOException);
            assertNull(srv.result);
        }
    }

    @Test
    public void 没有打开二维码页面时不接受任何配对请求() throws Exception {
        try (Srv srv = new Srv(sock -> {
            Secure.accept(sock.getInputStream(), sock.getOutputStream(), "HOST0001", keys("X", K, null));
            return "不该走到这里";
        })) {
            try (Socket c = srv.client()) {
                try {
                    Secure.connect(c.getInputStream(), c.getOutputStream(), "GUEST001", "HOST0001", Secure.random(16), true);
                    fail("没有口令时不应该能握手");
                } catch (IOException expected) {
                    // 正确
                }
            }
            srv.join();
            assertTrue(srv.err instanceof IOException);
        }
    }

    @Test
    public void 二维码口令不能当作配对密钥直接建立会话() throws Exception {
        final byte[] token = Secure.random(16);
        // 服务端以"会话"模式等待，密钥是 K；攻击者拿二维码口令冒充会话 → 必须失败
        try (Srv srv = new Srv(sock -> {
            Secure.Session ses = Secure.accept(sock.getInputStream(), sock.getOutputStream(), "HOST0001", keys("GUEST001", K, token));
            ses.ch.read();
            return "不该走到这里";
        })) {
            try (Socket c = srv.client()) {
                Secure.Session ses = Secure.connect(c.getInputStream(), c.getOutputStream(), "GUEST001", "HOST0001", token, false);
                ses.ch.write(b("x"));
                ses.ch.flush();
                assertReadFails(ses.ch);
            }
            srv.join();
            assertTrue(srv.err instanceof IOException);
        }
    }
}
