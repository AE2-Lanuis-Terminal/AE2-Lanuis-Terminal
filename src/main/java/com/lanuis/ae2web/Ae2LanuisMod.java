package com.lanuis.ae2web;

import com.lanuis.ae2web.auth.ModAdminCommands;
import com.lanuis.ae2web.auth.PasswordCommands;
import com.lanuis.ae2web.config.ModConfig;
import com.lanuis.ae2web.http.HttpServerLifecycle;
import com.lanuis.ae2web.icon.IconResourceCommands;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import org.slf4j.Logger;

/**
 * Forge 模组入口：注册服务端配置、游戏内命令，并在服务器启停时托管内嵌 HTTP / WebSocket。
 * <p>
 * 本模组为纯服务端（{@code mods.toml side=SERVER}）：联机玩家无需客户端模组。
 * 单人世界可选执行图标烘焙命令；专用服只静态分发 {@code aeKeyResources/}。
 * 生命周期与世界存档绑定：启动时读 bindings、开监听；停止时关停线程池。
 * </p>
 */
@Mod(Ae2LanuisMod.MOD_ID)
public class Ae2LanuisMod {
    /** Forge/资源命名空间，须与 mods.toml 一致。 */
    public static final String MOD_ID = "ae2lanuis";
    /** 对外健康检查与日志中的服务名。 */
    public static final String SERVICE_NAME = "ae2lanuis";
    /** 对外暴露的协议版本号（健康检查 JSON）。 */
    public static final String VERSION = "0.1.0";
    /** 模组统一日志器；HTTP/绑定/合成路径共用。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 模组构造：尽早注册 SERVER 配置，并把本实例挂到 Forge 事件总线。
     * <p>
     * 使用 SERVER 配置类型，保证仅在专用服/集成服侧生效，避免客户端误开端口。
     * </p>
     */
    public Ae2LanuisMod() {
        // SERVER 作用域：端口/会话 TTL 等只应在服务端加载
        ModLoadingContext.get().registerConfig(Type.SERVER, ModConfig.SPEC);
        // 订阅服务器启停与命令注册（非 Mod 总线）
        MinecraftForge.EVENT_BUS.register(this);
        // 单人：挂接客户端烘焙钩子（反射，专用服不加载 client 类）
        IconResourceCommands.initClientHooks();
    }

    /**
     * 注册 {@code /ae2lanuis} 命令树（help/password/status/http/bindings/resources）。
     * 须在玩家进服前完成，否则首次登录无法绑定无线终端。
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        PasswordCommands.register(event.getDispatcher());
        ModAdminCommands.register(event.getDispatcher());
        IconResourceCommands.register(event.getDispatcher());
    }

    /**
     * 服务器启动完成：初始化绑定存储并按配置启动 HTTP。
     * 此时 Level 已可用，BindingStore 可解析世界相对路径。
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        HttpServerLifecycle.start(event.getServer());
    }

    /**
     * 服务器停止：先停 HTTP，再清空对 MinecraftServer 的引用。
     * 防止停止过程中仍有请求进入主线程访问已卸载世界。
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        HttpServerLifecycle.stop();
    }
}
