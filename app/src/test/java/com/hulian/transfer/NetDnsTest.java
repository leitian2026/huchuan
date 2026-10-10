package com.hulian.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.util.List;
import org.junit.Test;

public class NetDnsTest {
    @Test
    public void 认空格_等号_逗号_和Clash写法() {
        String t = "a.example.com 1.2.3.4\n"
                + "b.example.com=5.6.7.8,9.9.9.9\n"
                + "c.example.com: 10.0.0.1  # 注释\n"
                + "# 整行注释\n"
                + "\n";
        NetDns.Parsed p = NetDns.INSTANCE.parseHosts(t);
        assertNull(p.getError());
        assertEquals(3, p.getMap().size());
        assertEquals(1, p.getMap().get("a.example.com").size());
        assertEquals(2, p.getMap().get("b.example.com").size());
        assertEquals("10.0.0.1", p.getMap().get("c.example.com").get(0).getHostAddress());
    }

    @Test
    public void 域名统一小写_同一域名多行合并() {
        NetDns.Parsed p = NetDns.INSTANCE.parseHosts("A.Example.com 1.1.1.1\na.example.com 2.2.2.2");
        List<InetAddress> l = p.getMap().get("a.example.com");
        assertNotNull(l);
        assertEquals(2, l.size());
    }

    @Test
    public void 支持IPv6() {
        NetDns.Parsed p = NetDns.INSTANCE.parseHosts("x.example.com 2606:4700:83b4:fd7d:d6b1:a9ce:9c52:416d");
        assertNull(p.getError());
        assertEquals(1, p.getMap().get("x.example.com").size());
        assertTrue(p.getMap().get("x.example.com").get(0).getAddress().length == 16);
    }

    @Test
    public void 错误的行报出行号并被跳过() {
        NetDns.Parsed p = NetDns.INSTANCE.parseHosts("ok.example.com 1.1.1.1\nbad.example.com\nbad2.example.com not-an-ip");
        assertEquals(1, p.getMap().size());
        assertTrue(p.getError().startsWith("Host 映射第 2 行"));
    }

    @Test
    public void IP位置填了域名要报错_不会去查DNS() {
        NetDns.Parsed p = NetDns.INSTANCE.parseHosts("a.example.com b.example.com");
        assertTrue(p.getMap().isEmpty());
        assertTrue(p.getError().contains("IP 格式不对"));
    }

    @Test
    public void 握手被掐的错误提示不再说成配对身份不一致() {
        Throwable t = new javax.net.ssl.SSLHandshakeException("connection closed");
        String r = NetDnsKt.netHint(t);
        assertNotNull(r);
        assertTrue(r.contains("和设备配对无关"));
    }

    @Test
    public void 非网络类错误返回null() {
        assertNull(NetDnsKt.netHint(new java.io.IOException("口令不对")));
    }
}
