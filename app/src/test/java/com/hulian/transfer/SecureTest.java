package com.hulian.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 小工具（编码、HMAC、比较）的测试 */
public class SecureTest {
    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void base64编码往返一致且没有填充符() {
        byte[] x = Secure.random(33);
        String s = Secure.b64(x);
        assertTrue(!s.contains("=") && !s.contains("+") && !s.contains("/"));
        assertTrue(Arrays.equals(x, Secure.unb64(s)));
    }

    @Test
    public void 随机数长度正确且每次不同() {
        assertEquals(16, Secure.random(16).length);
        assertTrue(!Arrays.equals(Secure.random(32), Secure.random(32)));
    }

    @Test
    public void hmac相同输入得到相同结果() {
        byte[] k = Secure.random(32);
        assertTrue(Arrays.equals(Secure.hmac(k, b("a"), b("b")), Secure.hmac(k, b("a"), b("b"))));
        assertEquals(32, Secure.hmac(k, b("a")).length);
    }

    @Test
    public void hmac分段边界不同结果就不同() {
        byte[] k = Secure.random(32);
        assertTrue(!Arrays.equals(Secure.hmac(k, b("ab"), b("c")), Secure.hmac(k, b("a"), b("bc"))));
    }

    @Test
    public void hmac密钥不同结果就不同() {
        assertTrue(!Arrays.equals(Secure.hmac(Secure.random(32), b("x")), Secure.hmac(Secure.random(32), b("x"))));
    }

    @Test
    public void 常量时间比较的结果正确() {
        assertTrue(Secure.constantTimeEquals(b("abc"), b("abc")));
        assertTrue(!Secure.constantTimeEquals(b("abc"), b("abd")));
        assertTrue(!Secure.constantTimeEquals(b("abc"), b("abcd")));
        assertTrue(!Secure.constantTimeEquals(null, b("abc")));
        assertTrue(!Secure.constantTimeEquals(b("abc"), null));
    }
}
