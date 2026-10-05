package com.hulian.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ErrorTextTest {
    // 来自真机截图的原始报错
    private static final String SCREENSHOT =
            "failed to connect to /192.168.15.114 (port 36903) from /192.168.15.113 (port 33110) "
                    + "after 6000ms: isConnected failed: EHOSTUNREACH (No route to host)";

    private static boolean noLatinSentence(String s) {
        return !s.contains("failed to connect") && !s.contains("EHOSTUNREACH") && !s.contains("No route");
    }

    @Test
    public void 截图里的找不到主机错误显示成中文() {
        String r = ErrorText.friendly(SCREENSHOT, "java.net.ConnectException");
        assertTrue(r.startsWith("找不到对方"));
        assertTrue(noLatinSentence(r));
    }

    @Test
    public void 没有异常类名时也能识别旧版本保存下来的英文错误() {
        assertTrue(ErrorText.friendly(SCREENSHOT, "").startsWith("找不到对方"));
    }

    @Test
    public void 对方没开互传显示拒绝连接() {
        String raw = "failed to connect to /192.168.1.5 (port 40000) after 6000ms: isConnected failed: ECONNREFUSED (Connection refused)";
        assertTrue(ErrorText.friendly(raw, "java.net.ConnectException").startsWith("对方拒绝连接"));
    }

    @Test
    public void 纯超时显示连接超时() {
        String raw = "failed to connect to /192.168.1.5 (port 40000) from /192.168.1.6 (port 1234) after 6000ms";
        assertTrue(ErrorText.friendly(raw, "java.net.SocketTimeoutException").startsWith("连接超时"));
        assertTrue(ErrorText.friendly("Read timed out", "java.net.SocketTimeoutException").startsWith("连接超时"));
    }

    @Test
    public void 连接中断显示中文() {
        assertEquals("连接被中断，请重试", ErrorText.friendly("Broken pipe", "java.net.SocketException"));
        assertEquals("连接被中断，请重试", ErrorText.friendly("Connection reset", "java.net.SocketException"));
        assertEquals("连接被中断，请重试", ErrorText.friendly(null, "java.io.EOFException"));
    }

    @Test
    public void 存储和文件相关错误显示中文() {
        assertEquals("存储空间不足", ErrorText.friendly("write failed: ENOSPC (No space left on device)", "java.io.IOException"));
        assertEquals("没有权限读取该文件", ErrorText.friendly("open failed: EACCES (Permission denied)", "java.io.FileNotFoundException"));
        assertEquals("找不到该文件，可能已被移动或删除", ErrorText.friendly("open failed: ENOENT (No such file or directory)", "java.io.FileNotFoundException"));
    }

    @Test
    public void 我们自己写的中文提示原样保留() {
        assertEquals("尚未配对，请扫码重新配对", ErrorText.friendly("尚未配对，请扫码重新配对", "java.io.IOException"));
        assertEquals("对方存储空间不足，需要 2.0 GB", ErrorText.friendly("对方存储空间不足，需要 2.0 GB", "java.io.IOException"));
    }

    @Test
    public void 不认识的错误也不会显示成英文长句() {
        String r = ErrorText.friendly("something weird happened internally", "java.lang.IllegalStateException");
        assertEquals("未知错误，请重试（IllegalStateException）", r);
        assertEquals("未知错误，请重试", ErrorText.friendly("", ""));
        assertEquals("未知错误，请重试", ErrorText.friendly(null, null));
    }
}
