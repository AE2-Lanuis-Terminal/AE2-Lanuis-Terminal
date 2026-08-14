package com.lanuis.ae2web.icon;

import com.lanuis.ae2web.Ae2LanuisMod;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 服务端 resources 命令：status/count/clear；render/cancel 在专用服提示去单人。
 */
public final class IconResourceCommands {
    private IconResourceCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("ae2lanuis")
                        .then(Commands.literal("resources")
                                .then(Commands.literal("render")
                                        .executes(ctx -> dedicatedRenderHint(ctx.getSource())))
                                .then(Commands.literal("cancel")
                                        .executes(ctx -> dedicatedCancelHint(ctx.getSource())))
                                .then(Commands.literal("status")
                                        .executes(ctx -> status(ctx.getSource())))
                                .then(Commands.literal("count")
                                        .executes(ctx -> count(ctx.getSource())))
                                .then(Commands.literal("clear")
                                        .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                        .executes(ctx -> clear(ctx.getSource()))))
        );
    }

    private static int dedicatedRenderHint(CommandSourceStack source) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            source.sendSuccess(() -> Component.literal(
                    "请使用客户端命令 /ae2lanuis resources render（在聊天栏执行即可）"
            ), false);
            return 1;
        }
        source.sendFailure(Component.literal(
                "图标须在单人世界烘焙：装齐模组后执行 /ae2lanuis resources render，再将 aeKeyResources/ 拷到本服根目录"
        ));
        return 0;
    }

    private static int dedicatedCancelHint(CommandSourceStack source) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            source.sendSuccess(() -> Component.literal(
                    "请使用客户端命令 /ae2lanuis resources cancel"
            ), false);
            return 1;
        }
        source.sendFailure(Component.literal("取消烘焙仅在单人世界有效"));
        return 0;
    }

    private static int status(CommandSourceStack source) {
        IconResourceStore.CountResult c = IconResourceStore.countPngs();
        Path base = c.root();
        source.sendSuccess(() -> Component.literal(
                "资源目录：" + base + (c.exists() ? "（存在）" : "（缺失）")
        ), false);
        source.sendSuccess(() -> Component.literal(
                "PNG：item=" + c.items() + " fluid=" + c.fluids() + " total=" + c.total()
        ), false);
        return 1;
    }

    private static int count(CommandSourceStack source) {
        return status(source);
    }

    private static int clear(CommandSourceStack source) {
        try {
            int n = IconResourceStore.clearAll();
            source.sendSuccess(() -> Component.literal("已删除 " + n + " 个 PNG（目录：" + IconResourceStore.rootDir() + "）"), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("清空失败：" + e.getMessage()));
            Ae2LanuisMod.LOGGER.warn("resources clear failed", e);
            return 0;
        }
    }

    /** 模组构造期挂接客户端烘焙钩子。 */
    public static void initClientHooks() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        try {
            Class<?> cl = Class.forName("com.lanuis.ae2web.client.icon.ClientIconBootstrap");
            cl.getMethod("init").invoke(null);
        } catch (ReflectiveOperationException e) {
            Ae2LanuisMod.LOGGER.error("Failed to init client icon bake hooks", e);
        }
    }
}
