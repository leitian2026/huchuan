package com.hulian.transfer;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * 在 TLS 连接之上的消息分帧：[4字节长度][内容]。
 * 加密、认证、防篡改、防重放都由 TLS 负责，这一层只负责"一条消息从哪到哪"，并限制单条大小。
 */
public final class Wire {
    /** 单条消息上限（也限制了对端能让我们分配的内存） */
    public static final int MAX_FRAME = 1 << 20;
    /** 传文件时每帧的大小 */
    public static final int CHUNK = 128 * 1024;

    private final DataInputStream in;
    private final OutputStream out;
    private final Object wlock = new Object();
    private final Object rlock = new Object();

    public Wire(InputStream in, OutputStream out) {
        this.in = new DataInputStream(in);
        this.out = out;
    }

    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        if (len < 0 || len > MAX_FRAME) throw new IOException("单次数据过大");
        ByteBuffer buf = ByteBuffer.allocate(4 + len);
        buf.putInt(len).put(b, off, len);
        synchronized (wlock) {
            out.write(buf.array());
        }
    }

    public void flush() throws IOException {
        out.flush();
    }

    public byte[] read() throws IOException {
        synchronized (rlock) {
            int len = in.readInt();
            if (len < 0 || len > MAX_FRAME) throw new IOException("数据格式错误");
            byte[] b = new byte[len];
            in.readFully(b);
            return b;
        }
    }
}
