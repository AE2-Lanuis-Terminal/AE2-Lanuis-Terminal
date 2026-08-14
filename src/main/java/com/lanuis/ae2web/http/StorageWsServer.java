package com.lanuis.ae2web.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lanuis.ae2web.Ae2LanuisMod;
import com.lanuis.ae2web.ae2.Ae2QueryService;
import com.lanuis.ae2web.auth.BindingStore;
import com.lanuis.ae2web.auth.SessionStore;
import com.lanuis.ae2web.config.ModConfig;
import com.lanuis.ae2web.util.MainThreadExecutor;
import net.minecraft.server.MinecraftServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 独立端口 WebSocket：鉴权后订阅 storage，按服务端 {@code pushIntervalMs} 推送分页 snapshot。
 * <p>
 * 间隔只读 Forge 配置，客户端不可改。按 contentRevision 跳过无变化推送；
 * 相同玩家+订阅参数的客户端合并为一次主线程查询。
 * </p>
 */
public final class StorageWsServer {
    private static final Gson GSON = new Gson();
    private static final AtomicLong REVISION = new AtomicLong(0);

    private static volatile ServerImpl server;
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> pushTask;
    private static MinecraftServer minecraftServer;

    private StorageWsServer() {
    }

    public static void start(MinecraftServer mc) {
        start(mc, null);
    }

    /**
     * @param bindAddr 非空则绑到该地址（同端口模式下为本机回环临时端口）；空则用配置的 bind + effective port
     */
    public static void start(MinecraftServer mc, InetSocketAddress bindAddr) {
        stop();
        minecraftServer = mc;
        if (!Boolean.TRUE.equals(ModConfig.WS_ENABLED.get())) {
            Ae2LanuisMod.LOGGER.info("AE2 Lanuis WebSocket disabled by config");
            return;
        }
        InetSocketAddress addr = bindAddr != null
                ? bindAddr
                : new InetSocketAddress(ModConfig.BIND_ADDRESS.get(), ModConfig.effectiveWebSocketPort());
        try {
            server = new ServerImpl(addr);
            server.setReuseAddr(true);
            server.start();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ae2lanuis-ws-push");
                t.setDaemon(true);
                return t;
            });
            long interval = ModConfig.WS_PUSH_INTERVAL_MS.get();
            pushTask = scheduler.scheduleAtFixedRate(StorageWsServer::tickPush, interval, interval, TimeUnit.MILLISECONDS);
            Ae2LanuisMod.LOGGER.info(
                    "AE2 Lanuis WebSocket listening on {}:{} (pushIntervalMs={}, publicPort={})",
                    addr.getHostString(),
                    server.getPort(),
                    interval,
                    ModConfig.effectiveWebSocketPort());
        } catch (Exception e) {
            Ae2LanuisMod.LOGGER.error("Failed to start WebSocket server", e);
            stop();
        }
    }

    /** 实际绑定端口（临时端口时与配置对外端口不同）。 */
    public static int getBoundPort() {
        ServerImpl s = server;
        return s != null ? s.getPort() : -1;
    }

    public static void stop() {
        if (pushTask != null) {
            pushTask.cancel(false);
            pushTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (server != null) {
            try {
                server.stop(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Ae2LanuisMod.LOGGER.debug("WS stop: {}", e.toString());
            }
            server = null;
        }
        minecraftServer = null;
        Ae2QueryService.clearInventoryCaches();
    }

    /** health / 前端发现用（port 为对外端口，与 HTTP 相同时前端连同一端口即可） */
    public static JsonObject statusJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", Boolean.TRUE.equals(ModConfig.WS_ENABLED.get()));
        o.addProperty("port", ModConfig.effectiveWebSocketPort());
        o.addProperty("pushIntervalMs", ModConfig.WS_PUSH_INTERVAL_MS.get());
        o.addProperty("running", server != null);
        o.addProperty("sameAsHttp", ModConfig.webSocketSharesHttpPort());
        return o;
    }

    private static void tickPush() {
        ServerImpl s = server;
        MinecraftServer mc = minecraftServer;
        if (s == null || mc == null) {
            return;
        }

        // 相同绑定玩家 + 订阅参数 → 只查一次，再 fan-out
        Map<String, List<ClientRef>> groups = new LinkedHashMap<>();
        for (Map.Entry<WebSocket, ClientState> e : s.clients.entrySet()) {
            WebSocket conn = e.getKey();
            ClientState st = e.getValue();
            if (!conn.isOpen() || st.session == null || st.subscription == null) {
                continue;
            }
            String key = groupKey(st.session.effectiveUuid(), st.subscription);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new ClientRef(conn, st));
        }

        for (List<ClientRef> group : groups.values()) {
            if (group.isEmpty()) {
                continue;
            }
            try {
                pushGroup(group, false);
            } catch (Exception ex) {
                Ae2LanuisMod.LOGGER.debug("WS push failed: {}", ex.toString());
            }
        }
    }

    private static String groupKey(UUID playerUuid, StorageSub sub) {
        return playerUuid + "|" + sub.q + "|" + sub.kind + "|" + sub.filter + "|"
                + sub.sort + "|" + sub.order + "|" + sub.page + "|" + sub.pageSize;
    }

    private static void pushGroup(List<ClientRef> group, boolean force) throws Exception {
        MinecraftServer mc = minecraftServer;
        if (mc == null || group.isEmpty()) {
            return;
        }
        ClientState sample = group.get(0).state;
        if (sample.session == null || sample.subscription == null) {
            return;
        }
        SessionStore.Session sess = sample.session;
        StorageSub sub = sample.subscription;

        // 收集各客户端已推送 revision，供主线程一次判断是否整组跳过
        long[] knownRevs = new long[group.size()];
        for (int i = 0; i < group.size(); i++) {
            knownRevs[i] = group.get(i).state.lastContentRevision;
        }

        JsonObject payload = MainThreadExecutor.call(mc, 8_000, () -> {
            Optional<BindingStore.BindingRecord> binding = BindingStore.findByUuid(sess.effectiveUuid());
            if (binding.isEmpty() || binding.get().networkLink == null) {
                throw new IllegalStateException("no_network_binding");
            }
            var gridOpt = Ae2QueryService.gridForBinding(mc, binding.get());
            if (gridOpt.isEmpty()) {
                throw new IllegalStateException("network_unavailable");
            }
            return Ae2QueryService.inventory(
                    gridOpt.get(),
                    sub.q,
                    sub.kind,
                    sub.filter,
                    sub.sort,
                    sub.order,
                    sub.page,
                    sub.pageSize,
                    force,
                    knownRevs
            );
        });

        if (payload == null) {
            return;
        }

        long contentRev = payload.has("contentRevision")
                ? payload.get("contentRevision").getAsLong()
                : 0L;

        long wireRev = REVISION.incrementAndGet();
        String json = null;
        for (ClientRef ref : group) {
            if (!force && ref.state.lastContentRevision == contentRev) {
                continue;
            }
            ref.state.lastContentRevision = contentRev;
            if (json == null) {
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "storage.snapshot");
                msg.addProperty("revision", wireRev);
                msg.addProperty("pushIntervalMs", ModConfig.WS_PUSH_INTERVAL_MS.get());
                msg.add("page", payload.get("page"));
                msg.add("pageSize", payload.get("pageSize"));
                msg.add("total", payload.get("total"));
                msg.add("items", payload.get("items"));
                msg.add("network", payload.get("network"));
                if (payload.has("contentRevision")) {
                    msg.add("contentRevision", payload.get("contentRevision"));
                }
                json = GSON.toJson(msg);
            }
            if (ref.conn.isOpen()) {
                ref.conn.send(json);
            }
        }
    }

    private static void pushSnapshot(WebSocket conn, ClientState st, boolean force) throws Exception {
        pushGroup(List.of(new ClientRef(conn, st)), force);
    }

    private record ClientRef(WebSocket conn, ClientState state) {
    }

    private static final class StorageSub {
        String q = "";
        String kind = "all";
        String filter = "all";
        String sort = "name";
        String order = "asc";
        int page = 1;
        int pageSize = 96;
    }

    private static final class ClientState {
        SessionStore.Session session;
        StorageSub subscription;
        /** 上次已推送的网格内容 revision；MIN_VALUE 表示尚未推送。 */
        long lastContentRevision = Long.MIN_VALUE;
    }

    private static final class ServerImpl extends WebSocketServer {
        final Map<WebSocket, ClientState> clients = new ConcurrentHashMap<>();

        ServerImpl(InetSocketAddress addr) {
            super(addr);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            clients.put(conn, new ClientState());
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "hello");
            hello.addProperty("service", Ae2LanuisMod.SERVICE_NAME);
            hello.addProperty("pushIntervalMs", ModConfig.WS_PUSH_INTERVAL_MS.get());
            conn.send(GSON.toJson(hello));
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            clients.remove(conn);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            ClientState st = clients.get(conn);
            if (st == null) {
                return;
            }
            try {
                JsonObject body = JsonParser.parseString(message).getAsJsonObject();
                String type = body.has("type") ? body.get("type").getAsString() : "";
                switch (type) {
                    case "auth" -> handleAuth(conn, st, body);
                    case "subscribe" -> handleSubscribe(conn, st, body);
                    case "unsubscribe" -> {
                        st.subscription = null;
                        st.lastContentRevision = Long.MIN_VALUE;
                        sendOk(conn, "unsubscribed");
                    }
                    case "ping" -> {
                        JsonObject pong = new JsonObject();
                        pong.addProperty("type", "pong");
                        conn.send(GSON.toJson(pong));
                    }
                    default -> sendError(conn, "bad_request", "Unknown type: " + type);
                }
            } catch (Exception e) {
                sendError(conn, "bad_request", e.getMessage() == null ? "invalid message" : e.getMessage());
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            Ae2LanuisMod.LOGGER.debug("WS error: {}", ex.toString());
        }

        @Override
        public void onStart() {
            // no-op
        }

        private void handleAuth(WebSocket conn, ClientState st, JsonObject body) {
            String token = body.has("token") ? body.get("token").getAsString() : "";
            Optional<SessionStore.Session> sess = SessionStore.get(token);
            if (sess.isEmpty()) {
                sendError(conn, "unauthorized", "Invalid or expired token");
                conn.close(4001, "unauthorized");
                return;
            }
            st.session = sess.get();
            JsonObject ok = new JsonObject();
            ok.addProperty("type", "auth_ok");
            ok.addProperty("account", sess.get().playerName());
            ok.addProperty("pushIntervalMs", ModConfig.WS_PUSH_INTERVAL_MS.get());
            conn.send(GSON.toJson(ok));
        }

        private void handleSubscribe(WebSocket conn, ClientState st, JsonObject body) {
            if (st.session == null) {
                sendError(conn, "unauthorized", "Auth required");
                return;
            }
            String channel = body.has("channel") ? body.get("channel").getAsString() : "storage";
            if (!"storage".equals(channel)) {
                sendError(conn, "bad_request", "Only channel=storage supported");
                return;
            }
            StorageSub sub = new StorageSub();
            if (body.has("q")) {
                sub.q = body.get("q").getAsString();
            }
            if (body.has("kind")) {
                sub.kind = body.get("kind").getAsString();
            }
            if (body.has("filter")) {
                sub.filter = body.get("filter").getAsString();
            }
            if (body.has("sort")) {
                sub.sort = body.get("sort").getAsString();
            }
            if (body.has("order")) {
                sub.order = body.get("order").getAsString();
            }
            if (body.has("page")) {
                sub.page = Math.max(1, body.get("page").getAsInt());
            }
            int maxPage = ModConfig.MAX_INVENTORY_PAGE_SIZE.get();
            if (body.has("pageSize")) {
                sub.pageSize = Math.min(Math.max(1, body.get("pageSize").getAsInt()), maxPage);
            }
            st.subscription = sub;
            st.lastContentRevision = Long.MIN_VALUE;
            try {
                pushSnapshot(conn, st, true);
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "subscribe failed" : e.getMessage();
                if (msg.contains("network_unavailable")) {
                    sendError(conn, "network_unavailable", "Cannot resolve AE network");
                } else if (msg.contains("no_network_binding")) {
                    sendError(conn, "no_network_binding", "Re-run /ae2lanuis password in-game");
                } else {
                    sendError(conn, "internal", msg);
                }
            }
        }

        private void sendOk(WebSocket conn, String detail) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "ok");
            o.addProperty("detail", detail);
            conn.send(GSON.toJson(o));
        }

        private void sendError(WebSocket conn, String code, String message) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "error");
            o.addProperty("code", code);
            o.addProperty("message", message);
            conn.send(GSON.toJson(o));
        }
    }
}
