package com.hulian.transfer;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class UrlRulesTest {
    @Test
    public void 中转地址_明文协议被拒_https和不写协议通过() {
        assertNotNull(UrlRules.INSTANCE.relayError("http://x.example.com"));
        assertNotNull(UrlRules.INSTANCE.relayError("WS://x.example.com"));
        assertNotNull(UrlRules.INSTANCE.relayError("ftp://x.example.com"));
        assertNull(UrlRules.INSTANCE.relayError("https://x.example.com"));
        assertNull(UrlRules.INSTANCE.relayError("wss://x.example.com"));
        assertNull(UrlRules.INSTANCE.relayError("x.example.com"));
        assertNull(UrlRules.INSTANCE.relayError(""));
    }

    @Test
    public void 网盘地址_必须https() {
        assertNotNull(UrlRules.INSTANCE.davError("http://dav.example.com/dav/", "坚果云"));
        assertNotNull(UrlRules.INSTANCE.davError("dav.example.com/dav/", "坚果云"));
        assertNull(UrlRules.INSTANCE.davError("https://dav.jianguoyun.com/dav/", "坚果云"));
        assertNull(UrlRules.INSTANCE.davError("", "坚果云"));
    }
}
