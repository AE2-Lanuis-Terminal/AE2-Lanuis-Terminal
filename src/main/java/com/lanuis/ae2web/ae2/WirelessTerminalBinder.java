package com.lanuis.ae2web.ae2;

import appeng.api.features.GridLinkables;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import com.google.gson.JsonObject;
import com.lanuis.ae2web.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;






import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从玩家身上的无线终端快照 AE 网络链接，并在 HTTP 请求时把持久化链接解析回 {@link IGrid}。
 * <p>
 * AE2 官方 API 在不同小版本对 WirelessTerminalItem / GridLinkables 签名不稳定，
 * 因此大量使用反射与多级回退：reflect linkedGrid → GridLinkables GlobalPos → WAP/getGrid。
 * 所有解析必须在服务端主线程执行（区块/方块实体访问）。
 * </p>
 */
public final class WirelessTerminalBinder {
    /**
     * 工具类禁止实例化。
     */
    private WirelessTerminalBinder() {
    }

    /**
     * 扫描玩家终端并尝试解析在线网络，成功则编码 networkLink + 摘要。
     * <p>
     * 多个候选终端时取第一个能 resolve 到 IGrid 的；
     * 全部失败返回中文失败信息，供命令直接展示。
     * </p>
     *
     * @param player 在线玩家（含维度与背包）
     * @return 成功含 JSON 链接；失败仅含 message
     */
    public static BindResult snapshotFromPlayer(ServerPlayer player) {
        List<ItemStack> terminals = findTerminals(player);
        if (terminals.isEmpty()) {
            return BindResult.fail("未找到已配置的无线终端。请将已链接的无线终端放在主手/副手/背包中。");
        }

        for (ItemStack stack : terminals) {
            // 必须当场能解析到 IGrid，否则落盘后 Web 端也会 503
            Optional<IGrid> grid = resolveGridFromStack(stack, player.serverLevel());
            if (grid.isEmpty()) {
                continue;
            }

            CompoundTag linkTag = extractLinkTag(stack);
            if (linkTag == null || linkTag.isEmpty()) {
                // 无标准 link 子标签时，退化为整份物品 NBT，保证至少可重建栈
                linkTag = stack.hasTag() ? stack.getTag().copy() : new CompoundTag();
            }

            JsonObject networkLink = NetworkLinkCodec.encode(stack, linkTag);
            JsonObject summary = new JsonObject();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            // 摘要仅供 status 展示，不参与解析
            summary.addProperty("terminalItemId", itemId.toString());
            summary.addProperty("dimension", player.level().dimension().location().toString());
            summary.addProperty("playerPos", BlockPos.of(player.blockPosition().asLong()).toShortString());

            String text = itemId + " @ " + player.level().dimension().location();
            return BindResult.ok(networkLink, summary, text);
        }

        return BindResult.fail("找到无线终端，但无法解析已链接的 AE 网络。请确认终端已在安全终端/无线接入点完成链接，且网络在线。");
    }

    /**
     * 从单个 ItemStack 解析当前可访问的 IGrid。
     * 优先反射 WirelessTerminalItem#getLinkedGrid；失败再走 GridLinkables 坐标路径。
     *
     * @param stack 终端物品
     * @param level 用于加载维度/区块的 ServerLevel（反射 API 需要 Level）
     */
    public static Optional<IGrid> resolveGridFromStack(ItemStack stack, ServerLevel level) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }

        // 首选：官方无线终端「已链接网络」直接返回 IGrid（API 名因版本而异）
        Optional<IGrid> reflected = tryReflectLinkedGrid(stack, level);
        if (reflected.isPresent()) {
            return reflected;
        }

        // 回退：GridLinkables 给出 GlobalPos/DimPos → 找 WAP 方块实体 → getGrid
        try {
            var handler = GridLinkables.get(stack.getItem());
            if (handler != null) {
                Method getPos = null;
                // 不依赖具体接口方法名：扫描「单参 ItemStack → GlobalPos/DimPos」
                for (Method m : handler.getClass().getMethods()) {
                    if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == ItemStack.class) {
                        Class<?> rt = m.getReturnType();
                        if (rt.getName().contains("GlobalPos") || rt.getName().contains("DimPos")) {
                            getPos = m;
                            break;
                        }
                    }
                }
                if (getPos != null) {
                    Object globalPos = getPos.invoke(handler, stack);
                    Optional<IGrid> fromPos = gridFromGlobalPos(level.getServer(), globalPos);
                    if (fromPos.isPresent()) {
                        return fromPos;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 反射/调用失败一律吞掉，交由上层返回 empty
            // fall through
        }

        return Optional.empty();
    }

    /**
     * 从 bindings 中的 networkLink JSON 重建栈并在所有已加载维度尝试解析网格。
     * <p>
     * 优先 overworld，再其它维度——多数 WAP 在主世界，可减少无效加载。
     * 须在主线程调用。
     * </p>
     *
     * @param networkLink 持久化链接
     * @param server      用于枚举 ServerLevel
     */
    public static Optional<IGrid> resolvePersisted(JsonObject networkLink, net.minecraft.server.MinecraftServer server) {
        if (networkLink == null) {
            return Optional.empty();
        }
        Optional<ItemStack> stack = NetworkLinkCodec.decodeToStack(networkLink);
        if (stack.isEmpty()) {
            return Optional.empty();
        }

        // 优先主世界，降低跨维度扫描成本
        List<ServerLevel> levels = new ArrayList<>();
        ServerLevel overworld = server.overworld();
        if (overworld != null) {
            levels.add(overworld);
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level != overworld) {
                levels.add(level);
            }
        }

        for (ServerLevel level : levels) {
            Optional<IGrid> grid = resolveGridFromStack(stack.get(), level);
            if (grid.isPresent()) {
                return grid;
            }
        }
        return Optional.empty();
    }

    /**
     * 反射查找名称含 linkedgrid、签名类似 (ItemStack, Level[, ...]) 的方法并调用。
     * AE2 小版本方法重载差异（多一个 player/null 参数）在此兼容。
     */
    private static Optional<IGrid> tryReflectLinkedGrid(ItemStack stack, Level level) {
        try {
            Item item = stack.getItem();
            Method method = null;
            for (Method m : item.getClass().getMethods()) {
                // 不硬编码 getLinkedGrid，兼容混淆前后与重命名
                if (!m.getName().toLowerCase().contains("linkedgrid")) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length >= 2 && params[0] == ItemStack.class && Level.class.isAssignableFrom(params[1])) {
                    method = m;
                    break;
                }
            }
            if (method == null) {
                return Optional.empty();
            }
            Object result;
            if (method.getParameterCount() == 2) {
                result = method.invoke(item, stack, level);
            } else {
                // 三参重载常见于需要 @Nullable Player；离线场景传 null
                result = method.invoke(item, stack, level, null);
            }
            if (result instanceof IGrid grid) {
                return Optional.of(grid);
            }
        } catch (Throwable ignored) {
            // 链接失效/网络离线时 AE2 可能抛异常，视为无网格
        }
        return Optional.empty();
    }

    /**
     * 从 GlobalPos 风格对象取出维度与坐标，定位无线接入点并取 IGrid。
     * 可选强制加载锚点区块；非 WAP 方块则反射 getGrid / MainNode。
     */
    private static Optional<IGrid> gridFromGlobalPos(net.minecraft.server.MinecraftServer server, Object globalPos) {
        if (globalPos == null || server == null) {
            return Optional.empty();
        }
        try {
            Method dimension = globalPos.getClass().getMethod("dimension");
            Method pos = globalPos.getClass().getMethod("pos");
            Object dimKey = dimension.invoke(globalPos);
            BlockPos blockPos = (BlockPos) pos.invoke(globalPos);

            ServerLevel level = null;
            if (dimKey instanceof ResourceKey<?> key) {
                @SuppressWarnings("unchecked")
                ResourceKey<Level> levelKey = (ResourceKey<Level>) key;
                level = server.getLevel(levelKey);
            }
            if (level == null) {
                // 维度未加载或模组维度已移除
                return Optional.empty();
            }

            // 未加载区块时 getBlockEntity 常为 null；配置允许则强制加载
            if (Boolean.TRUE.equals(ModConfig.FORCE_LOAD_ANCHOR_CHUNK.get())) {
                level.getChunk(blockPos);
            }

            BlockEntity be = level.getBlockEntity(blockPos);
            if (be instanceof IWirelessAccessPoint wap) {
                IGrid grid = wap.getGrid();
                return Optional.ofNullable(grid);
            }

            // 通用回退：其它模组接入点可能只暴露 getGrid / getMainNode
            if (be != null) {
                for (Method m : be.getClass().getMethods()) {
                    if (m.getParameterCount() == 0 && IGrid.class.isAssignableFrom(m.getReturnType())) {
                        Object g = m.invoke(be);
                        if (g instanceof IGrid grid) {
                            return Optional.of(grid);
                        }
                    }
                    if (m.getParameterCount() == 0 && m.getName().contains("MainNode")) {
                        Object node = m.invoke(be);
                        if (node instanceof IGridNode gridNode) {
                            return Optional.ofNullable(gridNode.getGrid());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 反射失败 → empty
        }
        return Optional.empty();
    }

    /**
     * 从物品 NBT 提取「链接」相关子树。
     * 优先 {@code link} 复合标签；否则若含能量/加密键等 AE2 特征则整份拷贝。
     */
    private static CompoundTag extractLinkTag(ItemStack stack) {
        if (!stack.hasTag()) {
            return null;
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return null;
        }
        // 常见 AE2 子键；缺失时保留全量 tag，避免丢 encryptionKey
        if (tag.contains("link")) {
            return tag.getCompound("link").copy();
        }
        if (tag.contains("internalCurrentPower") || tag.contains("encryptionKey") || tag.getAllKeys().stream().anyMatch(k -> k.toLowerCase().contains("link"))) {
            return tag.copy();
        }
        return tag.copy();
    }

    /**
     * 按配置扫描主手、副手、背包、末影箱中的允许终端物品。
     * 顺序即绑定优先级（先找到且可解析者优先）。
     */
    private static List<ItemStack> findTerminals(ServerPlayer player) {
        Set<ResourceLocation> allowed = ModConfig.TERMINAL_ITEM_IDS.get().stream()
                .map(ResourceLocation::tryParse)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        List<ItemStack> found = new ArrayList<>();
        ItemStack main = player.getMainHandItem();
        if (isAllowedTerminal(main, allowed)) {
            found.add(main);
        }
        if (Boolean.TRUE.equals(ModConfig.SCAN_OFFHAND.get())) {
            ItemStack off = player.getOffhandItem();
            if (isAllowedTerminal(off, allowed)) {
                found.add(off);
            }
        }
        if (Boolean.TRUE.equals(ModConfig.SCAN_INVENTORY.get())) {
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (isAllowedTerminal(stack, allowed)) {
                    found.add(stack);
                }
            }
        }
        if (Boolean.TRUE.equals(ModConfig.SCAN_ENDER_CHEST.get())) {
            var ender = player.getEnderChestInventory();
            for (int i = 0; i < ender.getContainerSize(); i++) {
                ItemStack stack = ender.getItem(i);
                if (isAllowedTerminal(stack, allowed)) {
                    found.add(stack);
                }
            }
        }
        return found;
    }

    /**
     * 物品在白名单内，且注册了 GridLinkables 或路径名含 wireless。
     * 后者兜底部分未正确注册 linkable 的整合包终端。
     */
    private static boolean isAllowedTerminal(ItemStack stack, Set<ResourceLocation> allowed) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!allowed.contains(id)) {
            return false;
        }
        // GridLinkables 非 null 表示可被 AE2 链接系统识别
        return GridLinkables.get(stack.getItem()) != null || id.getPath().contains("wireless");
    }

    /**
     * 绑定尝试结果：成功携带可持久化 JSON，失败仅消息。
     *
     * @param ok           是否成功
     * @param message      状态或错误文案
     * @param networkLink  编码后的链接
     * @param linkSummary  摘要
     * @param summaryText  命令成功提示短句
     */
    public record BindResult(boolean ok, String message, JsonObject networkLink, JsonObject linkSummary, String summaryText) {
        /**
         * 构造成功结果。
         */
        static BindResult ok(JsonObject link, JsonObject summary, String text) {
            return new BindResult(true, "ok", link, summary, text);
        }

        /**
         * 构造失败结果；链接字段为 null。
         */
        static BindResult fail(String message) {
            return new BindResult(false, message, null, null, null);
        }
    }
}
