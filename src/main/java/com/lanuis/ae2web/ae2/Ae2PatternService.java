package com.lanuis.ae2web.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.crafting.pattern.AEProcessingPattern;
import appeng.crafting.pattern.AESmithingTablePattern;
import appeng.crafting.pattern.AEStonecuttingPattern;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lanuis.ae2web.config.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 只读枚举 ME 网络样板：按 {@link ICraftingProvider}（样板供应器等）归属，供 Patterns Tab。
 */
public final class Ae2PatternService {
    private Ae2PatternService() {
    }

    public static JsonObject list(
            IGrid grid,
            String qOutput,
            String qInput,
            String modeFilter,
            int page,
            int pageSize
    ) {
        int size = Math.min(Math.max(1, pageSize), ModConfig.MAX_INVENTORY_PAGE_SIZE.get());
        int p = Math.max(1, page);
        List<JsonObject> all = new ArrayList<>();
        for (Ae2PatternBoardService.SlottedPattern entry : Ae2PatternBoardService.collectSlottedPatterns(grid)) {
            JsonObject dto = toPatternDto(entry.pattern(), entry.provider(), entry.slotIndex());
            if (dto == null) {
                continue;
            }
            if (!modeMatches(dto, modeFilter)) {
                continue;
            }
            if (!queryMatches(dto, qOutput, qInput)) {
                continue;
            }
            all.add(dto);
        }
        // 兜底：未出现在供应器库存中的 craftable 样板
        for (PatternEntry entry : collectOrphanPatternEntries(grid, all)) {
            JsonObject dto = toPatternDto(entry.pattern(), entry.provider(), -1);
            if (dto == null) {
                continue;
            }
            if (!modeMatches(dto, modeFilter) || !queryMatches(dto, qOutput, qInput)) {
                continue;
            }
            all.add(dto);
        }
        all.sort(Comparator
                .comparing((JsonObject o) -> providerSortKey(o), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(o -> stripSectionCodes(patternNameOf(o)), String.CASE_INSENSITIVE_ORDER));

        int total = all.size();
        int from = Math.min((p - 1) * size, total);
        int to = Math.min(from + size, total);
        JsonArray items = new JsonArray();
        for (int i = from; i < to; i++) {
            items.add(all.get(i));
        }
        JsonObject root = new JsonObject();
        root.addProperty("page", p);
        root.addProperty("pageSize", size);
        root.addProperty("total", total);
        root.add("items", items);
        return root;
    }

    private record PatternEntry(IPatternDetails pattern, JsonObject provider) {
    }

    /** 供 Board 服务复用：按节点构建供应器 DTO。 */
    static JsonObject providerDtoForNode(IGridNode node, ICraftingProvider craftingProvider) {
        return toProviderDto(node, craftingProvider);
    }

    static JsonObject toPatternDtoPublic(IPatternDetails pattern, JsonObject provider, int slotIndex) {
        return toPatternDto(pattern, provider, slotIndex);
    }

    static boolean modeMatchesPublic(JsonObject dto, String modeFilter) {
        return modeMatches(dto, modeFilter);
    }

    static boolean queryMatchesPublic(JsonObject dto, String qOutput, String qInput) {
        return queryMatches(dto, qOutput, qInput);
    }

    /**
     * 按供应器收集样板；同一样板可出现在多个 provider。
     * 未挂到任何 ICraftingProvider 的样板归入 unknown（兜底）。
     */
    static List<PatternEntry> collectPatternEntries(IGrid grid) {
        List<PatternEntry> out = new ArrayList<>();
        for (Ae2PatternBoardService.SlottedPattern sp : Ae2PatternBoardService.collectSlottedPatterns(grid)) {
            out.add(new PatternEntry(sp.pattern(), sp.provider()));
        }
        for (PatternEntry orphan : collectOrphanPatternEntries(grid, List.of())) {
            out.add(orphan);
        }
        return out;
    }

    /** craftables 中未被供应器库存覆盖的样板。 */
    private static List<PatternEntry> collectOrphanPatternEntries(IGrid grid, List<JsonObject> alreadyListed) {
        IdentityHashMap<IPatternDetails, Boolean> covered = new IdentityHashMap<>();
        Set<String> listedIds = new HashSet<>();
        for (JsonObject dto : alreadyListed) {
            if (dto.has("id")) {
                listedIds.add(dto.get("id").getAsString());
            }
        }
        for (Ae2PatternBoardService.SlottedPattern sp : Ae2PatternBoardService.collectSlottedPatterns(grid)) {
            covered.put(sp.pattern(), Boolean.TRUE);
        }

        List<PatternEntry> out = new ArrayList<>();
        ICraftingService crafting = grid.getCraftingService();
        Set<AEKey> craftables = new HashSet<>();
        craftables.addAll(crafting.getCraftables(AEItemKey.filter()));
        craftables.addAll(crafting.getCraftables(AEFluidKey.filter()));
        JsonObject unknown = unknownProviderDto();
        for (AEKey key : craftables) {
            for (IPatternDetails pattern : crafting.getCraftingFor(key)) {
                if (pattern == null || covered.containsKey(pattern)) {
                    continue;
                }
                covered.put(pattern, Boolean.TRUE);
                String fingerprint = patternId(pattern);
                String id = fingerprint + "@unknown";
                if (listedIds.contains(id)) {
                    continue;
                }
                out.add(new PatternEntry(pattern, unknown));
            }
        }
        return out;
    }

    static String patternMode(IPatternDetails pattern) {
        if (pattern instanceof AECraftingPattern) {
            return "crafting";
        }
        if (pattern instanceof AEProcessingPattern) {
            return "processing";
        }
        if (pattern instanceof AESmithingTablePattern) {
            return "smithing";
        }
        if (pattern instanceof AEStonecuttingPattern) {
            return "stonecutting";
        }
        return "other";
    }

    /** 样板内容指纹（不含供应器）。 */
    static String patternId(IPatternDetails pattern) {
        StringBuilder sb = new StringBuilder();
        AEItemKey def = pattern.getDefinition();
        if (def != null) {
            sb.append(def.toString());
        }
        sb.append('|').append(patternMode(pattern));
        for (GenericStack out : pattern.getOutputs()) {
            if (out != null && out.what() != null) {
                sb.append('|').append(out.what()).append('x').append(out.amount());
            }
        }
        for (IPatternDetails.IInput in : pattern.getInputs()) {
            if (in == null) {
                continue;
            }
            GenericStack[] possible = in.getPossibleInputs();
            if (possible != null && possible.length > 0 && possible[0] != null && possible[0].what() != null) {
                sb.append('|').append(possible[0].what()).append('*').append(in.getMultiplier());
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    private static JsonObject toProviderDto(IGridNode node, ICraftingProvider craftingProvider) {
        Object owner = node.getOwner();
        BlockPos pos = null;
        String dimension = "unknown";
        String selfName = "Unknown";
        String face = "";
        BlockEntity hostBe = null;

        ServerLevel level = null;
        try {
            level = node.getLevel();
        } catch (Throwable ignored) {
        }
        if (level != null) {
            dimension = level.dimension().location().toString();
        }

        if (owner instanceof BlockEntity be) {
            hostBe = be;
            pos = be.getBlockPos();
            if (be.getLevel() != null) {
                dimension = be.getLevel().dimension().location().toString();
                if (level == null && be.getLevel() instanceof ServerLevel sl) {
                    level = sl;
                }
            }
            selfName = resolveBlockName(be);
        } else if (owner != null) {
            hostBe = reflectPartHostBlockEntity(owner);
            if (hostBe != null) {
                pos = hostBe.getBlockPos();
                if (hostBe.getLevel() != null) {
                    dimension = hostBe.getLevel().dimension().location().toString();
                    if (level == null && hostBe.getLevel() instanceof ServerLevel sl) {
                        level = sl;
                    }
                }
            }
            Direction side = reflectPartSide(owner);
            if (side != null) {
                face = side.getSerializedName();
            }
            selfName = resolveOwnerName(owner);
        }

        Object logic = reflectGetLogic(owner);
        if (logic == null && hostBe != null) {
            logic = reflectGetLogic(hostBe);
        }

        JsonArray targets = new JsonArray();
        if (level != null && pos != null) {
            for (Direction side : resolvePushSides(logic, face)) {
                BlockPos adj = pos.relative(side);
                JsonObject target = resolveFacingTarget(level, adj, side, dimension);
                if (target != null) {
                    targets.add(target);
                }
            }
        }

        // 对齐 Pattern Access Terminal：自定义名 > 朝向机器名 > 供应器自身
        String displayName = selfName;
        String custom = resolveCustomName(owner, hostBe);
        if (custom != null) {
            displayName = custom;
        } else if (targets.size() == 1) {
            displayName = targets.get(0).getAsJsonObject().get("name").getAsString();
        } else if (targets.size() > 1) {
            String first = targets.get(0).getAsJsonObject().get("name").getAsString();
            boolean allSame = true;
            for (int i = 1; i < targets.size(); i++) {
                if (!first.equals(targets.get(i).getAsJsonObject().get("name").getAsString())) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) {
                displayName = first;
            }
        }

        String id;
        if (pos != null) {
            id = dimension + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + (face.isEmpty() ? "" : ":" + face);
        } else {
            id = "unknown:" + System.identityHashCode(node);
        }

        JsonObject dto = new JsonObject();
        dto.addProperty("id", id);
        dto.addProperty("name", displayName);
        dto.addProperty("priority", craftingProvider.getPatternPriority());
        dto.addProperty("movable", false);
        dto.addProperty("slotCount", 0);
        dto.addProperty("usedSlots", 0);
        if (pos != null) {
            JsonObject p = new JsonObject();
            p.addProperty("x", pos.getX());
            p.addProperty("y", pos.getY());
            p.addProperty("z", pos.getZ());
            p.addProperty("dimension", dimension);
            dto.add("pos", p);
        }
        dto.add("targets", targets);
        return dto;
    }

    private static JsonObject unknownProviderDto() {
        JsonObject dto = new JsonObject();
        dto.addProperty("id", "unknown");
        dto.addProperty("name", "Unknown");
        dto.addProperty("priority", 0);
        dto.add("targets", new JsonArray());
        return dto;
    }

    /** 供应器或宿主自定义名（砧重命名等）。 */
    private static String resolveCustomName(Object owner, BlockEntity hostBe) {
        String fromOwner = nameableCustom(owner);
        if (fromOwner != null) {
            return fromOwner;
        }
        return nameableCustom(hostBe);
    }

    private static String nameableCustom(Object obj) {
        if (!(obj instanceof net.minecraft.world.Nameable nameable)) {
            return null;
        }
        try {
            if (nameable.hasCustomName() && nameable.getCustomName() != null) {
                String s = nameable.getCustomName().getString();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object reflectGetLogic(Object host) {
        if (host == null) {
            return null;
        }
        try {
            Method getLogic = host.getClass().getMethod("getLogic");
            return getLogic.invoke(host);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.EnumSet<Direction> resolvePushSides(Object logic, String face) {
        if (logic != null) {
            try {
                Method m = logic.getClass().getMethod("getActiveSides");
                Object r = m.invoke(logic);
                if (r instanceof java.util.EnumSet<?> set) {
                    java.util.EnumSet<Direction> out = java.util.EnumSet.noneOf(Direction.class);
                    for (Object o : set) {
                        if (o instanceof Direction d) {
                            out.add(d);
                        }
                    }
                    if (!out.isEmpty()) {
                        return out;
                    }
                } else if (r instanceof java.util.Collection<?> col) {
                    java.util.EnumSet<Direction> out = java.util.EnumSet.noneOf(Direction.class);
                    for (Object o : col) {
                        if (o instanceof Direction d) {
                            out.add(d);
                        }
                    }
                    if (!out.isEmpty()) {
                        return out;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (face != null && !face.isBlank()) {
            Direction d = Direction.byName(face);
            if (d != null) {
                return java.util.EnumSet.of(d);
            }
        }
        return java.util.EnumSet.allOf(Direction.class);
    }

    /**
     * 朝向邻接方块：优先 AE2 PatternContainerGroup，否则 Nameable / 方块名。
     */
    private static JsonObject resolveFacingTarget(ServerLevel level, BlockPos adj, Direction side, String dimension) {
        if (level.getBlockState(adj).isAir()) {
            return null;
        }
        String name = null;
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(adj).getBlock())
                .toString();

        Object group = reflectPatternContainerGroup(level, adj, side.getOpposite());
        if (group != null) {
            name = reflectGroupName(group);
        }
        if (name == null || name.isBlank()) {
            BlockEntity be = level.getBlockEntity(adj);
            String custom = nameableCustom(be);
            if (custom != null) {
                name = custom;
            } else if (be instanceof net.minecraft.world.Nameable nameable) {
                try {
                    name = nameable.getName().getString();
                } catch (Throwable ignored) {
                }
            }
            if (name == null || name.isBlank()) {
                try {
                    Component c = level.getBlockState(adj).getBlock().getName();
                    name = c != null ? c.getString() : blockId;
                } catch (Throwable t) {
                    name = blockId;
                }
            }
        }

        JsonObject target = new JsonObject();
        target.addProperty("name", name);
        target.addProperty("blockId", blockId);
        target.addProperty("side", side.getSerializedName());
        JsonObject p = new JsonObject();
        p.addProperty("x", adj.getX());
        p.addProperty("y", adj.getY());
        p.addProperty("z", adj.getZ());
        p.addProperty("dimension", dimension);
        target.add("pos", p);
        return target;
    }

    private static Object reflectPatternContainerGroup(ServerLevel level, BlockPos pos, Direction fromSide) {
        String[] classes = {
                "appeng.api.implementations.blockentities.PatternContainerGroup",
                "appeng.helpers.patternprovider.PatternContainerGroup"
        };
        for (String cn : classes) {
            try {
                Class<?> clazz = Class.forName(cn);
                Method fromMachine = clazz.getMethod(
                        "fromMachine",
                        net.minecraft.world.level.Level.class,
                        BlockPos.class,
                        Direction.class
                );
                return fromMachine.invoke(null, level, pos, fromSide);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String reflectGroupName(Object group) {
        for (String method : new String[]{"name", "getName", "getDisplayName"}) {
            try {
                Method m = group.getClass().getMethod(method);
                Object r = m.invoke(group);
                if (r instanceof Component c) {
                    String s = c.getString();
                    if (s != null && !s.isBlank()) {
                        return s;
                    }
                } else if (r != null) {
                    String s = r.toString();
                    if (!s.isBlank()) {
                        return s;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // record 组件字段
        try {
            var field = group.getClass().getDeclaredField("name");
            field.setAccessible(true);
            Object r = field.get(group);
            if (r instanceof Component c) {
                return c.getString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String resolveBlockName(BlockEntity be) {
        String custom = nameableCustom(be);
        if (custom != null) {
            return custom;
        }
        try {
            Component c = be.getBlockState().getBlock().getName();
            if (c != null) {
                String s = c.getString();
                if (s != null && !s.isBlank()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return be.getBlockState().getBlock().getDescriptionId();
    }

    private static String resolveOwnerName(Object owner) {
        try {
            Method getPartItem = owner.getClass().getMethod("getPartItem");
            Object partItem = getPartItem.invoke(owner);
            if (partItem instanceof Item item) {
                ItemStack stack = new ItemStack(item);
                if (!stack.isEmpty()) {
                    return stack.getHoverName().getString();
                }
            } else if (partItem != null) {
                Method asItem = partItem.getClass().getMethod("asItem");
                Object itemObj = asItem.invoke(partItem);
                if (itemObj instanceof Item item) {
                    return new ItemStack(item).getHoverName().getString();
                }
            }
        } catch (Throwable ignored) {
        }
        return owner.getClass().getSimpleName();
    }

    private static BlockEntity reflectPartHostBlockEntity(Object part) {
        try {
            Method getHost = part.getClass().getMethod("getHost");
            Object host = getHost.invoke(part);
            if (host == null) {
                return null;
            }
            Method getBe = host.getClass().getMethod("getBlockEntity");
            Object be = getBe.invoke(host);
            return be instanceof BlockEntity blockEntity ? blockEntity : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Direction reflectPartSide(Object part) {
        try {
            Method getSide = part.getClass().getMethod("getSide");
            Object side = getSide.invoke(part);
            return side instanceof Direction d ? d : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject toPatternDto(IPatternDetails pattern, JsonObject provider) {
        return toPatternDto(pattern, provider, -1);
    }

    private static JsonObject toPatternDto(IPatternDetails pattern, JsonObject provider, int slotIndex) {
        GenericStack primary = pattern.getPrimaryOutput();
        if (primary == null || primary.what() == null) {
            GenericStack[] outs = pattern.getOutputs();
            if (outs == null || outs.length == 0 || outs[0] == null) {
                return null;
            }
            primary = outs[0];
        }
        JsonObject primaryDto = Ae2QueryService.toStackDto(primary.what(), primary.amount(), true);
        if (primaryDto == null) {
            return null;
        }

        String fingerprint = patternId(pattern);
        String providerId = provider != null && provider.has("id") ? provider.get("id").getAsString() : "unknown";

        JsonObject dto = new JsonObject();
        dto.addProperty("id", fingerprint + "@" + providerId);
        dto.addProperty("mode", patternMode(pattern));
        dto.addProperty("name", resolvePatternName(pattern, primaryDto));
        if (slotIndex >= 0) {
            dto.addProperty("slotIndex", slotIndex);
        }
        dto.add("primaryOutput", primaryDto);
        if (provider != null) {
            dto.add("provider", provider);
        }

        JsonArray outputs = new JsonArray();
        for (GenericStack out : pattern.getOutputs()) {
            if (out == null || out.what() == null) {
                continue;
            }
            JsonObject o = Ae2QueryService.toStackDto(out.what(), out.amount(), true);
            if (o != null) {
                outputs.add(o);
            }
        }
        dto.add("outputs", outputs);

        JsonArray inputs = new JsonArray();
        for (IPatternDetails.IInput in : pattern.getInputs()) {
            if (in == null) {
                continue;
            }
            GenericStack[] possible = in.getPossibleInputs();
            if (possible == null || possible.length == 0 || possible[0] == null || possible[0].what() == null) {
                continue;
            }
            long amount = possible[0].amount() * Math.max(1L, in.getMultiplier());
            JsonObject input = new JsonObject();
            JsonObject item = Ae2QueryService.toStackDto(possible[0].what(), amount, false);
            if (item == null) {
                continue;
            }
            input.add("item", item);
            input.addProperty("multiplier", Long.toString(in.getMultiplier()));
            JsonArray alts = new JsonArray();
            for (int i = 1; i < possible.length; i++) {
                if (possible[i] == null || possible[i].what() == null) {
                    continue;
                }
                JsonObject alt = Ae2QueryService.toStackDto(
                        possible[i].what(),
                        possible[i].amount() * Math.max(1L, in.getMultiplier()),
                        false
                );
                if (alt != null) {
                    alts.add(alt);
                }
            }
            input.add("alternatives", alts);
            inputs.add(input);
        }
        dto.add("inputs", inputs);

        boolean substitute = false;
        boolean substituteFluids = false;
        if (pattern instanceof AECraftingPattern crafting) {
            substitute = crafting.canSubstitute();
            substituteFluids = crafting.canSubstituteFluids();
        }
        dto.addProperty("substitute", substitute);
        dto.addProperty("substituteFluids", substituteFluids);

        AEItemKey def = pattern.getDefinition();
        if (def != null) {
            JsonObject defDto = Ae2QueryService.toStackDto(def, 1, false);
            if (defDto != null) {
                dto.add("definition", defDto);
            }
            enrichPatternMeta(dto, pattern, def);
        }
        return dto;
    }

    /**
     * 附加可选元数据：编码人（NBT）、配方 ID、有序/无序。无数据则不写字段。
     */
    private static void enrichPatternMeta(JsonObject dto, IPatternDetails pattern, AEItemKey def) {
        ItemStack stack;
        try {
            stack = def.toStack();
        } catch (Throwable t) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getTag();
        String encoder = readEncoderName(tag);
        if (encoder != null) {
            dto.addProperty("encoder", encoder);
        }
        if (tag != null && tag.contains("recipe", Tag.TAG_STRING)) {
            String recipeId = tag.getString("recipe").trim();
            if (!recipeId.isEmpty()) {
                dto.addProperty("recipeId", recipeId);
            }
        }
        if (pattern instanceof AECraftingPattern crafting) {
            String shape = resolveCraftingShape(crafting);
            if (shape != null) {
                dto.addProperty("craftingShape", shape);
            }
        }
    }

    private static String readEncoderName(CompoundTag tag) {
        if (tag == null) {
            return null;
        }
        String[] keys = {
                "encoder", "Encoder", "author", "Author", "encodedBy", "EncodedBy",
                "createdBy", "CreatedBy", "playerName", "PlayerName"
        };
        for (String key : keys) {
            if (tag.contains(key, Tag.TAG_STRING)) {
                String v = tag.getString(key).trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        }
        return null;
    }

    private static String resolveCraftingShape(AECraftingPattern crafting) {
        try {
            Field f = AECraftingPattern.class.getDeclaredField("recipe");
            f.setAccessible(true);
            Object recipe = f.get(crafting);
            if (recipe instanceof ShapedRecipe) {
                return "shaped";
            }
            if (recipe instanceof CraftingRecipe) {
                return "shapeless";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 编码样板悬停名（砧重命名 / GT 彩色显示名），保留 § 颜色码。
     * 无自定义名时与物品栏一致，回退到产物名。
     */
    private static String resolvePatternName(IPatternDetails pattern, JsonObject primaryDto) {
        AEItemKey def = pattern.getDefinition();
        if (def != null) {
            try {
                ItemStack stack = def.toStack();
                if (stack != null && !stack.isEmpty()) {
                    String legacy = toLegacySection(stack.getHoverName());
                    if (legacy != null && !stripSectionCodes(legacy).isBlank()) {
                        return legacy;
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                String legacy = toLegacySection(def.getDisplayName());
                if (legacy != null && !stripSectionCodes(legacy).isBlank()) {
                    return legacy;
                }
            } catch (Throwable ignored) {
            }
        }
        if (primaryDto != null && primaryDto.has("displayName")) {
            return primaryDto.get("displayName").getAsString();
        }
        return "";
    }

    /** Component 样式 → 传统 § 码，供前端 McFormattedText 渲染。 */
    private static String toLegacySection(Component component) {
        if (component == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        component.visit((style, text) -> {
            if (text != null && !text.isEmpty()) {
                sb.append(legacyPrefix(style));
                sb.append(text);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }

    private static String legacyPrefix(Style style) {
        if (style == null || style.isEmpty()) {
            return "§r";
        }
        StringBuilder sb = new StringBuilder("§r");
        TextColor tc = style.getColor();
        if (tc != null) {
            ChatFormatting named = null;
            int rgb = tc.getValue();
            for (ChatFormatting f : ChatFormatting.values()) {
                if (f.isColor() && f.getColor() != null && f.getColor() == rgb) {
                    named = f;
                    break;
                }
            }
            if (named != null) {
                sb.append('§').append(named.getChar());
            } else {
                String hex = String.format("%06X", rgb & 0xFFFFFF);
                sb.append("§x");
                for (int i = 0; i < 6; i++) {
                    sb.append('§').append(hex.charAt(i));
                }
            }
        }
        if (style.isBold()) {
            sb.append("§l");
        }
        if (style.isItalic()) {
            sb.append("§o");
        }
        if (style.isUnderlined()) {
            sb.append("§n");
        }
        if (style.isStrikethrough()) {
            sb.append("§m");
        }
        if (style.isObfuscated()) {
            sb.append("§k");
        }
        return sb.toString();
    }

    private static String patternNameOf(JsonObject dto) {
        if (dto != null && dto.has("name") && dto.get("name").isJsonPrimitive()) {
            return dto.get("name").getAsString();
        }
        return "";
    }

    private static String stripSectionCodes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.replaceAll("[§\u00a7].", "");
    }

    private static String providerSortKey(JsonObject o) {
        if (o.has("provider") && o.getAsJsonObject("provider").has("id")) {
            return o.getAsJsonObject("provider").get("id").getAsString();
        }
        return "unknown";
    }

    private static boolean modeMatches(JsonObject dto, String modeFilter) {
        if (modeFilter == null || modeFilter.isBlank() || "all".equalsIgnoreCase(modeFilter.trim())) {
            return true;
        }
        return modeFilter.trim().equalsIgnoreCase(dto.get("mode").getAsString());
    }

    /**
     * qOutput / qInput：分别约束；两者都有时 AND；皆空则不过滤。
     */
    private static boolean queryMatches(JsonObject dto, String qOutput, String qInput) {
        boolean hasOut = qOutput != null && !qOutput.isBlank();
        boolean hasIn = qInput != null && !qInput.isBlank();
        if (!hasOut && !hasIn) {
            return true;
        }
        if (hasOut && !matchesOutput(dto, qOutput.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (hasIn && !matchesInput(dto, qInput.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return true;
    }

    private static boolean matchesOutput(JsonObject dto, String q) {
        if (matchesName(dto, q)) {
            return true;
        }
        if (matchesItem(dto.getAsJsonObject("primaryOutput"), q)) {
            return true;
        }
        if (dto.has("definition") && matchesItem(dto.getAsJsonObject("definition"), q)) {
            return true;
        }
        for (var el : dto.getAsJsonArray("outputs")) {
            if (matchesItem(el.getAsJsonObject(), q)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesInput(JsonObject dto, String q) {
        for (var el : dto.getAsJsonArray("inputs")) {
            JsonObject in = el.getAsJsonObject();
            if (matchesItem(in.getAsJsonObject("item"), q)) {
                return true;
            }
            if (in.has("alternatives")) {
                for (var alt : in.getAsJsonArray("alternatives")) {
                    if (matchesItem(alt.getAsJsonObject(), q)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean matchesName(JsonObject dto, String q) {
        String name = patternNameOf(dto);
        if (name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        String plain = stripSectionCodes(name).toLowerCase(Locale.ROOT);
        return lower.contains(q) || plain.contains(q);
    }

    private static boolean matchesItem(JsonObject item, String q) {
        if (item == null) {
            return false;
        }
        String name = item.has("displayName") ? item.get("displayName").getAsString() : "";
        String id = item.has("id") ? item.get("id").getAsString() : "";
        String key = item.has("key") ? item.get("key").getAsString() : "";
        return name.toLowerCase(Locale.ROOT).contains(q)
                || id.toLowerCase(Locale.ROOT).contains(q)
                || key.toLowerCase(Locale.ROOT).contains(q);
    }
}
