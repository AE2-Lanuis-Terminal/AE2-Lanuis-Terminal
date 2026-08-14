package com.lanuis.ae2web.auth;

import com.lanuis.ae2web.config.ModConfig;
import com.lanuis.ae2web.http.HttpServerLifecycle;
import com.lanuis.ae2web.http.StorageWsServer;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 管理向命令：help / http / bindings。
 */
public final class ModAdminCommands {
    private ModAdminCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("ae2lanuis")
                        .then(Commands.literal("help")
                                .executes(ctx -> help(ctx.getSource())))
                        .then(Commands.literal("http")
                                .executes(ctx -> httpStatus(ctx.getSource())))
                        .then(Commands.literal("bindings")
                                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(ctx -> listBindings(ctx.getSource())))
        );
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("—— AE2 Lanuis 命令 ——"), false);
        source.sendSuccess(() -> Component.literal("/ae2lanuis help — 本帮助"), false);
        source.sendSuccess(() -> Component.literal("/ae2lanuis password <密码> — 设网页密码并绑定当前无线终端网络"), false);
        source.sendSuccess(() -> Component.literal("/ae2lanuis password clear — 清除本人绑定"), false);
        source.sendSuccess(() -> Component.literal("/ae2lanuis status — 本人绑定与网页地址"), false);
        source.sendSuccess(() -> Component.literal("/ae2lanuis http — HTTP / WebSocket 监听状态"), false);
        source.sendSuccess(() -> Component.literal("/ae2lanuis bindings — 列出全部绑定（需权限等级 ≥2）"), false);
        source.sendSuccess(() -> Component.literal("Web 管理页：OP 登录后可见「管理」标签（GET /api/v1/admin/bindings）"), false);
        source.sendSuccess(() -> Component.literal("/ae2lanuis resources status|count — 图标资源目录状态/张数"), false);
        source.sendSuccess(() -> Component.literal("/ae2lanuis resources render [limit] [size] — 单人烘焙图标"), false);
        source.sendSuccess(() -> Component.literal("/ae2lanuis resources cancel — 取消进行中的烘焙（单人）"), false);
        source.sendSuccess(() -> Component.literal("/ae2lanuis resources clear — 清空预烘焙 PNG（需权限等级 ≥2）"), false);
        return 1;
    }

    private static int httpStatus(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(HttpServerLifecycle.statusSummary()), false);
        var ws = StorageWsServer.statusJson();
        source.sendSuccess(() -> Component.literal(
                "WS enabled=" + ws.get("enabled").getAsBoolean()
                        + " running=" + ws.get("running").getAsBoolean()
                        + " port=" + ws.get("port").getAsInt()
                        + " pushIntervalMs=" + ws.get("pushIntervalMs").getAsInt()
                        + " bind=" + ModConfig.BIND_ADDRESS.get()
        ), false);
        return 1;
    }

    private static int listBindings(CommandSourceStack source) {
        List<String> lines = BindingStore.listBindingLines();
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.literal("当前无网页绑定"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("绑定共 " + lines.size() + " 条："), false);
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        return 1;
    }
}
