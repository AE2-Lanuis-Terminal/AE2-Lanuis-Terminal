package com.lanuis.ae2web.icon;

import java.nio.file.Path;

/**
 * 预烘焙图标磁盘路径约定（与 HTTP {@code /api/v1/icons/{kind}/{ns}/{path}} 对齐）。
 * <p>
 * 文件：{@code {root}/{item|fluid}/{ns}_{path}.png}，{@code :} 与 {@code /} 均替换为 {@code _}。
 * </p>
 */
public final class IconResourcePaths {
    public static final String KIND_ITEM = "item";
    public static final String KIND_FLUID = "fluid";
    public static final String DEFAULT_DIR = "aeKeyResources";

    private IconResourcePaths() {
    }

    /** {@code minecraft:stone} → {@code minecraft_stone} */
    public static String sanitizeId(String id) {
        if (id == null || id.isEmpty()) {
            return "_";
        }
        return id.replace(':', '_').replace('/', '_').replace('\\', '_');
    }

    /**
     * 解析 PNG 路径；调用方须再 {@link #isUnderRoot} 防穿越。
     */
    public static Path resolvePng(Path root, String kind, String namespace, String path) {
        String fileName = sanitizeId(namespace + ":" + path) + ".png";
        return root.resolve(kind).resolve(fileName).normalize();
    }

    public static boolean isKind(String kind) {
        return KIND_ITEM.equals(kind) || KIND_FLUID.equals(kind);
    }

    public static boolean isUnderRoot(Path root, Path candidate) {
        Path nRoot = root.toAbsolutePath().normalize();
        Path nCand = candidate.toAbsolutePath().normalize();
        return nCand.startsWith(nRoot);
    }
}
