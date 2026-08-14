package com.lanuis.ae2web.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lanuis.ae2web.Ae2LanuisMod;
import com.lanuis.ae2web.ae2.AdminBindingService;
import com.lanuis.ae2web.ae2.Ae2PatternBoardService;
import com.lanuis.ae2web.ae2.Ae2PatternService;
import com.lanuis.ae2web.ae2.Ae2QueryService;
import com.lanuis.ae2web.audit.AuditLogStore;
import com.lanuis.ae2web.auth.AdminAccess;
import com.lanuis.ae2web.auth.BindingStore;
import com.lanuis.ae2web.auth.SessionStore;
import com.lanuis.ae2web.config.ModConfig;
import com.lanuis.ae2web.icon.IconResourcePaths;
import com.lanuis.ae2web.icon.IconResourceStore;
import com.lanuis.ae2web.util.MainThreadExecutor;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * 内嵌 JDK {@link HttpServer} 生命周期与 {@code /api/v1} 路由实现。
 * <p>
 * 在独立守护线程池中处理请求；触及 AE2/世界时一律经 {@link MainThreadExecutor}
 * 切回 Minecraft 主线程。鉴权依赖 Bearer token 与 BindingStore 中的 networkLink。
 * 可选挂载 classpath {@code /web} 静态资源，未知前端路由回退到 index.html（SPA）。
 * </p>
 * <p>
 * 安全注意：默认 CORS 放行 {@code *}，适合局域网终端；公网部署应前置反代并收紧策略。
 * </p>
 */
public final class HttpServerLifecycle {
    /** 响应序列化；请求体解析用 JsonParser 以容忍空 body。 */
    private static final Gson GSON = new Gson();
    /** 当前监听实例；stop 后置 null。 */
    private static HttpServer server;
    /** 同端口模式下的对外分流器。 */
    private static ProtocolMux mux;
    /** 启动时保存的 MC 服务端引用，供主线程桥接使用。 */
    private static MinecraftServer minecraftServer;

    /**
     * 工具类禁止实例化。
     */
    private HttpServerLifecycle() {
    }

    /**
     * 启动 HTTP：先 stop 清旧实例，再 init 绑定存储，按配置决定是否监听。
     * 绑定失败只记日志，不抛到 Forge 事件，避免拖垮服务器启动。
     *
     * @param mc 正在启动的 Minecraft 服务端
     */
    public static void start(MinecraftServer mc) {
        // 热重载/重复事件：先确保旧端口释放
        stop();
        minecraftServer = mc;
        BindingStore.init(mc);
        AuditLogStore.init(mc);
        boolean httpOn = Boolean.TRUE.equals(ModConfig.HTTP_ENABLED.get());
        boolean wsOn = Boolean.TRUE.equals(ModConfig.WS_ENABLED.get());
        boolean share = ModConfig.webSocketSharesHttpPort();

        if (!httpOn) {
            Ae2LanuisMod.LOGGER.info("AE2 Lanuis HTTP disabled by config");
            if (wsOn && !share) {
                StorageWsServer.start(mc);
            } else if (wsOn) {
                Ae2LanuisMod.LOGGER.warn("WebSocket same-as-http requires HTTP enabled; WS not started");
            }
            return;
        }

        try {
            String bind = ModConfig.BIND_ADDRESS.get();
            int publicPort = ModConfig.PORT.get();
            if (share && wsOn) {
                // HTTP/WS 均绑本机回环临时端口，对外由 ProtocolMux 单端口分流
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                registerContexts(server);
                server.setExecutor(httpExecutor());
                server.start();
                int httpLocal = server.getAddress().getPort();
                StorageWsServer.start(mc, new InetSocketAddress("127.0.0.1", 0));
                int wsLocal = StorageWsServer.getBoundPort();
                if (wsLocal <= 0) {
                    throw new IOException("WebSocket backend failed to bind");
                }
                mux = ProtocolMux.start(
                        new InetSocketAddress(bind, publicPort),
                        new InetSocketAddress("127.0.0.1", httpLocal),
                        new InetSocketAddress("127.0.0.1", wsLocal));
                Ae2LanuisMod.LOGGER.info(
                        "AE2 Lanuis HTTP+WS on {}:{} (mux; httpLocal={} wsLocal={})",
                        bind,
                        publicPort,
                        httpLocal,
                        wsLocal);
            } else {
                server = HttpServer.create(new InetSocketAddress(bind, publicPort), 0);
                registerContexts(server);
                server.setExecutor(httpExecutor());
                server.start();
                Ae2LanuisMod.LOGGER.info("AE2 Lanuis HTTP listening on {}:{}", bind, publicPort);
                if (wsOn) {
                    StorageWsServer.start(mc);
                }
            }
            if ("0.0.0.0".equals(bind)) {
                Ae2LanuisMod.LOGGER.warn("HTTP bound to 0.0.0.0 — ensure firewall/reverse-proxy is intentional");
            }
        } catch (IOException e) {
            Ae2LanuisMod.LOGGER.error("Failed to start HTTP server", e);
            stop();
        }
    }

    private static void registerContexts(HttpServer http) {
        http.createContext("/api/v1/health", HttpServerLifecycle::health);
        http.createContext("/api/v1/auth/login", HttpServerLifecycle::login);
        http.createContext("/api/v1/auth/logout", HttpServerLifecycle::logout);
        http.createContext("/api/v1/auth/session", HttpServerLifecycle::session);
        http.createContext("/api/v1/items", HttpServerLifecycle::items);
        http.createContext("/api/v1/pattern-providers", HttpServerLifecycle::patternProviders);
        http.createContext("/api/v1/patterns/move", HttpServerLifecycle::patternsMove);
        http.createContext("/api/v1/patterns", HttpServerLifecycle::patterns);
        http.createContext("/api/v1/crafting/catalog", HttpServerLifecycle::catalog);
        http.createContext("/api/v1/crafting/plan", HttpServerLifecycle::plan);
        http.createContext("/api/v1/crafting/submit", HttpServerLifecycle::submit);
        http.createContext("/api/v1/crafting/jobs", HttpServerLifecycle::jobs);
        http.createContext("/api/v1/crafting/cancel", HttpServerLifecycle::cancel);
        http.createContext("/api/v1/icons", HttpServerLifecycle::icons);
        http.createContext("/api/v1/admin/bindings", HttpServerLifecycle::adminBindings);
        http.createContext("/api/v1/admin/session/act-as", HttpServerLifecycle::adminActAs);
        http.createContext("/api/v1/admin/session/clear-act-as", HttpServerLifecycle::adminClearActAs);
        http.createContext("/api/v1/admin/audit", HttpServerLifecycle::adminAudit);
        if (Boolean.TRUE.equals(ModConfig.STATIC_WEB_ENABLED.get())) {
            http.createContext("/", HttpServerLifecycle::staticOrFallback);
        }
    }

    private static java.util.concurrent.Executor httpExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "ae2lanuis-http");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 立即停止监听（delay=0）并清空 MC 引用。
     * 可重复调用；未启动时为 no-op。
     */
    public static void stop() {
        StorageWsServer.stop();
        if (mux != null) {
            mux.close();
            mux = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
            Ae2LanuisMod.LOGGER.info("AE2 Lanuis HTTP stopped");
        }
        minecraftServer = null;
    }

    /** 游戏内 /ae2lanuis http 用。 */
    public static String statusSummary() {
        boolean enabled = Boolean.TRUE.equals(ModConfig.HTTP_ENABLED.get());
        boolean running = server != null;
        String bind = ModConfig.BIND_ADDRESS.get();
        int port = ModConfig.PORT.get();
        boolean staticWeb = Boolean.TRUE.equals(ModConfig.STATIC_WEB_ENABLED.get());
        String icons = ModConfig.ICON_RESOURCES_DIR.get();
        return "HTTP enabled=" + enabled
                + " running=" + running
                + " bind=" + bind
                + " port=" + port
                + " staticWeb=" + staticWeb
                + " iconDir=" + icons
                + " wsPort=" + ModConfig.effectiveWebSocketPort()
                + " wsSameAsHttp=" + ModConfig.webSocketSharesHttpPort();
    }

    /**
     * 健康检查：无需鉴权，返回服务名、版本与当前会话数。
     */
    private static void health(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "GET only");
            return;
        }
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("service", Ae2LanuisMod.SERVICE_NAME);
        root.addProperty("version", Ae2LanuisMod.VERSION);
        root.addProperty("sessions", SessionStore.size());
        root.add("websocket", StorageWsServer.statusJson());
        sendJson(ex, 200, root);
    }

    /**
     * 登录：校验账号绑定与密码，发放会话 token。
     * 无绑定 / 错密 / 缺 networkLink 均返回 401，错误码区分前端提示。
     */
    private static void login(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "POST only");
            return;
        }
        JsonObject body = readJson(ex);
        String account = body.has("account") ? body.get("account").getAsString() : "";
        String password = body.has("password") ? body.get("password").getAsString() : "";
        boolean remember = body.has("remember") && body.get("remember").getAsBoolean();

        Optional<BindingStore.BindingRecord> binding = BindingStore.findByAccount(account);
        if (binding.isEmpty()) {
            // 未设密：引导玩家进游戏执行命令
            sendError(ex, 401, "password_not_set", "No binding for this account");
            return;
        }
        if (!BindingStore.verifyPassword(binding.get(), password)) {
            sendError(ex, 401, "unauthorized", "Invalid credentials");
            return;
        }
        if (binding.get().networkLink == null) {
            // 有密码哈希但链接丢失（手工删 JSON 字段等）
            sendError(ex, 401, "no_network_binding", "Network link missing; run /ae2lanuis password again");
            return;
        }

        var session = SessionStore.create(
                java.util.UUID.fromString(binding.get().playerUuid),
                binding.get().playerName,
                remember
        );
        boolean admin = AdminAccess.isAdmin(minecraftServer, session.playerUuid());
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("account", binding.get().playerName);
        root.addProperty("displayName", binding.get().playerName);
        root.addProperty("token", session.token());
        root.addProperty("admin", admin);
        AuditLogStore.append(
                "login",
                session.playerUuid(),
                session.playerName(),
                null,
                null,
                admin ? "admin=true" : "admin=false"
        );
        sendJson(ex, 200, root);
    }

    /**
     * 注销：删除 Bearer 对应会话；幂等（无效 token 也返回 ok）。
     */
    private static void logout(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "POST only");
            return;
        }
        String token = bearer(ex);
        var sess = SessionStore.get(token);
        if (sess.isPresent()) {
            AuditLogStore.append(
                    "logout",
                    sess.get().playerUuid(),
                    sess.get().playerName(),
                    sess.get().actingAsUuid(),
                    null,
                    null
            );
        }
        SessionStore.remove(token);
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        sendJson(ex, 200, root);
    }

    /**
     * 会话探测：未登录仍 200，authenticated=false，便于前端路由守卫。
     */
    private static void session(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "GET only");
            return;
        }
        var sess = SessionStore.get(bearer(ex));
        JsonObject root = new JsonObject();
        if (sess.isEmpty()) {
            root.addProperty("authenticated", false);
            sendJson(ex, 200, root);
            return;
        }
        root.addProperty("authenticated", true);
        root.addProperty("account", sess.get().playerName());
        boolean admin = minecraftServer != null && AdminAccess.isAdmin(minecraftServer, sess.get().playerUuid());
        root.addProperty("admin", admin);
        appendActingAs(root, sess.get());
        sendJson(ex, 200, root);
    }

    private static void appendActingAs(JsonObject root, SessionStore.Session sess) {
        if (!sess.isActingAs()) {
            return;
        }
        JsonObject as = new JsonObject();
        as.addProperty("playerUuid", sess.actingAsUuid().toString());
        var binding = BindingStore.findByUuid(sess.actingAsUuid());
        String name = binding.map(b -> b.playerName).orElse("");
        as.addProperty("playerName", name == null ? "" : name);
        root.add("actingAs", as);
    }

    /**
     * 管理员切入目标玩家绑定（进入其 ME 用户端）。
     */
    private static void adminActAs(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            Headers h = ex.getResponseHeaders();
            cors(h);
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "POST only");
            return;
        }
        var sess = SessionStore.get(bearer(ex));
        if (sess.isEmpty()) {
            sendError(ex, 401, "unauthorized", "Login required");
            return;
        }
        if (minecraftServer == null) {
            sendError(ex, 503, "not_ready", "Server not ready");
            return;
        }
        JsonObject body = readJson(ex);
        String targetRaw = body.has("playerUuid") ? body.get("playerUuid").getAsString() : "";
        java.util.UUID targetUuid;
        try {
            targetUuid = java.util.UUID.fromString(targetRaw.trim());
        } catch (Exception e) {
            sendError(ex, 400, "bad_request", "Invalid playerUuid");
            return;
        }
        try {
            MainThreadExecutor.call(minecraftServer, 20_000, () -> {
                if (!AdminAccess.isAdmin(minecraftServer, sess.get().playerUuid())) {
                    throw new IllegalStateException("forbidden_admin");
                }
                var binding = BindingStore.findByUuid(targetUuid);
                if (binding.isEmpty() || binding.get().networkLink == null) {
                    throw new IllegalStateException("binding_not_found");
                }
                var grid = Ae2QueryService.gridForBinding(minecraftServer, binding.get());
                if (grid.isEmpty()) {
                    throw new IllegalStateException("network_unavailable");
                }
                if (!grid.get().getEnergyService().isNetworkPowered()) {
                    throw new IllegalStateException("network_offline");
                }
                SessionStore.actAs(sess.get(), targetUuid);
                AuditLogStore.append(
                        "act_as",
                        sess.get().playerUuid(),
                        sess.get().playerName(),
                        targetUuid,
                        binding.get().playerName,
                        "enter user terminal"
                );
                JsonObject root = new JsonObject();
                root.addProperty("ok", true);
                root.addProperty("admin", true);
                root.addProperty("account", sess.get().playerName());
                appendActingAs(root, sess.get());
                sendJson(ex, 200, root);
                return null;
            });
        } catch (Exception e) {
            mapAdminError(ex, e);
        }
    }

    private static void adminClearActAs(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            Headers h = ex.getResponseHeaders();
            cors(h);
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "POST only");
            return;
        }
        var sess = SessionStore.get(bearer(ex));
        if (sess.isEmpty()) {
            sendError(ex, 401, "unauthorized", "Login required");
            return;
        }
        if (minecraftServer == null || !AdminAccess.isAdmin(minecraftServer, sess.get().playerUuid())) {
            sendError(ex, 403, "forbidden", "Admin (OP) required");
            return;
        }
        java.util.UUID prev = sess.get().actingAsUuid();
        String prevName = prev == null ? null : BindingStore.findByUuid(prev).map(b -> b.playerName).orElse(null);
        SessionStore.clearActAs(sess.get());
        AuditLogStore.append(
                "clear_act_as",
                sess.get().playerUuid(),
                sess.get().playerName(),
                prev,
                prevName,
                null
        );
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("admin", true);
        root.addProperty("account", sess.get().playerName());
        sendJson(ex, 200, root);
    }

    private static void adminAudit(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            Headers h = ex.getResponseHeaders();
            cors(h);
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "GET only");
            return;
        }
        var sess = SessionStore.get(bearer(ex));
        if (sess.isEmpty()) {
            sendError(ex, 401, "unauthorized", "Login required");
            return;
        }
        if (minecraftServer == null || !AdminAccess.isAdmin(minecraftServer, sess.get().playerUuid())) {
            sendError(ex, 403, "forbidden", "Admin (OP) required");
            return;
        }
        Map<String, String> q = query(ex);
        JsonObject result = AuditLogStore.query(
                q.getOrDefault("q", ""),
                parseInt(q.get("page"), 1),
                parseInt(q.get("pageSize"), 50)
        );
        sendJson(ex, 200, result);
    }

    private static void mapAdminError(HttpExchange ex, Exception e) throws IOException {
        String msg = e.getMessage() == null ? "internal" : e.getMessage();
        if (msg.contains("forbidden_admin")) {
            sendError(ex, 403, "forbidden", "Admin (OP) required");
        } else if (msg.contains("binding_not_found")) {
            sendError(ex, 404, "not_found", "Binding not found");
        } else if (msg.contains("network_unavailable")) {
            sendError(ex, 503, "network_unavailable", "Cannot resolve AE network");
        } else if (msg.contains("network_offline")) {
            sendError(ex, 503, "network_offline", "Target AE network is offline");
        } else if (msg.contains("Timeout")) {
            sendError(ex, 504, "timeout", msg);
        } else {
            Ae2LanuisMod.LOGGER.error("admin API error", e);
            sendError(ex, 500, "internal", msg);
        }
    }

    /**
     * 管理员：全部绑定端点与在线状态。须 OP（{@link AdminAccess}）。
     */
    private static void adminBindings(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            Headers h = ex.getResponseHeaders();
            cors(h);
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "GET only");
            return;
        }
        var sess = SessionStore.get(bearer(ex));
        if (sess.isEmpty()) {
            sendError(ex, 401, "unauthorized", "Login required");
            return;
        }
        if (minecraftServer == null) {
            sendError(ex, 503, "not_ready", "Server not ready");
            return;
        }
        try {
            MainThreadExecutor.call(minecraftServer, 30_000, () -> {
                if (!AdminAccess.isAdmin(minecraftServer, sess.get().playerUuid())) {
                    throw new IllegalStateException("forbidden_admin");
                }
                JsonObject result = AdminBindingService.listBindings(minecraftServer);
                AuditLogStore.append(
                        "admin_list_bindings",
                        sess.get().playerUuid(),
                        sess.get().playerName(),
                        null,
                        null,
                        "total=" + result.get("total").getAsInt()
                );
                sendJson(ex, 200, result);
                return null;
            });
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "internal" : e.getMessage();
            if (msg.contains("forbidden_admin")) {
                sendError(ex, 403, "forbidden", "Admin (OP) required");
            } else if (msg.contains("Timeout")) {
                sendError(ex, 504, "timeout", msg);
            } else {
                Ae2LanuisMod.LOGGER.error("admin bindings failed", e);
                sendError(ex, 500, "internal", msg);
            }
        }
    }

    /**
     * ME 库存分页查询；查询参数 q/kind(all|item|fluid|other)/filter/sort/order/page/pageSize。
     */
    private static void items(HttpExchange ex) throws IOException {
        authedGrid(ex, (mc, grid, sess) -> {
            Map<String, String> q = query(ex);
            JsonObject result = Ae2QueryService.inventory(
                    grid,
                    q.getOrDefault("q", ""),
                    q.getOrDefault("kind", "all"),
                    q.getOrDefault("filter", "all"),
                    q.getOrDefault("sort", "name"),
                    q.getOrDefault("order", "asc"),
                    parseInt(q.get("page"), 1),
                    parseInt(q.get("pageSize"), 96)
            );
            sendJson(ex, 200, result);
        });
    }

    /**
     * ME 已安装样板分页；查询参数 q / qOutput / qInput / mode / page / pageSize。
     */
    private static void patterns(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "GET only");
            return;
        }
        authedGrid(ex, (mc, grid, sess) -> {
            Map<String, String> q = query(ex);
            JsonObject result = Ae2PatternService.list(
                    grid,
                    q.getOrDefault("q", ""),
                    q.getOrDefault("qOutput", ""),
                    q.getOrDefault("qInput", ""),
                    q.getOrDefault("mode", ""),
                    parseInt(q.get("page"), 1),
                    parseInt(q.get("pageSize"), 96)
            );
            sendJson(ex, 200, result);
        });
    }

    /**
     * 样板供应器槽位板（含空槽与容量）。
     */
    private static void patternProviders(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "GET only");
            return;
        }
        authedGrid(ex, (mc, grid, sess) -> {
            Map<String, String> q = query(ex);
            JsonObject result = Ae2PatternBoardService.listProviders(
                    grid,
                    q.getOrDefault("q", ""),
                    q.getOrDefault("qOutput", ""),
                    q.getOrDefault("qInput", ""),
                    q.getOrDefault("mode", "")
            );
            sendJson(ex, 200, result);
        });
    }

    /**
     * 批量移动/重排样板槽位；事务性失败回滚。
     */
    private static void patternsMove(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "POST only");
            return;
        }
        JsonObject body = readJson(ex);
        authedGrid(ex, (mc, grid, sess) -> {
            JsonObject result = Ae2PatternBoardService.movePatterns(grid, body);
            boolean ok = result.has("ok") && result.get("ok").getAsBoolean();
            if (ok) {
                AuditLogStore.append(
                        "pattern_move",
                        sess.playerUuid(),
                        sess.playerName(),
                        sess.actingAsUuid(),
                        null,
                        body.has("moves") ? ("ops=" + body.getAsJsonArray("moves").size()) : null
                );
            }
            sendJson(ex, ok ? 200 : 400, result);
        });
    }

    /**
     * 可合成目录；内部固定 filter=craftable。
     */
    private static void catalog(HttpExchange ex) throws IOException {
        authedGrid(ex, (mc, grid, sess) -> {
            Map<String, String> q = query(ex);
            JsonObject result = Ae2QueryService.catalog(
                    grid,
                    q.getOrDefault("q", ""),
                    q.getOrDefault("sort", "name"),
                    q.getOrDefault("order", "asc"),
                    parseInt(q.get("page"), 1),
                    parseInt(q.get("pageSize"), 96)
            );
            sendJson(ex, 200, result);
        });
    }

    /**
     * 合成规划：body 需 key + amount；结果含 planId、缺失、字节/CPU 与配方树。
     */
    private static void plan(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "POST only");
            return;
        }
        JsonObject body = readJson(ex);
        authedGrid(ex, (mc, grid, sess) -> {
            JsonObject result = Ae2QueryService.plan(
                    mc, grid, sess.playerUuid(),
                    body.get("key").getAsString(),
                    body.get("amount").getAsString()
            );
            sendJson(ex, 200, result);
        });
    }

    /**
     * 提交合成：有 planId 则提交缓存计划，否则 key+amount 直提。
     * 业务失败返回 400 且 body 含 ok=false。
     */
    private static void submit(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "POST only");
            return;
        }
        JsonObject body = readJson(ex);
        authedGrid(ex, (mc, grid, sess) -> {
            JsonObject result;
            UUID craftOwner = sess.effectiveUuid();
            if (body.has("planId")) {
                String cpuName = body.has("cpuName") ? body.get("cpuName").getAsString() : "";
                result = Ae2QueryService.submit(mc, grid, craftOwner, body.get("planId").getAsString(), cpuName);
            } else {
                // 前端「一键合成」路径：内部仍走 plan→submit
                result = Ae2QueryService.submitDirect(
                        mc, grid, craftOwner,
                        body.get("key").getAsString(),
                        body.get("amount").getAsString()
                );
            }
            if (result.has("ok") && result.get("ok").getAsBoolean()) {
                AuditLogStore.append(
                        "craft_submit",
                        sess.playerUuid(),
                        sess.playerName(),
                        sess.actingAsUuid(),
                        null,
                        body.has("planId") ? "planId" : ("key=" + (body.has("key") ? body.get("key").getAsString() : ""))
                );
            }
            sendJson(ex, result.has("ok") && result.get("ok").getAsBoolean() ? 200 : 400, result);
        });
    }

    /**
     * 列出合成 CPU 任务；仅 GET。
     */
    private static void jobs(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            authedGrid(ex, (mc, grid, sess) -> sendJson(ex, 200, Ae2QueryService.jobs(grid)));
            return;
        }
        sendError(ex, 405, "method_not_allowed", "GET only");
    }

    /**
     * 取消指定 CPU 上的任务；body.cpuName 可空（则必然取消失败）。
     */
    private static void cancel(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "POST only");
            return;
        }
        JsonObject body = readJson(ex);
        String cpu = body.has("cpuName") ? body.get("cpuName").getAsString() : "";
        authedGrid(ex, (mc, grid, sess) -> {
            JsonObject result = Ae2QueryService.cancel(grid, cpu);
            AuditLogStore.append(
                    "craft_cancel",
                    sess.playerUuid(),
                    sess.playerName(),
                    sess.actingAsUuid(),
                    null,
                    "cpu=" + cpu
            );
            sendJson(ex, 200, result);
        });
    }

    /**
     * 预烘焙图标 PNG：{@code GET /api/v1/icons/{item|fluid}/{namespace}/{path...}}。
     * 不鉴权（img 无法带 Bearer）；读 {@code aeKeyResources/}；缺图 404（前端占位）。
     */
    private static void icons(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            Headers h = ex.getResponseHeaders();
            cors(h);
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod()) && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "method_not_allowed", "GET only");
            return;
        }
        String raw = ex.getRequestURI().getPath();
        String iconsRoot = "/api/v1/icons/";
        if (!raw.startsWith(iconsRoot)) {
            sendError(ex, 404, "not_found", "Expected /api/v1/icons/{item|fluid}/{namespace}/{path}");
            return;
        }
        String afterRoot = raw.substring(iconsRoot.length());
        int kindSlash = afterRoot.indexOf('/');
        if (kindSlash <= 0 || kindSlash >= afterRoot.length() - 1) {
            sendError(ex, 404, "not_found", "Expected /api/v1/icons/{item|fluid}/{namespace}/{path}");
            return;
        }
        String kind = afterRoot.substring(0, kindSlash);
        if (!IconResourcePaths.isKind(kind)) {
            sendError(ex, 404, "not_found", "Expected /api/v1/icons/{item|fluid}/{namespace}/{path}");
            return;
        }
        String rest = afterRoot.substring(kindSlash + 1);
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash >= rest.length() - 1) {
            sendError(ex, 400, "bad_request", "Missing namespace or path");
            return;
        }
        String namespace = urlDecode(rest.substring(0, slash));
        String path = urlDecode(rest.substring(slash + 1));
        if (path.endsWith(".png")) {
            path = path.substring(0, path.length() - 4);
        }
        if (!ResourceLocation.isValidNamespace(namespace) || !ResourceLocation.isValidPath(path) || path.contains("..")) {
            sendError(ex, 400, "bad_request", "Invalid resource id");
            return;
        }
        try {
            byte[] png = IconResourceStore.readPng(kind, namespace, path);
            if (png == null || png.length == 0) {
                sendError(ex, 404, "not_found", "Icon not found");
                return;
            }
            Headers h = ex.getResponseHeaders();
            h.add("Content-Type", "image/png");
            h.add("Cache-Control", "public, max-age=604800");
            cors(h);
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            ex.sendResponseHeaders(200, png.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(png);
            }
        } catch (Exception e) {
            Ae2LanuisMod.LOGGER.debug("Icon serve failed for {}:{}", namespace, path, e);
            sendError(ex, 500, "internal", e.getMessage() == null ? "icon failed" : e.getMessage());
        }
    }

    /**
     * 静态资源或 SPA 回退。
     * 误入 /api/* 返回 404；资源缺失则回退 index.html；两者皆无则纯文本提示构建步骤。
     */
    private static void staticOrFallback(HttpExchange ex) throws IOException {
        if (ex.getRequestURI().getPath().startsWith("/api/")) {
            sendError(ex, 404, "not_found", "Unknown API");
            return;
        }
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        try (InputStream in = HttpServerLifecycle.class.getResourceAsStream("/web" + path)) {
            if (in == null) {
                // SPA：未知路径回退入口页，交给前端路由
                try (InputStream index = HttpServerLifecycle.class.getResourceAsStream("/web/index.html")) {
                    if (index == null) {
                        sendText(ex, 200, "text/plain", "AE2 Lanuis API is running. Build web/dist then gradlew build to embed UI.");
                        return;
                    }
                    byte[] bytes = index.readAllBytes();
                    Headers h = ex.getResponseHeaders();
                    h.add("Content-Type", "text/html; charset=utf-8");
                    cors(h);
                    ex.sendResponseHeaders(200, bytes.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(bytes);
                    }
                    return;
                }
            }
            byte[] bytes = in.readAllBytes();
            Headers h = ex.getResponseHeaders();
            h.add("Content-Type", contentType(path));
            cors(h);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /**
     * 已鉴权且已解析 IGrid 后的业务回调；在主线程执行。
     */
    private interface GridHandler {
        void handle(MinecraftServer mc, appeng.api.networking.IGrid grid, SessionStore.Session session) throws Exception;
    }

    /**
     * 鉴权 → 取绑定 → 主线程解析网格并执行 handler。
     * <p>
     * 超时 20s 覆盖一般库存查询；合成规划另有自身 future 超时。
     * 异常消息关键字映射为稳定错误码（503/400/504/500）。
     * </p>
     */
    private static void authedGrid(HttpExchange ex, GridHandler handler) throws IOException {
        var sess = SessionStore.get(bearer(ex));
        if (sess.isEmpty()) {
            sendError(ex, 401, "unauthorized", "Login required");
            return;
        }
        var binding = BindingStore.findByUuid(sess.get().effectiveUuid());
        if (binding.isEmpty() || binding.get().networkLink == null) {
            sendError(ex, 401, "no_network_binding", "Re-run /ae2lanuis password in-game");
            return;
        }
        try {
            MainThreadExecutor.call(minecraftServer, 20_000, () -> {
                var grid = Ae2QueryService.gridForBinding(minecraftServer, binding.get());
                if (grid.isEmpty()) {
                    // WAP 卸载/网络离线/链接损坏
                    throw new IllegalStateException("network_unavailable");
                }
                handler.handle(minecraftServer, grid.get(), sess.get());
                return null;
            });
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "internal" : e.getMessage();
            if (msg.contains("network_unavailable")) {
                sendError(ex, 503, "network_unavailable", "Cannot resolve AE network from saved link");
            } else if (msg.contains("plan_expired")) {
                sendError(ex, 400, "plan_expired", "Craft plan expired");
            } else if (msg.contains("cpu_not_found")) {
                sendError(ex, 400, "cpu_not_found", "Crafting CPU not found");
            } else if (msg.contains("cpu_busy")) {
                sendError(ex, 400, "cpu_busy", "Crafting CPU is busy");
            } else if (msg.contains("cpu_insufficient_storage")) {
                sendError(ex, 400, "cpu_insufficient_storage", "Crafting CPU storage too small");
            } else if (msg.contains("Timeout")) {
                sendError(ex, 504, "plan_timeout", msg);
            } else {
                Ae2LanuisMod.LOGGER.error("API error", e);
                sendError(ex, 500, "internal", msg);
            }
        }
    }

    /**
     * 提取 Bearer token；无 Header 时回退 query {@code token}（便于调试，生产仍推荐 Header）。
     */
    private static String bearer(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        // 亦允许 query token，方便浏览器快速探测
        return query(ex).getOrDefault("token", "");
    }

    /**
     * 解析 raw query 为 map；重复键后者覆盖。值经 URLDecoder。
     */
    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> map = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) {
                map.put(urlDecode(part), "");
            } else {
                map.put(urlDecode(part.substring(0, i)), urlDecode(part.substring(i + 1)));
            }
        }
        return map;
    }

    /**
     * UTF-8 URL 解码。
     */
    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /**
     * 安全解析整数；null/非法返回默认值。
     */
    private static int parseInt(String s, int def) {
        try {
            return s == null ? def : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 读取请求体为 JsonObject；空 body 视为 {}。
     */
    private static JsonObject readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return new JsonObject();
            }
            return JsonParser.parseString(raw).getAsJsonObject();
        }
    }

    /**
     * 写 JSON 响应并附加 CORS 头。
     */
    private static void sendJson(HttpExchange ex, int code, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", "application/json; charset=utf-8");
        cors(h);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 统一错误 envelope：{@code { "error": { "code", "message" } }}。
     */
    private static void sendError(HttpExchange ex, int code, String err, String message) throws IOException {
        JsonObject root = new JsonObject();
        JsonObject error = new JsonObject();
        error.addProperty("code", err);
        error.addProperty("message", message);
        root.add("error", error);
        sendJson(ex, code, root);
    }

    /**
     * 写纯文本/其它类型响应。
     */
    private static void sendText(HttpExchange ex, int code, String type, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", type);
        cors(h);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 宽松 CORS：局域网 Web/桌面端跨源访问 API。
     * 公网请用反代覆盖更严策略。
     */
    private static void cors(Headers h) {
        h.add("Access-Control-Allow-Origin", "*");
        h.add("Access-Control-Allow-Headers", "Authorization, Content-Type");
        h.add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    /**
     * 按扩展名猜测 Content-Type；未知则 octet-stream。
     */
    private static String contentType(String path) {
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        if (path.endsWith(".ttf")) return "font/ttf";
        if (path.endsWith(".otf")) return "font/otf";
        return "application/octet-stream";
    }
}
