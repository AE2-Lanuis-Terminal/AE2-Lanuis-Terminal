package com.lanuis.ae2web.client.icon;

import appeng.api.stacks.AEKey;
import com.lanuis.ae2web.Ae2LanuisMod;
import com.lanuis.ae2web.config.ModConfig;
import com.lanuis.ae2web.icon.IconResourcePaths;
import com.lanuis.ae2web.icon.IconResourceStore;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 仅 Dist.CLIENT：resources render/cancel/status/count/clear。
 */
public final class ClientIconBootstrap {
    private ClientIconBootstrap() {
    }

    /** 由模组入口经反射调用，避免专用服加载本类。 */
    public static void init() {
        MinecraftForge.EVENT_BUS.register(new ClientIconBootstrap());
        Ae2LanuisMod.LOGGER.info("AE2 Lanuis client icon bake hooks registered");
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(resourcesCommand());
    }

    @SubscribeEvent
    public void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            AeKeyIconRenderer renderer = AeKeyIconRenderer.getInstance();
            if (renderer != null) {
                renderer.next();
            }
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> resourcesCommand() {
        return Commands.literal("ae2lanuis")
                .then(Commands.literal("resources")
                        .then(Commands.literal("render")
                                .executes(ctx -> startRender(ctx.getSource(), 1, 128))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> startRender(
                                                ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "limit"),
                                                128
                                        ))
                                        .then(Commands.argument("size", IntegerArgumentType.integer(16, 512))
                                                .executes(ctx -> startRender(
                                                        ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "limit"),
                                                        IntegerArgumentType.getInteger(ctx, "size")
                                                )))))
                        .then(Commands.literal("cancel")
                                .executes(ctx -> cancel(ctx.getSource())))
                        .then(Commands.literal("status")
                                .executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("count")
                                .executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("clear")
                                .executes(ctx -> clear(ctx.getSource()))));
    }

    private static int startRender(CommandSourceStack source, int limit, int size) {
        if (AeKeyIconRenderer.isBusy()) {
            source.sendFailure(Component.literal("图标烘焙已在进行中；可 /ae2lanuis resources cancel"));
            return 0;
        }
        Path base = Path.of(ModConfig.ICON_RESOURCES_DIR.get()).toAbsolutePath().normalize();
        boolean ok = AeKeyIconRenderer.start(base, limit, size, new ChatProgressSink(source));
        if (!ok) {
            source.sendFailure(Component.literal("无法启动图标烘焙"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "已开始烘焙图标 → " + base + "（每帧 " + limit + " 张，" + size + "px）"
        ), false);
        return 1;
    }

    private static int cancel(CommandSourceStack source) {
        if (!AeKeyIconRenderer.cancel()) {
            source.sendSuccess(() -> Component.literal("当前没有进行中的烘焙"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("已取消图标烘焙"), false);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        Path base = Path.of(ModConfig.ICON_RESOURCES_DIR.get()).toAbsolutePath().normalize();
        IconResourceStore.CountResult c = IconResourceStore.countPngs();
        source.sendSuccess(() -> Component.literal("资源目录：" + base), false);
        source.sendSuccess(() -> Component.literal(
                "烘焙：" + AeKeyIconRenderer.statusText().getString()
                        + "；剩余任务：" + AeKeyIconRenderer.remainingTasks()
        ), false);
        source.sendSuccess(() -> Component.literal(
                "PNG：item=" + c.items() + " fluid=" + c.fluids() + " total=" + c.total()
        ), false);
        return 1;
    }

    private static int clear(CommandSourceStack source) {
        if (AeKeyIconRenderer.isBusy()) {
            source.sendFailure(Component.literal("请先 /ae2lanuis resources cancel 再清空"));
            return 0;
        }
        try {
            int n = IconResourceStore.clearAll();
            source.sendSuccess(() -> Component.literal(
                    "已删除 " + n + " 个 PNG（目录：" + IconResourceStore.rootDir() + "）"
            ), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("清空失败：" + e.getMessage()));
            return 0;
        }
    }

    private static final class ChatProgressSink implements AeKeyIconRenderer.ProgressSink {
        private final CommandSourceStack source;

        private ChatProgressSink(CommandSourceStack source) {
            this.source = source;
        }

        @Override
        public void onStarted(int queued) {
            source.sendSystemMessage(Component.literal("图标队列已排入 " + queued + " 个任务"));
        }

        @Override
        public void onProviderTotal(String kind, int total) {
            source.sendSystemMessage(Component.literal("烘焙 " + kind + "：共 " + total + " 项"));
        }

        @Override
        public void onProgress(String kind, int index, int total, AEKey key) {
            source.sendSystemMessage(Component.literal(
                    "进度 " + kind + " " + (index + 1) + "/" + total + " " + key.getId()
            ));
        }

        @Override
        public void onCompleted() {
            source.sendSystemMessage(Component.literal(
                    "图标烘焙完成，请将 " + IconResourcePaths.DEFAULT_DIR + "/ 上传到专用服游戏根目录"
            ));
        }
    }
}
