package com.lanuis.ae2web.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Arrays;
import java.util.List;

/**
 * Forge 服务端配置规格定义。
 * <p>
 * 分 http / auth / ae2 三组：HTTP 监听与静态页、会话 TTL、无线终端扫描与合成超时。
 * 默认绑定 0.0.0.0:8765，生产环境应配合防火墙或反代；末影箱扫描默认关闭以免误绑。
 * </p>
 * <p>
 * 配置在模组构造期注册为 {@code Type.SERVER}，仅服务端生效；
 * 运行时通过 {@code .get()} 读取，HTTP 热路径勿缓存过久以免忽略热重载（若启用）。
 * </p>
 */
public final class ModConfig {
    /** 构建完成的配置规格，由模组入口注册为 SERVER 类型。 */
    public static final ForgeConfigSpec SPEC;

    /** 是否启用内嵌 HTTP；关闭时仍可加载绑定文件但不监听端口。 */
    public static final ForgeConfigSpec.BooleanValue HTTP_ENABLED;
    /** 监听地址；0.0.0.0 表示所有网卡，局域网暴露需自担风险。 */
    public static final ForgeConfigSpec.ConfigValue<String> BIND_ADDRESS;
    /**
     * 聊天栏/status 展示用的对外主机名或 IP。
     * 空则自动探测；Docker / 端口映射场景请填宿主机 IP 或域名。
     */
    public static final ForgeConfigSpec.ConfigValue<String> PUBLIC_HOST;
    /** HTTP 端口，范围 1–65535，默认 8765。 */
    public static final ForgeConfigSpec.IntValue PORT;
    /** 是否从 classpath /web 提供静态前端；仅 API 场景可关。 */
    public static final ForgeConfigSpec.BooleanValue STATIC_WEB_ENABLED;
    /**
     * 预烘焙图标目录（相对游戏根目录）。
     * 单人执行 {@code /ae2lanuis resources render} 生成后，拷到专用服同路径。
     */
    public static final ForgeConfigSpec.ConfigValue<String> ICON_RESOURCES_DIR;

    /** 普通登录会话 TTL（秒）；到期后 SessionStore 惰性清除。 */
    public static final ForgeConfigSpec.IntValue SESSION_TTL_SECONDS;
    /** 「记住我」会话 TTL（秒）；显著长于普通会话。 */
    public static final ForgeConfigSpec.IntValue SESSION_TTL_REMEMBER_SECONDS;
    /**
     * Web 管理页所需最低权限等级（与原版 OP 一致：1=moderator，2=gamemaster，3=admin，4=owner）。
     * 默认 2，与游戏内 {@code /ae2lanuis bindings} 一致。
     */
    public static final ForgeConfigSpec.IntValue ADMIN_PERMISSION_LEVEL;

    /** 允许绑定的无线终端物品 id 列表（命名空间:路径）。 */
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> TERMINAL_ITEM_IDS;
    /** 设密时是否扫描副手。 */
    public static final ForgeConfigSpec.BooleanValue SCAN_OFFHAND;
    /** 设密时是否扫描玩家背包全部槽位。 */
    public static final ForgeConfigSpec.BooleanValue SCAN_INVENTORY;
    /** 设密时是否扫描末影箱；默认关，避免跨维度误绑。 */
    public static final ForgeConfigSpec.BooleanValue SCAN_ENDER_CHEST;
    /** 解析 GlobalPos 锚点时是否强制加载区块（WAP 所在 chunk）。 */
    public static final ForgeConfigSpec.BooleanValue FORCE_LOAD_ANCHOR_CHUNK;
    /** 库存 API 单页最大条数上限，防止一次扫全网 OOM。 */
    public static final ForgeConfigSpec.IntValue MAX_INVENTORY_PAGE_SIZE;
    /** 合成规划 future.get 超时（毫秒）；复杂配方可能接近上限。 */
    public static final ForgeConfigSpec.IntValue CRAFT_PLAN_TIMEOUT_MS;

    /** 是否启用 WebSocket（库存实时推送）。 */
    public static final ForgeConfigSpec.BooleanValue WS_ENABLED;
    /**
     * WebSocket 端口。{@code 0}（默认）= 与 {@link #PORT} 相同（单端口分流）；
     * 显式填其它端口则独立监听。
     */
    public static final ForgeConfigSpec.IntValue WS_PORT;
    /** 库存采样/推送最小间隔（毫秒）；仅服务端可调，客户端不可改。 */
    public static final ForgeConfigSpec.IntValue WS_PUSH_INTERVAL_MS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        // —— HTTP 监听与静态资源 ——
        builder.push("http");
        // 总开关：关则 start() 只初始化 BindingStore
        HTTP_ENABLED = builder.define("enabled", true);
        // 默认全网卡；日志会对 0.0.0.0 给出安全警告
        BIND_ADDRESS = builder.define("bindAddress", "0.0.0.0");
        PUBLIC_HOST = builder
                .comment("Host/IP shown in /ae2lanuis password|status links. Empty = auto. Set this in Docker to the host IP or domain.")
                .define("publicHost", "");
        PORT = builder.defineInRange("port", 8765, 1, 65535);
        // 嵌入 jar 的 Vue 构建产物；无资源时仍返回提示文本
        STATIC_WEB_ENABLED = builder.define("staticWebEnabled", true);
        ICON_RESOURCES_DIR = builder
                .comment("Pre-baked AEKey PNG dir (relative to game root). Generate in SP via /ae2lanuis resources render, then copy to the dedicated server.")
                .define("iconResourcesDir", "aeKeyResources");
        builder.pop();

        // —— WebSocket ——
        builder.push("websocket");
        WS_ENABLED = builder.comment("Enable storage realtime WebSocket")
                .define("enabled", true);
        WS_PORT = builder.comment("WebSocket port; 0 = same as http.port (default). Set e.g. 8766 for a dedicated port.")
                .defineInRange("port", 0, 0, 65535);
        WS_PUSH_INTERVAL_MS = builder.comment("Min interval between storage snapshots (ms); server-only")
                .defineInRange("pushIntervalMs", 1000, 100, 10000);
        builder.pop();

        // —— Web 会话寿命 ——
        builder.push("auth");
        // 最短 60s，避免配置成几乎立即失效
        SESSION_TTL_SECONDS = builder.defineInRange("sessionTtlSeconds", 3600, 60, 86400 * 30);
        // 记住我最长约 90 天，与前端勾选联动
        SESSION_TTL_REMEMBER_SECONDS = builder.defineInRange("sessionTtlRememberSeconds", 604800, 60, 86400 * 90);
        ADMIN_PERMISSION_LEVEL = builder
                .comment("Min vanilla permission level for Web admin APIs (1-4). Default 2 = LEVEL_GAMEMASTERS / typical OP.")
                .defineInRange("adminPermissionLevel", 2, 1, 4);
        builder.pop();

        // —— AE2 绑定与查询行为 ——
        builder.push("ae2");
        // 默认官方无线终端与无线合成终端；整合包可追加兼容物品
        TERMINAL_ITEM_IDS = builder.defineList(
                "terminalItemIds",
                Arrays.asList("ae2:wireless_terminal", "ae2:wireless_crafting_terminal"),
                o -> o instanceof String
        );
        SCAN_OFFHAND = builder.define("scanOffhand", true);
        SCAN_INVENTORY = builder.define("scanInventory", true);
        // 末影箱默认不扫：物品可能在另一维度且玩家无感知
        SCAN_ENDER_CHEST = builder.define("scanEnderChest", false);
        // 强制加载可提高离线解析成功率，但有额外区块加载成本
        FORCE_LOAD_ANCHOR_CHUNK = builder.define("forceLoadAnchorChunk", true);
        MAX_INVENTORY_PAGE_SIZE = builder.defineInRange("maxInventoryPageSize", 200, 1, 1000);
        // 与 MainThreadExecutor 调用超时分开；规划本身常更慢
        CRAFT_PLAN_TIMEOUT_MS = builder.defineInRange("craftPlanTimeoutMs", 15000, 1000, 120000);
        builder.pop();

        SPEC = builder.build();
    }

    /** {@code websocket.port==0} 时跟随 HTTP 端口。 */
    public static int effectiveWebSocketPort() {
        int ws = WS_PORT.get();
        return ws > 0 ? ws : PORT.get();
    }

    /** 是否与 HTTP 共用对外端口（经 ProtocolMux 分流）。 */
    public static boolean webSocketSharesHttpPort() {
        return Boolean.TRUE.equals(WS_ENABLED.get()) && effectiveWebSocketPort() == PORT.get();
    }

    /**
     * 配置持有者禁止实例化。
     */
    private ModConfig() {
    }
}
