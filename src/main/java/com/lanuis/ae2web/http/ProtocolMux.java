package com.lanuis.ae2web.http;

import com.lanuis.ae2web.Ae2LanuisMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单端口协议分流：根据首包 HTTP 头是否含 {@code Upgrade: websocket}，
 * 把连接转到本机 HTTP 或 WebSocket 后端（二者绑 127.0.0.1 临时端口）。
 */
public final class ProtocolMux implements AutoCloseable {
    private static final int HEADER_LIMIT = 16 * 1024;

    private final ServerSocket listen;
    private final InetSocketAddress httpBackend;
    private final InetSocketAddress wsBackend;
    private final ExecutorService acceptPool;
    private final ExecutorService pipePool;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private ProtocolMux(ServerSocket listen, InetSocketAddress httpBackend, InetSocketAddress wsBackend) {
        this.listen = listen;
        this.httpBackend = httpBackend;
        this.wsBackend = wsBackend;
        this.acceptPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ae2lanuis-mux-accept");
            t.setDaemon(true);
            return t;
        });
        this.pipePool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ae2lanuis-mux-pipe");
            t.setDaemon(true);
            return t;
        });
        this.acceptPool.execute(this::acceptLoop);
    }

    public static ProtocolMux start(InetSocketAddress publicAddr, InetSocketAddress httpBackend, InetSocketAddress wsBackend)
            throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(publicAddr);
        return new ProtocolMux(ss, httpBackend, wsBackend);
    }

    public int getLocalPort() {
        return listen.getLocalPort();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = listen.accept();
                pipePool.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running.get()) {
                    Ae2LanuisMod.LOGGER.debug("mux accept: {}", e.toString());
                }
                break;
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            HeaderPeek peek = readHeaders(in);
            InetSocketAddress backend = peek.websocketUpgrade ? wsBackend : httpBackend;
            Socket upstream = new Socket();
            upstream.setTcpNoDelay(true);
            upstream.connect(backend, 5000);
            OutputStream upOut = upstream.getOutputStream();
            upOut.write(peek.buffer, 0, peek.length);
            upOut.flush();
            pipePool.execute(() -> copyQuiet(in, upOut, client, upstream));
            copyQuiet(upstream.getInputStream(), client.getOutputStream(), upstream, client);
        } catch (Exception e) {
            Ae2LanuisMod.LOGGER.debug("mux client: {}", e.toString());
            closeQuiet(client);
        }
    }

    private static HeaderPeek readHeaders(InputStream in) throws IOException {
        byte[] buf = new byte[HEADER_LIMIT];
        int n = 0;
        while (n < HEADER_LIMIT) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            buf[n++] = (byte) b;
            if (n >= 4
                    && buf[n - 4] == '\r'
                    && buf[n - 3] == '\n'
                    && buf[n - 2] == '\r'
                    && buf[n - 1] == '\n') {
                break;
            }
        }
        String head = new String(buf, 0, n, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        boolean ws = head.contains("\r\nupgrade: websocket");
        return new HeaderPeek(buf, n, ws);
    }

    private static void copyQuiet(InputStream in, OutputStream out, Socket... closeAll) {
        byte[] buf = new byte[16 * 1024];
        try {
            int r;
            while ((r = in.read(buf)) >= 0) {
                if (r == 0) {
                    continue;
                }
                out.write(buf, 0, r);
                out.flush();
            }
        } catch (IOException ignored) {
            // peer closed
        } finally {
            for (Socket s : closeAll) {
                closeQuiet(s);
            }
        }
    }

    private static void closeQuiet(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            listen.close();
        } catch (IOException ignored) {
            // ignore
        }
        acceptPool.shutdownNow();
        pipePool.shutdownNow();
        try {
            acceptPool.awaitTermination(1, TimeUnit.SECONDS);
            pipePool.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record HeaderPeek(byte[] buffer, int length, boolean websocketUpgrade) {
    }
}
