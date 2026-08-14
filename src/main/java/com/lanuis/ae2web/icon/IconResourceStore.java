package com.lanuis.ae2web.icon;

import com.lanuis.ae2web.Ae2LanuisMod;
import com.lanuis.ae2web.config.ModConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 从预烘焙目录读取图标 PNG；专用服无 GPU 合成。
 */
public final class IconResourceStore {
    private IconResourceStore() {
    }

    public static Path rootDir() {
        return Path.of(ModConfig.ICON_RESOURCES_DIR.get()).toAbsolutePath().normalize();
    }

    /**
     * @return PNG 字节；缺失或非法路径返回 null
     */
    public static byte[] readPng(String kind, String namespace, String path) {
        if (!IconResourcePaths.isKind(kind)) {
            return null;
        }
        Path root = rootDir();
        Path file = IconResourcePaths.resolvePng(root, kind, namespace, path);
        if (!IconResourcePaths.isUnderRoot(root, file)) {
            Ae2LanuisMod.LOGGER.warn("Rejected icon path traversal: {}", file);
            return null;
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            Ae2LanuisMod.LOGGER.debug("Failed reading icon {}", file, e);
            return null;
        }
    }

    /** 各 kind 下 PNG 数量；目录不存在则全 0。 */
    public static CountResult countPngs() {
        Path root = rootDir();
        int items = countKind(root, IconResourcePaths.KIND_ITEM);
        int fluids = countKind(root, IconResourcePaths.KIND_FLUID);
        return new CountResult(root, Files.isDirectory(root), items, fluids);
    }

    private static int countKind(Path root, String kind) {
        Path dir = root.resolve(kind);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".png")).count();
        } catch (IOException e) {
            Ae2LanuisMod.LOGGER.debug("countPngs failed for {}", dir, e);
            return 0;
        }
    }

    /**
     * 删除资源目录下全部 PNG（保留目录结构尽力而为）。
     *
     * @return 删除的文件数；失败抛 IOException
     */
    public static int clearAll() throws IOException {
        Path root = rootDir();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int deleted = 0;
        try (var walk = Files.walk(root)) {
            var files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .toList();
            for (Path f : files) {
                if (!IconResourcePaths.isUnderRoot(root, f)) {
                    continue;
                }
                Files.deleteIfExists(f);
                deleted++;
            }
        }
        return deleted;
    }

    public record CountResult(Path root, boolean exists, int items, int fluids) {
        public int total() {
            return items + fluids;
        }
    }
}
