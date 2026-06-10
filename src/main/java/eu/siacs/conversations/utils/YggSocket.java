package eu.siacs.conversations.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Wraps a yggmobile.YggConn (Go TCP connection over Yggdrasil overlay)
 * as a java.net.Socket so it can be used in the SOCKS5 proxy handler.
 */
public class YggSocket extends Socket {

    private final yggmobile.YggConn yggConn;
    private final YggInputStream inputStream;
    private final YggOutputStream outputStream;
    private boolean closed = false;

    public YggSocket(yggmobile.YggConn conn) {
        this.yggConn = conn;
        this.inputStream = new YggInputStream(conn);
        this.outputStream = new YggOutputStream(conn);
    }

    @Override
    public InputStream getInputStream() {
        return inputStream;
    }

    @Override
    public OutputStream getOutputStream() {
        return outputStream;
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try { yggConn.close(); } catch (Exception e) { throw new IOException(e); }
        }
    }

    @Override
    public boolean isClosed() { return closed; }

    @Override
    public boolean isConnected() { return !closed; }

    @Override
    public void setSoTimeout(int timeout) { /* no-op */ }

    // ── Inner streams ────────────────────────────────────────────────────────

    private static class YggInputStream extends InputStream {
        private final yggmobile.YggConn conn;
        private byte[] pending = null;
        private int pendingOffset = 0;

        YggInputStream(yggmobile.YggConn conn) { this.conn = conn; }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n == -1 ? -1 : (b[0] & 0xFF);
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (pending != null && pendingOffset < pending.length) {
                int n = Math.min(len, pending.length - pendingOffset);
                System.arraycopy(pending, pendingOffset, buf, off, n);
                pendingOffset += n;
                if (pendingOffset >= pending.length) { pending = null; pendingOffset = 0; }
                return n;
            }
            try {
                // gomobile: Read([]byte) (int, error) -> read(byte[]) throws Exception
                byte[] tmp = new byte[len];
                long n = conn.read(tmp);
                if (n <= 0) return -1;
                System.arraycopy(tmp, 0, buf, off, (int) n);
                return (int) n;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    private static class YggOutputStream extends OutputStream {
        private final yggmobile.YggConn conn;

        YggOutputStream(yggmobile.YggConn conn) { this.conn = conn; }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b});
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            byte[] data = new byte[len];
            System.arraycopy(buf, off, data, 0, len);
            try {
                // gomobile: Write([]byte) (int, error) -> write(byte[]) throws Exception
                conn.write(data);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }
}
