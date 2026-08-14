package com.lanuis.ae2web.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.lanuis.ae2web.Ae2LanuisMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;






import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家网页凭证与 AE 网络链接的持久化存储。
 * <p>
 * 文件位于世界目录 {@code ae2lanuis/bindings.json}；密码仅存 PBKDF2 哈希与盐。
 * 写路径方法均为 synchronized，避免 HTTP 登录与游戏内设密并发写坏 JSON。
 * 登录可用玩家名（大小写不敏感）或 UUID 字符串。
 * </p>
 */
public final class BindingStore {
    /** 美化输出，便于服主手工排错；性能非瓶颈。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** bindings 对象反序列化类型：UUID 字符串 → BindingRecord。 */
    private static final Type MAP_TYPE = new TypeToken<Map<String, BindingRecord>>() {}.getType();
    /** PBKDF2 迭代次数：偏高以抗离线爆破，仍可接受登录延迟。 */
    private static final int ITERATIONS = 120_000;
    /** 派生密钥比特长度。 */
    private static final int KEY_LENGTH = 256;
    /** 盐与其它随机字节的安全源。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 当前世界 bindings.json 路径；init 前为 null。 */
    private static Path storePath;
    /** UUID 字符串 → 绑定记录（不含 transient playerUuid）。 */
    private static final Map<String, BindingRecord> BY_UUID = new ConcurrentHashMap<>();
    /** 规范化玩家名 → UUID 字符串，加速账号登录。 */
    private static final Map<String, String> NAME_TO_UUID = new ConcurrentHashMap<>();

    /**
     * 工具类禁止实例化。
     */
    private BindingStore() {
    }

    /**
     * 在服务器启动时解析世界数据目录并加载已有绑定。
     * 目录创建失败直接抛错，避免后续 silent 丢数据。
     *
     * @param server 已启动的 Minecraft 服务端
     */
    public static synchronized void init(MinecraftServer server) {
        // 挂在世界根下，随存档备份/迁移
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("ae2lanuis");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create ae2lanuis data dir", e);
        }
        storePath = dir.resolve("bindings.json");
        load();
    }

    /**
     * 写入或覆盖某玩家的密码哈希与网络链接快照，并立即 persist。
     * 每次设密重新生成盐，防止彩虹表与旧哈希复用。
     *
     * @param playerId    玩家 UUID
     * @param playerName  当前显示名（用于 NAME 索引）
     * @param password    明文密码（仅用于哈希）
     * @param networkLink {@link com.lanuis.ae2web.ae2.NetworkLinkCodec} 编码结果
     * @param linkSummary 给人看的摘要 JSON（维度、物品 id 等）
     */
    public static synchronized void saveBinding(UUID playerId, String playerName, String password, JsonObject networkLink, JsonObject linkSummary) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String hash = hashPassword(password, salt);
        BindingRecord record = new BindingRecord();
        record.playerName = playerName;
        record.passwordSalt = Base64.getEncoder().encodeToString(salt);
        record.passwordHash = hash;
        // 秒级时间戳，便于外部工具比对
        record.updatedAt = System.currentTimeMillis() / 1000L;
        record.networkLink = networkLink;
        record.linkSummary = linkSummary;
        BY_UUID.put(playerId.toString(), record);
        // 改名后旧名索引可能残留；此处以最新名为准覆盖
        indexName(playerName, playerId.toString());
        persist();
    }

    /**
     * 删除绑定并刷新磁盘；同时移除名称索引。
     */
    public static synchronized void clearBinding(UUID playerId) {
        BindingRecord removed = BY_UUID.remove(playerId.toString());
        if (removed != null && removed.playerName != null) {
            NAME_TO_UUID.remove(normalize(removed.playerName));
        }
        persist();
    }

    /**
     * 按账号查找：先规范化玩家名，再尝试把 account 当作 UUID 字符串。
     * 返回的记录带 transient playerUuid，供 Session 创建使用。
     *
     * @param account 玩家名或 UUID
     */
    public static Optional<BindingRecord> findByAccount(String account) {
        if (account == null || account.isBlank()) {
            return Optional.empty();
        }
        String uuid = NAME_TO_UUID.get(normalize(account));
        if (uuid != null) {
            return Optional.ofNullable(BY_UUID.get(uuid)).map(r -> withUuid(uuid, r));
        }
        // 允许直接用 UUID 登录（脚本/多端）
        BindingRecord byId = BY_UUID.get(account);
        if (byId != null) {
            return Optional.of(withUuid(account, byId));
        }
        return Optional.empty();
    }

    /**
     * 按 UUID 精确查找；无记录返回 empty。
     */
    public static Optional<BindingRecord> findByUuid(UUID uuid) {
        BindingRecord record = BY_UUID.get(uuid.toString());
        return record == null ? Optional.empty() : Optional.of(withUuid(uuid.toString(), record));
    }

    /**
     * 绑定摘要列表（不含密码哈希）；供管理员命令展示。
     */
    public static synchronized java.util.List<String> listBindingLines() {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (Map.Entry<String, BindingRecord> e : BY_UUID.entrySet()) {
            BindingRecord r = e.getValue();
            String name = r.playerName == null ? "?" : r.playerName;
            String summary = r.linkSummary == null ? "(无摘要)" : r.linkSummary.toString();
            lines.add(name + " [" + e.getKey() + "] updated=" + r.updatedAt + " " + summary);
        }
        lines.sort(String::compareToIgnoreCase);
        return lines;
    }

    /**
     * 全部绑定快照（含 playerUuid，无密码字段拷贝到调用方只读用途）。
     * 返回副本，避免调用方改到表内对象。
     */
    public static synchronized java.util.List<BindingRecord> listAll() {
        java.util.ArrayList<BindingRecord> out = new java.util.ArrayList<>(BY_UUID.size());
        for (Map.Entry<String, BindingRecord> e : BY_UUID.entrySet()) {
            out.add(withUuid(e.getKey(), e.getValue()));
        }
        out.sort((a, b) -> {
            String na = a.playerName == null ? "" : a.playerName;
            String nb = b.playerName == null ? "" : b.playerName;
            return na.compareToIgnoreCase(nb);
        });
        return out;
    }

    /**
     * 使用记录中的盐重新派生哈希，并与存储值做常量时间比较。
     * 任一参数 null 直接 false，避免 NPE 泄露分支。
     */
    public static boolean verifyPassword(BindingRecord record, String password) {
        if (record == null || password == null) {
            return false;
        }
        byte[] salt = Base64.getDecoder().decode(record.passwordSalt);
        String hash = hashPassword(password, salt);
        return constantTimeEquals(hash, record.passwordHash);
    }

    /**
     * 复制记录并填入 playerUuid（磁盘 JSON 的 key 不进 Gson 字段）。
     */
    private static BindingRecord withUuid(String uuid, BindingRecord source) {
        BindingRecord copy = new BindingRecord();
        copy.playerUuid = uuid;
        copy.playerName = source.playerName;
        copy.passwordSalt = source.passwordSalt;
        copy.passwordHash = source.passwordHash;
        copy.updatedAt = source.updatedAt;
        copy.networkLink = source.networkLink;
        copy.linkSummary = source.linkSummary;
        return copy;
    }

    /**
     * 建立规范化名 → UUID 索引；null 名忽略。
     */
    private static void indexName(String playerName, String uuid) {
        if (playerName != null) {
            NAME_TO_UUID.put(normalize(playerName), uuid);
        }
    }

    /**
     * 玩家名规范化：trim + Locale.ROOT 小写，避免土耳其语 I 等陷阱。
     */
    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * PBKDF2WithHmacSHA256 派生后 Base64；失败包装为 IllegalStateException。
     */
    private static String hashPassword(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Password hashing failed", e);
        }
    }

    /**
     * 常量时间字符串比较，降低时序旁路猜测哈希的风险。
     * 长度不等时直接 false（长度本身已固定为 Base64 输出）。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            // 累积异或，避免短路比较提前返回
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /**
     * 从磁盘加载；文件缺失视为空库。损坏时记 error 日志但不拖垮服务器启动。
     */
    private static void load() {
        BY_UUID.clear();
        NAME_TO_UUID.clear();
        if (storePath == null || !Files.exists(storePath)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(storePath, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("bindings")) {
                return;
            }
            Map<String, BindingRecord> map = GSON.fromJson(root.get("bindings"), MAP_TYPE);
            if (map == null) {
                return;
            }
            for (Map.Entry<String, BindingRecord> e : map.entrySet()) {
                BY_UUID.put(e.getKey(), e.getValue());
                indexName(e.getValue().playerName, e.getKey());
            }
            Ae2LanuisMod.LOGGER.info("Loaded {} AE2 Lanuis bindings", BY_UUID.size());
        } catch (Exception e) {
            Ae2LanuisMod.LOGGER.error("Failed to load bindings.json", e);
        }
    }

    /**
     * 原子性不足：直接覆盖写；崩溃窗口极小。storePath 未 init 时跳过。
     * 写入时拷贝 HashMap，避免序列化过程中 map 被并发修改。
     */
    private static void persist() {
        if (storePath == null) {
            return;
        }
        JsonObject root = new JsonObject();
        // 快照拷贝：Gson 遍历时 BY_UUID 仍可能被其它线程读，但写入口已 synchronized
        root.add("bindings", GSON.toJsonTree(new HashMap<>(BY_UUID)));
        try (Writer writer = Files.newBufferedWriter(storePath, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            Ae2LanuisMod.LOGGER.error("Failed to save bindings.json", e);
        }
    }

    /**
     * 单条绑定：密码材料 + AE 链接。
     * playerUuid 为 transient，仅运行时由 withUuid 填入，不序列化进 JSON。
     */
    public static final class BindingRecord {
        /** 运行时注入的玩家 UUID 字符串；不落盘。 */
        public transient String playerUuid;
        /** 设密时的玩家名，用于登录索引。 */
        public String playerName;
        /** Base64 盐。 */
        public String passwordSalt;
        /** Base64 PBKDF2 哈希。 */
        public String passwordHash;
        /** 上次更新（Unix 秒）。 */
        public long updatedAt;
        /** NetworkLinkCodec 编码的网络链接。 */
        public JsonObject networkLink;
        /** 给人看的摘要（终端 id、维度等）。 */
        public JsonObject linkSummary;
    }
}
