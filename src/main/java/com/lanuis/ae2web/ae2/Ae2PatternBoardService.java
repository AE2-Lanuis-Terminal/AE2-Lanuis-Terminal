package com.lanuis.ae2web.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.helpers.patternprovider.PatternProviderLogic;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lanuis.ae2web.Ae2LanuisMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 样板供应器槽位板：容量枚举与槽位移动/重排（经 {@link PatternProviderLogic#getPatternInv()}）。
 */
public final class Ae2PatternBoardService {
    private Ae2PatternBoardService() {
    }

    /**
     * 列出供应器及完整槽位；可选按 qOutput/qInput/mode 过滤样板（空槽在无筛选时保留）。
     */
    public static JsonObject listProviders(
            IGrid grid,
            String qOutput,
            String qInput,
            String modeFilter
    ) {
        boolean filtering = hasFilter(qOutput, qInput, modeFilter);
        List<JsonObject> boards = new ArrayList<>();
        for (ProviderHost host : collectHosts(grid)) {
            JsonObject board = buildBoard(host, filtering, qOutput, qInput, modeFilter);
            if (board != null) {
                boards.add(board);
            }
        }
        boards.sort(Comparator
                .comparing((JsonObject o) -> o.has("priority") ? o.get("priority").getAsInt() : Integer.MAX_VALUE)
                .thenComparing(o -> strip(o.has("name") ? o.get("name").getAsString() : ""), String.CASE_INSENSITIVE_ORDER));

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        JsonArray arr = new JsonArray();
        for (JsonObject b : boards) {
            arr.add(b);
        }
        root.add("providers", arr);
        return root;
    }

    /**
     * 批量移动/交换；先在内存快照上模拟，成功后再写回库存（事务性）。
     */
    public static JsonObject movePatterns(IGrid grid, JsonObject body) {
        if (body == null || !body.has("moves") || !body.get("moves").isJsonArray()) {
            return err("bad_request", "moves required");
        }
        JsonArray moves = body.getAsJsonArray("moves");
        if (moves.isEmpty()) {
            return err("bad_request", "moves must not be empty");
        }

        Map<String, ProviderHost> hosts = new LinkedHashMap<>();
        for (ProviderHost h : collectHosts(grid)) {
            if (h.movable && h.inv != null) {
                hosts.put(h.providerId, h);
            }
        }

        Map<String, ItemStack[]> working = new HashMap<>();
        Set<String> touched = new HashSet<>();

        try {
            for (JsonElement el : moves) {
                if (el == null || !el.isJsonObject()) {
                    return err("bad_request", "invalid move entry");
                }
                JsonObject op = el.getAsJsonObject();
                if (!op.has("from") || !op.has("to")) {
                    return err("bad_request", "from/to required");
                }
                JsonObject from = op.getAsJsonObject("from");
                JsonObject to = op.getAsJsonObject("to");
                String fromId = str(from, "providerId");
                String toId = str(to, "providerId");
                if (fromId.isEmpty() || toId.isEmpty() || !from.has("slotIndex")) {
                    return err("bad_request", "providerId/slotIndex required");
                }
                int fromSlot = from.get("slotIndex").getAsInt();
                Integer toSlotOpt = to.has("slotIndex") ? to.get("slotIndex").getAsInt() : null;

                ProviderHost fromHost = hosts.get(fromId);
                ProviderHost toHost = hosts.get(toId);
                if (fromHost == null) {
                    return err("provider_not_found", "Source provider not found or not movable: " + fromId);
                }
                if (toHost == null) {
                    return err("provider_not_found", "Target provider not found or not movable: " + toId);
                }

                ItemStack[] fromArr = working.computeIfAbsent(fromId, id -> snapshot(fromHost.inv));
                ItemStack[] toArr = fromId.equals(toId)
                        ? fromArr
                        : working.computeIfAbsent(toId, id -> snapshot(toHost.inv));

                if (fromSlot < 0 || fromSlot >= fromArr.length) {
                    return err("bad_slot", "Invalid source slotIndex");
                }
                ItemStack src = fromArr[fromSlot];
                if (src == null || src.isEmpty()) {
                    return err("slot_empty", "Source slot is empty");
                }
                if (!isEncodedPattern(src, fromHost.level)) {
                    return err("not_pattern_item", "Source slot is not an encoded pattern");
                }

                int toSlot;
                if (toSlotOpt != null) {
                    toSlot = toSlotOpt;
                    if (toSlot < 0 || toSlot >= toArr.length) {
                        return err("bad_slot", "Invalid target slotIndex");
                    }
                } else {
                    toSlot = firstEmpty(toArr, fromId.equals(toId) ? fromSlot : -1);
                    if (toSlot < 0) {
                        return err("target_full", "Target provider has no empty slot");
                    }
                }

                if (fromId.equals(toId) && fromSlot == toSlot) {
                    continue;
                }

                ItemStack dest = toArr[toSlot] == null ? ItemStack.EMPTY : toArr[toSlot];
                // 交换或移入空槽
                fromArr[fromSlot] = dest.isEmpty() ? ItemStack.EMPTY : dest.copy();
                toArr[toSlot] = src.copy();
                touched.add(fromId);
                touched.add(toId);
            }
        } catch (Exception e) {
            Ae2LanuisMod.LOGGER.warn("pattern move simulate failed: {}", e.toString());
            return err("internal", e.getMessage() == null ? "move failed" : e.getMessage());
        }

        try {
            for (String id : touched) {
                ProviderHost host = hosts.get(id);
                ItemStack[] arr = working.get(id);
                if (host == null || arr == null || host.inv == null) {
                    continue;
                }
                writeSnapshot(host.inv, arr);
            }
        } catch (Exception e) {
            Ae2LanuisMod.LOGGER.warn("pattern move commit failed: {}", e.toString());
            return err("internal", e.getMessage() == null ? "commit failed" : e.getMessage());
        }

        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        return ok;
    }

    /** 供 list() 附带 slotIndex / 容量：扫描可移动供应器槽位。 */
    public static List<SlottedPattern> collectSlottedPatterns(IGrid grid) {
        List<SlottedPattern> out = new ArrayList<>();
        IdentityHashMap<IPatternDetails, Boolean> covered = new IdentityHashMap<>();
        for (ProviderHost host : collectHosts(grid)) {
            if (host.movable && host.inv != null) {
                int used = countUsed(host.inv);
                JsonObject providerBase = host.providerDto.deepCopy();
                enrichCapacity(providerBase, host.inv.size(), used, true);
                for (int i = 0; i < host.inv.size(); i++) {
                    ItemStack stack = host.inv.getStackInSlot(i);
                    if (stack == null || stack.isEmpty()) {
                        continue;
                    }
                    IPatternDetails pattern = decode(stack, host.level);
                    if (pattern == null) {
                        continue;
                    }
                    covered.put(pattern, Boolean.TRUE);
                    out.add(new SlottedPattern(pattern, providerBase.deepCopy(), i));
                }
            } else if (host.craftingProvider != null) {
                List<IPatternDetails> patterns = host.craftingProvider.getAvailablePatterns();
                if (patterns == null) {
                    continue;
                }
                JsonObject providerBase = host.providerDto.deepCopy();
                enrichCapacity(providerBase, patterns.size(), patterns.size(), false);
                for (IPatternDetails pattern : patterns) {
                    if (pattern == null || covered.containsKey(pattern)) {
                        continue;
                    }
                    covered.put(pattern, Boolean.TRUE);
                    out.add(new SlottedPattern(pattern, providerBase.deepCopy(), -1));
                }
            }
        }
        return out;
    }

    public record SlottedPattern(IPatternDetails pattern, JsonObject provider, int slotIndex) {
    }

    private record ProviderHost(
            String providerId,
            JsonObject providerDto,
            ICraftingProvider craftingProvider,
            InternalInventory inv,
            Level level,
            boolean movable
    ) {
    }

    private static List<ProviderHost> collectHosts(IGrid grid) {
        List<ProviderHost> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> machineClass : grid.getMachineClasses()) {
            for (IGridNode node : grid.getMachineNodes(machineClass)) {
                if (node == null) {
                    continue;
                }
                ICraftingProvider craftingProvider = node.getService(ICraftingProvider.class);
                if (craftingProvider == null) {
                    continue;
                }
                JsonObject providerDto = Ae2PatternService.providerDtoForNode(node, craftingProvider);
                if (providerDto == null || !providerDto.has("id")) {
                    continue;
                }
                String id = providerDto.get("id").getAsString();
                if (!seen.add(id)) {
                    continue;
                }
                ServerLevel level = null;
                try {
                    level = node.getLevel();
                } catch (Throwable ignored) {
                }
                InternalInventory inv = resolvePatternInv(node, craftingProvider);
                boolean movable = inv != null;
                if (movable) {
                    enrichCapacity(providerDto, inv.size(), countUsed(inv), true);
                } else {
                    List<IPatternDetails> pats = craftingProvider.getAvailablePatterns();
                    int n = pats == null ? 0 : pats.size();
                    enrichCapacity(providerDto, n, n, false);
                }
                out.add(new ProviderHost(id, providerDto, craftingProvider, inv, level, movable));
            }
        }
        return out;
    }

    private static JsonObject buildBoard(
            ProviderHost host,
            boolean filtering,
            String qOutput,
            String qInput,
            String modeFilter
    ) {
        JsonObject board = host.providerDto.deepCopy();
        JsonArray slots = new JsonArray();
        int used = 0;
        if (host.movable && host.inv != null) {
            for (int i = 0; i < host.inv.size(); i++) {
                ItemStack stack = host.inv.getStackInSlot(i);
                JsonObject slot = new JsonObject();
                slot.addProperty("index", i);
                if (stack == null || stack.isEmpty()) {
                    if (!filtering) {
                        slots.add(slot);
                    }
                    continue;
                }
                used++;
                IPatternDetails pattern = decode(stack, host.level);
                JsonObject dto = pattern == null
                        ? null
                        : Ae2PatternService.toPatternDtoPublic(pattern, host.providerDto.deepCopy(), i);
                if (dto == null) {
                    if (!filtering) {
                        slots.add(slot);
                    }
                    continue;
                }
                boolean match = Ae2PatternService.modeMatchesPublic(dto, modeFilter)
                        && Ae2PatternService.queryMatchesPublic(dto, qOutput, qInput);
                if (filtering && !match) {
                    continue;
                }
                slot.add("pattern", dto);
                slots.add(slot);
            }
            enrichCapacity(board, host.inv.size(), used, true);
        } else {
            List<IPatternDetails> patterns = host.craftingProvider == null
                    ? List.of()
                    : host.craftingProvider.getAvailablePatterns();
            if (patterns == null) {
                patterns = List.of();
            }
            int idx = 0;
            for (IPatternDetails pattern : patterns) {
                if (pattern == null) {
                    continue;
                }
                JsonObject dto = Ae2PatternService.toPatternDtoPublic(pattern, host.providerDto.deepCopy(), -1);
                if (dto == null) {
                    continue;
                }
                if (!Ae2PatternService.modeMatchesPublic(dto, modeFilter)
                        || !Ae2PatternService.queryMatchesPublic(dto, qOutput, qInput)) {
                    continue;
                }
                JsonObject slot = new JsonObject();
                slot.addProperty("index", idx++);
                slot.add("pattern", dto);
                slots.add(slot);
                used++;
            }
            enrichCapacity(board, used, used, false);
        }
        if (filtering && slots.isEmpty()) {
            return null;
        }
        board.add("slots", slots);
        return board;
    }

    private static InternalInventory resolvePatternInv(IGridNode node, ICraftingProvider craftingProvider) {
        if (craftingProvider instanceof PatternProviderLogic logic) {
            return logic.getPatternInv();
        }
        Object owner = node.getOwner();
        Object logic = reflectGetLogic(owner);
        if (logic == null) {
            return null;
        }
        if (logic instanceof PatternProviderLogic ppl) {
            return ppl.getPatternInv();
        }
        try {
            Method m = logic.getClass().getMethod("getPatternInv");
            Object inv = m.invoke(logic);
            return inv instanceof InternalInventory ii ? ii : null;
        } catch (Throwable ignored) {
            return null;
        }
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

    private static IPatternDetails decode(ItemStack stack, Level level) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            return PatternDetailsHelper.decodePattern(stack, level);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isEncodedPattern(ItemStack stack, Level level) {
        return decode(stack, level) != null;
    }

    private static int countUsed(InternalInventory inv) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            if (s != null && !s.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static void enrichCapacity(JsonObject dto, int slotCount, int usedSlots, boolean movable) {
        dto.addProperty("slotCount", Math.max(0, slotCount));
        if (usedSlots >= 0) {
            dto.addProperty("usedSlots", usedSlots);
        }
        dto.addProperty("movable", movable);
    }

    private static ItemStack[] snapshot(InternalInventory inv) {
        ItemStack[] arr = new ItemStack[inv.size()];
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStackInSlot(i);
            arr[i] = (s == null || s.isEmpty()) ? ItemStack.EMPTY : s.copy();
        }
        return arr;
    }

    private static void writeSnapshot(InternalInventory inv, ItemStack[] arr) {
        for (int i = 0; i < arr.length && i < inv.size(); i++) {
            ItemStack next = arr[i] == null || arr[i].isEmpty() ? ItemStack.EMPTY : arr[i].copy();
            ItemStack cur = inv.getStackInSlot(i);
            if (ItemStack.matches(cur == null ? ItemStack.EMPTY : cur, next)) {
                continue;
            }
            inv.setItemDirect(i, next);
        }
    }

    private static int firstEmpty(ItemStack[] arr, int skip) {
        for (int i = 0; i < arr.length; i++) {
            if (i == skip) {
                continue;
            }
            if (arr[i] == null || arr[i].isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 将已编码样板写入可移动供应器空槽。
     * @return 实际写入的 slotIndex；失败抛出带 code 前缀的 IllegalStateException
     */
    static int insertEncodedPattern(IGrid grid, String providerId, ItemStack encoded, Integer slotIndexOpt) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalStateException("invalid_recipe:Encoded stack empty");
        }
        ProviderHost host = null;
        for (ProviderHost h : collectHosts(grid)) {
            if (providerId.equals(h.providerId)) {
                host = h;
                break;
            }
        }
        if (host == null) {
            throw new IllegalStateException("provider_not_found:Provider not found: " + providerId);
        }
        if (!host.movable || host.inv == null) {
            throw new IllegalStateException("not_movable:Provider is read-only");
        }
        int slot;
        if (slotIndexOpt != null) {
            slot = slotIndexOpt;
            if (slot < 0 || slot >= host.inv.size()) {
                throw new IllegalStateException("bad_slot:Invalid slotIndex");
            }
            ItemStack cur = host.inv.getStackInSlot(slot);
            if (cur != null && !cur.isEmpty()) {
                throw new IllegalStateException("slot_occupied:Slot occupied");
            }
        } else {
            ItemStack[] snap = snapshot(host.inv);
            slot = firstEmpty(snap, -1);
            if (slot < 0) {
                throw new IllegalStateException("no_empty_slot:No empty slot");
            }
        }
        host.inv.setItemDirect(slot, encoded.copy());
        return slot;
    }

    static JsonObject providerDtoById(IGrid grid, String providerId) {
        for (ProviderHost h : collectHosts(grid)) {
            if (providerId.equals(h.providerId)) {
                return h.providerDto.deepCopy();
            }
        }
        return null;
    }

    static Level levelForProvider(IGrid grid, String providerId) {
        for (ProviderHost h : collectHosts(grid)) {
            if (providerId.equals(h.providerId)) {
                return h.level;
            }
        }
        return null;
    }

    private static boolean hasFilter(String qOutput, String qInput, String modeFilter) {
        return (qOutput != null && !qOutput.isBlank())
                || (qInput != null && !qInput.isBlank())
                || (modeFilter != null && !modeFilter.isBlank() && !"all".equalsIgnoreCase(modeFilter.trim()));
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString().trim() : "";
    }

    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("§.", "");
    }

    private static JsonObject err(String code, String message) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", false);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        root.add("error", error);
        return root;
    }
}
