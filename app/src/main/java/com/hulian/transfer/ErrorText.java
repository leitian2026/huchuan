package com.hulian.transfer;

/** 把系统抛出的英文网络/文件异常翻成中文提示（纯 Java，便于测试）。我们自己写的中文提示原样保留。 */
public final class ErrorText {
    private ErrorText() {}

    private static boolean has(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') return true;
        }
        return false;
    }

    /** @param raw 异常信息（可为 null）；@param exClass 异常类名（可为空） */
    public static String friendly(String raw, String exClass) {
        if (raw == null) raw = "";
        if (exClass == null) exClass = "";
        if (hasCjk(raw)) return raw;

        if (has(raw, "EHOSTUNREACH", "No route to host") || exClass.endsWith("NoRouteToHostException")) {
            return "找不到对方：对方可能已离开当前网络、关闭了 Wi-Fi，或 IP 地址已变化。请确认两台手机在同一网络后重试";
        }
        if (has(raw, "ECONNREFUSED", "Connection refused")) {
            return "对方拒绝连接：对方可能没有打开互传，或互传已被系统在后台关闭。请让对方打开互传后重试";
        }
        if (has(raw, "ENETUNREACH", "Network is unreachable")) {
            return "当前网络不可用，请检查 Wi-Fi 或热点连接";
        }
        if (exClass.endsWith("SocketTimeoutException") || has(raw, "ETIMEDOUT", "timed out", "failed to connect")) {
            return "连接超时：对方没有响应。请确认对方在线，并且两台手机在同一网络";
        }
        if (has(raw, "EPIPE", "Broken pipe", "ECONNRESET", "Connection reset", "ECONNABORTED",
                "Software caused connection abort") || exClass.endsWith("EOFException")) {
            return "连接被中断，请重试";
        }
        if (has(raw, "ENOSPC", "No space left")) {
            return "存储空间不足";
        }
        if (has(raw, "EACCES", "Permission denied") || exClass.endsWith("SecurityException")) {
            return "没有权限读取该文件";
        }
        if (has(raw, "ENOENT", "No such file") || exClass.endsWith("FileNotFoundException")) {
            return "找不到该文件，可能已被移动或删除";
        }
        if (exClass.endsWith("UnknownHostException")) {
            return "找不到对方的地址，请删除该设备后重新扫码配对";
        }
        if (raw.isEmpty() && exClass.isEmpty()) return "未知错误，请重试";
        int dot = exClass.lastIndexOf('.');
        String shortName = dot >= 0 ? exClass.substring(dot + 1) : exClass;
        return shortName.isEmpty() ? "未知错误，请重试" : "未知错误，请重试（" + shortName + "）";
    }
}
