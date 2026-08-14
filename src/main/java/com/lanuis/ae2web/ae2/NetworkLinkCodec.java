package com.lanuis.ae2web.ae2;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * AE2 无线终端「网络链接」与持久化 JSON 之间的编解码。
 * <p>
 * 绑定存档不保存完整物品实例，只存 itemId + NBT payload 字符串，
 * 以便离线/跨维度后仍能重建可被 {@link WirelessTerminalBinder} 解析的 ItemStack。
 * version/kind 字段预留向前兼容，旧数据可按版本分支迁移。
 * </p>
 */
public final class NetworkLinkCodec {
    /**
     * 当前编解码协议版本；bump 时须同时兼容读旧 payload。
     */
    public static final int VERSION = 1;

    /**
     * 工具类禁止实例化。
     */
    private NetworkLinkCodec() {
    }

    /**
     * 将终端物品与链接 NBT 编码为可写入 bindings.json 的 JSON。
     * <p>
     * payload 使用 NBT {@code toString()} 文本，解码时走 {@link TagParser}；
     * linkTag 为空时写 "{}"，避免 Gson 省略字段导致读侧歧义。
     * </p>
     *
     * @param stack   已链接的无线终端物品（用于取注册名）
     * @param linkTag 链接相关 NBT（可为 null，表示空复合标签）
     * @return 含 version/kind/itemId/payload 的对象
     */
    public static JsonObject encode(ItemStack stack, CompoundTag linkTag) {
        JsonObject obj = new JsonObject();
        // 协议头：读写双方据此决定是否迁移
        obj.addProperty("version", VERSION);
        // kind 标明这是 AE2 GridLinkable 快照，而非坐标硬编码
        obj.addProperty("kind", "ae2_grid_linkable");
        // 用注册表 id，避免显示名/翻译键不稳定
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        obj.addProperty("itemId", id.toString());
        // SNBT 文本；null 视为空标签，保证字段始终存在
        obj.addProperty("payload", linkTag == null ? "{}" : linkTag.toString());
        return obj;
    }

    /**
     * 从持久化 JSON 重建临时 ItemStack，供解析 IGrid 使用。
     * <p>
     * 物品 id 非法、注册表缺失或 payload 解析失败时返回 empty，
     * 调用方应视为「网络不可用」而非抛异常（HTTP 映射 503）。
     * </p>
     *
     * @param networkLink 绑定记录中的 networkLink 字段
     * @return 带 NBT 的栈；失败为空
     */
    public static Optional<ItemStack> decodeToStack(JsonObject networkLink) {
        // 缺 itemId：旧损坏数据或手动编辑错误
        if (networkLink == null || !networkLink.has("itemId")) {
            return Optional.empty();
        }
        ResourceLocation id = ResourceLocation.tryParse(networkLink.get("itemId").getAsString());
        // 模组卸载后物品消失时，containsKey 为 false
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return Optional.empty();
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        ItemStack stack = new ItemStack(item);
        // payload 可选：无则仅靠 GridLinkables 默认行为（通常失败）
        if (networkLink.has("payload")) {
            try {
                // SNBT → CompoundTag；语法错误视为整条链接失效
                CompoundTag tag = TagParser.parseTag(networkLink.get("payload").getAsString());
                stack.setTag(tag);
            } catch (Exception ignored) {
                // 不吞掉原因到日志：调用链上层已有 network_unavailable
                return Optional.empty();
            }
        }
        return Optional.of(stack);
    }
}
