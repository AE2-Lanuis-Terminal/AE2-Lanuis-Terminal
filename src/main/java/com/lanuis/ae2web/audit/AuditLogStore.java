package com.lanuis.ae2web.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lanuis.ae2web.Ae2LanuisMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 审计日志：内存环形缓冲 + 世界目录 {@code ae2lanuis/audit.jsonl} 追加。
 */
public final class AuditLogStore {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_MEMORY = 2000;
    private static final Deque<JsonObject> MEMORY = new ArrayDeque<>();
    private static Path logPath;
    private static final Object LOCK = new Object();

    private AuditLogStore() {
    }

    public static synchronized void init(MinecraftServer server) {
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("ae2lanuis");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create ae2lanuis data dir for audit", e);
        }
        logPath = dir.resolve("audit.jsonl");
        loadTailIntoMemory();
    }

    public static void append(String action, UUID actorUuid, String actorName, UUID targetUuid, String targetName, String detail) {
        JsonObject e = new JsonObject();
        e.addProperty("ts", System.currentTimeMillis());
        e.addProperty("action", action == null ? "unknown" : action);
        if (actorUuid != null) {
            e.addProperty("actorUuid", actorUuid.toString());
        }
        if (actorName != null) {
            e.addProperty("actorName", actorName);
        }
        if (targetUuid != null) {
            e.addProperty("targetUuid", targetUuid.toString());
        }
        if (targetName != null) {
            e.addProperty("targetName", targetName);
        }
        if (detail != null && !detail.isBlank()) {
            e.addProperty("detail", detail);
        }
        synchronized (LOCK) {
            MEMORY.addLast(e);
            while (MEMORY.size() > MAX_MEMORY) {
                MEMORY.removeFirst();
            }
            Path path = logPath;
            if (path != null) {
                try (BufferedWriter w = Files.newBufferedWriter(
                        path,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                )) {
                    w.write(GSON.toJson(e));
                    w.newLine();
                } catch (IOException ex) {
                    Ae2LanuisMod.LOGGER.warn("Failed to append audit log: {}", ex.toString());
                }
            }
        }
        Ae2LanuisMod.LOGGER.info(
                "audit action={} actor={} target={} detail={}",
                action,
                actorName,
                targetName,
                detail
        );
    }

    /**
     * 分页查询（最新在前）；q 匹配 action/actor/target/detail（忽略大小写）。
     */
    public static JsonObject query(String q, int page, int pageSize) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<JsonObject> filtered = new ArrayList<>();
        synchronized (LOCK) {
            List<JsonObject> all = new ArrayList<>(MEMORY);
            for (int i = all.size() - 1; i >= 0; i--) {
                JsonObject e = all.get(i);
                if (needle.isEmpty() || matches(e, needle)) {
                    filtered.add(e);
                }
            }
        }
        int p = Math.max(1, page);
        int size = Math.min(Math.max(1, pageSize), 200);
        int total = filtered.size();
        int from = Math.min((p - 1) * size, total);
        int to = Math.min(from + size, total);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (int i = from; i < to; i++) {
            arr.add(filtered.get(i));
        }
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("page", p);
        root.addProperty("pageSize", size);
        root.addProperty("total", total);
        root.add("entries", arr);
        return root;
    }

    private static boolean matches(JsonObject e, String needle) {
        return contains(e, "action", needle)
                || contains(e, "actorName", needle)
                || contains(e, "actorUuid", needle)
                || contains(e, "targetName", needle)
                || contains(e, "targetUuid", needle)
                || contains(e, "detail", needle);
    }

    private static boolean contains(JsonObject e, String key, String needle) {
        if (!e.has(key) || !e.get(key).isJsonPrimitive()) {
            return false;
        }
        return e.get(key).getAsString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static void loadTailIntoMemory() {
        if (logPath == null || !Files.isRegularFile(logPath)) {
            return;
        }
        Deque<JsonObject> loaded = new ArrayDeque<>();
        try (BufferedReader r = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    JsonObject o = GSON.fromJson(line, JsonObject.class);
                    if (o != null) {
                        loaded.addLast(o);
                        while (loaded.size() > MAX_MEMORY) {
                            loaded.removeFirst();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            Ae2LanuisMod.LOGGER.warn("Failed to load audit.jsonl: {}", e.toString());
            return;
        }
        synchronized (LOCK) {
            MEMORY.clear();
            MEMORY.addAll(loaded);
        }
    }
}
