package com.lanuis.ae2web.auth;

import com.google.gson.JsonObject;
import com.lanuis.ae2web.ae2.WirelessTerminalBinder;
import com.lanuis.ae2web.config.ModConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;

/**
 * 游戏内 Brigadier 命令：设置/清除网页密码并快照无线终端网络链接。
 * <p>
 * 命令树：{@code /ae2lanuis password <密码>}、{@code password clear}、{@code status}。
 * 仅玩家实体可执行——控制台无背包终端，无法产生有效绑定。
 * 设密时同步调用 {@link WirelessTerminalBinder#snapshotFromPlayer}，失败则不写库。
 * </p>
 */
public final class PasswordCommands {
    /**
     * 工具类禁止实例化。
     */
    private PasswordCommands() {
    }

    /**
     * 向调度器注册 {@code ae2lanuis} 字面量命令树。
     * password 使用 greedyString，允许含空格的密码（仍建议无空格）。
     *
     * @param dispatcher 服务端命令调度器
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("ae2lanuis")
                        .then(Commands.literal("password")
                                // 子命令 clear 必须在 greedy 参数之前注册，否则会被吃掉
                                .then(Commands.literal("clear")
                                        .executes(ctx -> clear(ctx.getSource())))
                                .then(Commands.argument("password", StringArgumentType.greedyString())
                                        .executes(ctx -> setPassword(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "password")
                                        ))))
                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource())))
        );
    }

    /**
     * 设置网页密码并持久化当前可解析的 AE 网络链接。
     * <p>
     * 密码最短 4 字符（轻量约束，真正强度靠玩家）；
     * 绑定失败时返回 0 且不改动旧绑定，避免「清了旧的却写不上新的」。
     * </p>
     *
     * @param source   命令源
     * @param password 明文密码（仅此刻用于哈希，不记日志）
     * @return 1 成功 / 0 失败
     */
    private static int setPassword(CommandSourceStack source, String password) {
        // 控制台/命令方块：无玩家背包与无线终端上下文
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("该命令只能由玩家执行"));
            return 0;
        }
        // 过短密码几乎无防撞库意义，直接拒绝
        if (password == null || password.length() < 4) {
            source.sendFailure(Component.literal("密码至少 4 位"));
            return 0;
        }

        // 从主手/副手/背包等扫描已链接终端并解析 IGrid
        WirelessTerminalBinder.BindResult result = WirelessTerminalBinder.snapshotFromPlayer(player);
        if (!result.ok()) {
            // 失败消息已是中文用户可读原因
            source.sendFailure(Component.literal(result.message()));
            return 0;
        }

        // 哈希密码 + 写入 networkLink / linkSummary，立即落盘
        BindingStore.saveBinding(
                player.getUUID(),
                player.getGameProfile().getName(),
                password,
                result.networkLink(),
                result.linkSummary()
        );
        String account = player.getGameProfile().getName();
        WebLink link = resolveWebLink(account);
        source.sendSuccess(() -> Component.literal("已设置网页密码并绑定 AE 网络：" + result.summaryText()), false);
        source.sendSuccess(() -> Component.literal("登录账号：" + account), false);
        source.sendSuccess(() -> webAddressMessage(link), false);
        if (link.needsPublicHostHint()) {
            source.sendSuccess(() -> Component.literal(
                    "当前像在容器内：请设置 config 中 http.publicHost（或环境变量 AE2LANUIS_PUBLIC_HOST）为宿主机 IP/域名"
            ).withStyle(ChatFormatting.YELLOW), false);
        }
        if (!Boolean.TRUE.equals(ModConfig.HTTP_ENABLED.get())) {
            source.sendSuccess(() -> Component.literal("注意：当前配置已关闭 HTTP，网页暂不可用").withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /**
     * 清除该玩家密码哈希与网络绑定；Web 登录将立即失效（旧 token 仍可能存活至过期）。
     */
    private static int clear(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("该命令只能由玩家执行"));
            return 0;
        }
        BindingStore.clearBinding(player.getUUID());
        source.sendSuccess(() -> Component.literal("已清除密码与网络绑定"), false);
        return 1;
    }

    /**
     * 查询是否已绑定及 linkSummary 摘要（终端 id、维度、位置）。
     * 未绑定时提示设密流程，不泄露其他玩家信息。
     */
    private static int status(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("该命令只能由玩家执行"));
            return 0;
        }
        var binding = BindingStore.findByUuid(player.getUUID());
        if (binding.isEmpty()) {
            source.sendSuccess(() -> Component.literal("尚未绑定。手持已链接无线终端后执行 /ae2lanuis password <密码>"), false);
            return 1;
        }
        JsonObject summary = binding.get().linkSummary;
        // summary 可能为空（旧数据）；仍报告「已绑定」
        String text = summary == null ? "(无摘要)" : summary.toString();
        source.sendSuccess(() -> Component.literal("已绑定：" + text), false);
        WebLink link = resolveWebLink(player.getGameProfile().getName());
        source.sendSuccess(() -> webAddressMessage(link), false);
        if (link.needsPublicHostHint()) {
            source.sendSuccess(() -> Component.literal(
                    "当前像在容器内：请设置 config 中 http.publicHost（或环境变量 AE2LANUIS_PUBLIC_HOST）为宿主机 IP/域名"
            ).withStyle(ChatFormatting.YELLOW), false);
        }
        return 1;
    }

    /** 聊天栏可点击打开的网页地址行。 */
    private static MutableComponent webAddressMessage(WebLink link) {
        String url = link.url();
        return Component.literal("网页地址：")
                .append(Component.literal(url).withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))));
    }

    /**
     * 对外展示用链接：附带 {@code #/?account=}，打开后只需输入密码。
     * publicHost / 环境变量优先；否则自动探测（避开 Docker 桥接网）。
     */
    private static WebLink resolveWebLink(String account) {
        int port = ModConfig.PORT.get();
        HostPick pick = resolveWebHost(ModConfig.BIND_ADDRESS.get());
        String host = pick.host();
        if (host.contains(":") && !host.startsWith("[")) {
            // IPv6 裸地址需加方括号，否则会被当成端口分隔
            host = "[" + host + "]";
        }
        StringBuilder url = new StringBuilder("http://").append(host).append(':').append(port).append('/');
        if (account != null && !account.isBlank()) {
            // Hash 路由：前端用 Vue Router query 读取 account
            url.append("#/?account=").append(urlEncode(account.trim()));
        }
        return new WebLink(url.toString(), pick.needsPublicHostHint());
    }

    private static String urlEncode(String raw) {
        try {
            return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20");
        } catch (Exception e) {
            return raw;
        }
    }

    private static HostPick resolveWebHost(String bind) {
        String configured = firstNonBlank(ModConfig.PUBLIC_HOST.get(), System.getenv("AE2LANUIS_PUBLIC_HOST"));
        if (configured != null) {
            return new HostPick(configured, false);
        }
        if (bind != null) {
            String b = bind.trim();
            if (!b.isEmpty() && !"0.0.0.0".equals(b) && !"::".equals(b) && !"*".equals(b)) {
                return new HostPick(b, false);
            }
        }
        String lan = preferPublicLanIpv4();
        if (lan != null) {
            return new HostPick(lan, false);
        }
        // Docker 等环境只有容器网段时，不要把 172.17.x 当成可点开的「网页地址」
        return new HostPick("127.0.0.1", runningInContainer());
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null) {
                String t = v.trim();
                if (!t.isEmpty()) {
                    return t;
                }
            }
        }
        return null;
    }

    /**
     * 挑选适合展示给玩家的站点本地 IPv4：跳过 docker/veth 网卡，偏好 192.168/10 网段。
     */
    private static String preferPublicLanIpv4() {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return null;
            }
            while (ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual() || isContainerishInterface(nif.getName())) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address) || !addr.isSiteLocalAddress() || addr.isLoopbackAddress()) {
                        continue;
                    }
                    int score = scoreLanIpv4(addr.getHostAddress());
                    if (score > bestScore) {
                        bestScore = score;
                        best = addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 仅剩 Docker 典型 172.16/12 地址时视为不可用（容器内探测）
        if (best != null && bestScore <= 5 && runningInContainer()) {
            return null;
        }
        return best;
    }

    /** 192.168 &gt; 10.x &gt; 其它 RFC1918（含 Docker 常用 172.16/12）。 */
    private static int scoreLanIpv4(String ip) {
        if (ip.startsWith("192.168.")) {
            return 30;
        }
        if (ip.startsWith("10.")) {
            return 20;
        }
        // 172.16.0.0/12：可能是公司网，也可能是 docker0；给低分
        if (ip.startsWith("172.")) {
            return 5;
        }
        return 1;
    }

    private static boolean isContainerishInterface(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String n = name.toLowerCase();
        return n.equals("docker0")
                || n.startsWith("br-")
                || n.startsWith("veth")
                || n.startsWith("cni")
                || n.startsWith("flannel")
                || n.startsWith("tunl")
                || n.startsWith("kube-")
                || n.equals("podman");
    }

    private static boolean runningInContainer() {
        try {
            if (Files.exists(Path.of("/.dockerenv"))) {
                return true;
            }
        } catch (Exception ignored) {
        }
        String container = System.getenv("container");
        if (container != null && !container.isBlank()) {
            return true;
        }
        String docker = System.getenv("DOCKER_CONTAINER");
        return docker != null && !docker.isBlank();
    }

    private record WebLink(String url, boolean needsPublicHostHint) {
    }

    private record HostPick(String host, boolean needsPublicHostHint) {
    }
}
