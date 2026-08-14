package com.lanuis.ae2web.auth;

import com.lanuis.ae2web.config.ModConfig;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话表：登录成功后发放 Bearer token。
 * <p>
 * 管理员可通过 {@link #actAs} 将有效绑定 UUID 切到目标玩家（effectiveUuid），
 * 便于进入其 ME 网络；{@link #playerUuid} 始终为操作者本人。
 * </p>
 */
public final class SessionStore {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private SessionStore() {
    }

    public static Session create(UUID playerUuid, String playerName, boolean remember) {
        purgeExpired();
        String token = generateToken();
        long ttl = remember
                ? ModConfig.SESSION_TTL_REMEMBER_SECONDS.get()
                : ModConfig.SESSION_TTL_SECONDS.get();
        Session session = new Session(token, playerUuid, playerName, System.currentTimeMillis() + ttl * 1000L);
        SESSIONS.put(token, session);
        return session;
    }

    public static Optional<Session> get(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = SESSIONS.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAtMillis < System.currentTimeMillis()) {
            SESSIONS.remove(token);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public static void remove(String token) {
        if (token != null) {
            SESSIONS.remove(token);
        }
    }

    /**
     * 管理员切入目标绑定；成功返回 true。
     */
    public static boolean actAs(Session session, UUID targetUuid) {
        if (session == null || targetUuid == null) {
            return false;
        }
        session.actingAsUuid = targetUuid;
        return true;
    }

    public static void clearActAs(Session session) {
        if (session != null) {
            session.actingAsUuid = null;
        }
    }

    public static int size() {
        purgeExpired();
        return SESSIONS.size();
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtMillis < now) {
                it.remove();
            }
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 会话：{@link #playerUuid} 为登录者；{@link #effectiveUuid()} 为当前使用的绑定。
     */
    public static final class Session {
        private final String token;
        private final UUID playerUuid;
        private final String playerName;
        private final long expiresAtMillis;
        /** 管理员切入的目标 UUID；null 表示使用本人绑定。 */
        private volatile UUID actingAsUuid;

        Session(String token, UUID playerUuid, String playerName, long expiresAtMillis) {
            this.token = token;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.expiresAtMillis = expiresAtMillis;
        }

        public String token() {
            return token;
        }

        public UUID playerUuid() {
            return playerUuid;
        }

        public String playerName() {
            return playerName;
        }

        public long expiresAtMillis() {
            return expiresAtMillis;
        }

        public UUID actingAsUuid() {
            return actingAsUuid;
        }

        /** 当前解析网络所用的绑定 UUID。 */
        public UUID effectiveUuid() {
            UUID as = actingAsUuid;
            return as != null ? as : playerUuid;
        }

        public boolean isActingAs() {
            return actingAsUuid != null;
        }
    }
}
