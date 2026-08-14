package com.lanuis.ae2web.ae2;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lanuis.ae2web.auth.BindingStore;
import com.lanuis.ae2web.config.ModConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import appeng.api.crafting.IPatternDetails;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * AE2 网络查询与合成操作的服务端门面，供 HTTP API 在主线程调用。
 * <p>
 * 职责：库存分页、可合成目录、网络摘要、合成规划/提交/任务列表/取消。
 * 规划结果缓存在进程内 PLAN_CACHE（按 planId），60 秒过期且绑定提交玩家 UUID，
 * 防止跨会话盗用。库存同时列出 {@link AEItemKey} 与 {@link AEFluidKey}；其它 AEKey 仍忽略。
 * </p>
 * <p>
 * 库存热路径：按网格内容 revision 缓存已过滤排序的视图，分页只做 slice；
 * 内容未变时跳过 DTO 构建与排序（仍会采样 KeyCounter 以计算 revision）。
 * 正在合成的最终产物置顶（对齐 AE2 客户端 PinnedKeys 行为；服务端用 CPU JobStatus）。
 * </p>
 * <p>
 * 线程：全部方法假定已在 Minecraft 主线程；HTTP 层须经 {@code MainThreadExecutor}。
 * </p>
 */
public final class Ae2QueryService {
    /**
     * planId → 缓存规划；提交时 remove，过期或错主均拒绝。
     * ConcurrentHashMap：规划计算可能跨 tick，提交来自另一 HTTP 请求。
     */
    private static final ConcurrentHashMap<String, CachedPlan> PLAN_CACHE = new ConcurrentHashMap<>();

    /** IGrid identityHashCode → 最近一次库存采样（revision 命中时复用）。 */
    private static final ConcurrentHashMap<Integer, GridSnap> GRID_SNAPS = new ConcurrentHashMap<>();

    /** viewKey → 已过滤排序列表；与 GridSnap.contentRevision 对齐。 */
    private static final ConcurrentHashMap<String, ViewSnap> VIEW_CACHE = new ConcurrentHashMap<>();

    private static final int MAX_VIEW_CACHE = 64;

    /**
     * 工具类禁止实例化。
     */
    private Ae2QueryService() {
    }

    /** 服务停止或世界卸载时清空库存缓存，避免持有过期 KeyCounter。 */
    public static void clearInventoryCaches() {
        GRID_SNAPS.clear();
        VIEW_CACHE.clear();
    }

    /**
     * 根据绑定记录中的 networkLink 解析 IGrid。
     * 解析失败返回 empty，由 HTTP 映射 network_unavailable。
     */
    public static Optional<IGrid> gridForBinding(MinecraftServer server, BindingStore.BindingRecord binding) {
        return WirelessTerminalBinder.resolvePersisted(binding.networkLink, server);
    }

    /**
     * 查询 ME 库存并分页，附带网络摘要。
     * <p>
     * kind：all / item / fluid / other；filter：all / stocked / craftable；
     * sort：name / amount / mod；order：asc / desc。
     * other=非 item/fluid（含未来未单独注册的类型）。
     * filter 为 all/craftable 时并入零库存可合成项。
     * 忙碌 CPU 的最终产物不受搜索/筛选影响，固定排在列表最前（同物品去重）。
     * 响应含 {@code contentRevision}，供 WS 在内容未变时跳过推送。
     * </p>
     */
    public static JsonObject inventory(
            IGrid grid,
            String query,
            String kind,
            String filter,
            String sort,
            String order,
            int page,
            int pageSize
    ) {
        return inventory(grid, query, kind, filter, sort, order, page, pageSize, true, null);
    }

    /**
     * 同 {@link #inventory}；当 {@code force} 为 false 且所有 {@code knownRevisions} 均等于
     * 当前 contentRevision 时返回 null（WS 热路径跳过组装分页）。
     */
    public static JsonObject inventory(
            IGrid grid,
            String query,
            String kind,
            String filter,
            String sort,
            String order,
            int page,
            int pageSize,
            boolean force,
            long[] knownRevisions
    ) {
        GridSnap snap = ensureGridSnap(grid);
        if (!force && knownRevisions != null) {
            boolean allMatch = true;
            for (long known : knownRevisions) {
                if (known != snap.contentRevision) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return null;
            }
        }

        String q = query == null ? "" : query;
        String kindKey = kind == null || kind.isBlank() ? "all" : kind;
        String filterKey = filter == null || filter.isBlank() ? "all" : filter;
        String sortKey = sort == null || sort.isBlank() ? "name" : sort;
        String orderKey = order == null || order.isBlank() ? "asc" : order;

        String viewKey = System.identityHashCode(grid) + "|" + q + "|" + kindKey + "|" + filterKey + "|" + sortKey + "|" + orderKey;
        ViewSnap view = VIEW_CACHE.get(viewKey);
        if (view == null || view.contentRevision != snap.contentRevision) {
            List<JsonObject> items = buildFilteredSorted(snap, q, kindKey, filterKey, sortKey, orderKey);
            view = new ViewSnap(snap.contentRevision, items);
            putView(viewKey, view);
        }

        int safePageSize = Math.min(Math.max(pageSize, 1), ModConfig.MAX_INVENTORY_PAGE_SIZE.get());
        int safePage = Math.max(page, 1);
        int from = (safePage - 1) * safePageSize;
        int to = Math.min(from + safePageSize, view.items.size());
        JsonArray pageItems = new JsonArray();
        if (from < view.items.size()) {
            for (JsonObject o : view.items.subList(from, to)) {
                pageItems.add(o);
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("page", safePage);
        root.addProperty("pageSize", safePageSize);
        root.addProperty("total", view.items.size());
        root.addProperty("contentRevision", snap.contentRevision);
        root.add("items", pageItems);
        root.add("network", snap.network.deepCopy());
        return root;
    }

    /**
     * 可合成目录：filter=craftable。
     */
    public static JsonObject catalog(IGrid grid, String query, String sort, String order, int page, int pageSize) {
        return inventory(grid, query, "all", "craftable", sort, order, page, pageSize);
    }

    /**
     * 仅计算内容 revision（会采样库存）；供 WS 在未变时跳过完整分页组装。
     */
    public static long contentRevision(IGrid grid) {
        return ensureGridSnap(grid).contentRevision;
    }

    /**
     * 网络摘要：是否通电、库存种类数（物品+流体）、CPU 总数与忙碌数。
     * online = {@link appeng.api.networking.energy.IEnergyService#isNetworkPowered()}，断电为 false。
     * 字段名 itemTypes 保持 API 兼容。
     */
    public static JsonObject networkSummary(IGrid grid) {
        return ensureGridSnap(grid).network.deepCopy();
    }

    /**
     * 轻量网络状态：不通扫全库存；管理员列表用。itemTypes 为 -1 表示未统计。
     */
    public static JsonObject networkStatusLite(IGrid grid) {
        CpuScan cpus = scanCpus(grid);
        boolean online = grid.getEnergyService().isNetworkPowered();
        JsonObject net = new JsonObject();
        net.addProperty("online", online);
        net.addProperty("itemTypes", -1);
        net.addProperty("cpuCount", cpus.cpuCount());
        net.addProperty("busyCpuCount", cpus.busyCpuCount());
        return net;
    }

    private static GridSnap ensureGridSnap(IGrid grid) {
        int gridId = System.identityHashCode(grid);
        MEStorage storage = grid.getStorageService().getInventory();
        KeyCounter available = new KeyCounter();
        storage.getAvailableStacks(available);

        Set<AEKey> craftables = new HashSet<>();
        craftables.addAll(grid.getCraftingService().getCraftables(AEItemKey.filter()));
        craftables.addAll(grid.getCraftingService().getCraftables(AEFluidKey.filter()));

        CpuScan cpus = scanCpus(grid);
        boolean online = grid.getEnergyService().isNetworkPowered();
        long rev = hashContent(available, craftables, cpus.cpuCount(), cpus.busyCpuCount(), cpus.craftingOutputs(), online);

        GridSnap existing = GRID_SNAPS.get(gridId);
        if (existing != null && existing.contentRevision == rev) {
            return existing;
        }

        JsonObject network = buildNetworkSummary(available, cpus.cpuCount(), cpus.busyCpuCount(), online);
        GridSnap snap = new GridSnap(rev, available, craftables, network, cpus.craftingOutputs());
        GRID_SNAPS.put(gridId, snap);
        return snap;
    }

    private static void putView(String viewKey, ViewSnap view) {
        VIEW_CACHE.put(viewKey, view);
        if (VIEW_CACHE.size() <= MAX_VIEW_CACHE) {
            return;
        }
        // 优先丢掉其它 revision 的陈旧视图；仍超限则整表清空
        VIEW_CACHE.entrySet().removeIf(e -> e.getValue().contentRevision != view.contentRevision);
        if (VIEW_CACHE.size() > MAX_VIEW_CACHE) {
            VIEW_CACHE.clear();
            VIEW_CACHE.put(viewKey, view);
        }
    }

    private static List<JsonObject> buildFilteredSorted(
            GridSnap snap,
            String query,
            String kind,
            String filter,
            String sort,
            String order
    ) {
        List<JsonObject> items = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (AEKey key : snap.available.keySet()) {
            long amount = snap.available.get(key);
            JsonObject dto = toStackDto(key, amount, snap.craftables.contains(key));
            if (dto == null || !matches(dto, query, kind, filter)) {
                continue;
            }
            items.add(dto);
            seenKeys.add(dto.get("key").getAsString());
        }

        if (includesCraftableZeros(filter)) {
            for (AEKey key : snap.craftables) {
                JsonObject dto = toStackDto(key, snap.available.get(key), true);
                if (dto == null || seenKeys.contains(dto.get("key").getAsString())) {
                    continue;
                }
                if (!matches(dto, query, kind, filter)) {
                    continue;
                }
                items.add(dto);
                seenKeys.add(dto.get("key").getAsString());
            }
        }

        boolean desc = "desc".equalsIgnoreCase(order);
        Comparator<JsonObject> cmp;
        if ("amount".equalsIgnoreCase(sort)) {
            cmp = Comparator.comparing((JsonObject o) -> Long.parseLong(o.get("amount").getAsString()));
        } else if ("mod".equalsIgnoreCase(sort)) {
            // 先按命名空间（模组 id），同模组再按显示名
            cmp = Comparator
                    .comparing((JsonObject o) -> modIdOf(o.get("id").getAsString()), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(o -> o.get("displayName").getAsString().toLowerCase(Locale.ROOT));
        } else {
            cmp = Comparator.comparing(o -> o.get("displayName").getAsString().toLowerCase(Locale.ROOT));
        }
        if (desc) {
            cmp = cmp.reversed();
        }
        items.sort(cmp);
        return pinCraftingFirst(items, snap);
    }

    /**
     * 忙碌 CPU 最终产物置顶：用库存真实数量，不受当前搜索/筛选影响。
     * 对齐 AE2 终端 pin 行；无客户端 PinnedKeys 可读，只能用 JobStatus。
     */
    private static List<JsonObject> pinCraftingFirst(List<JsonObject> items, GridSnap snap) {
        List<AEKey> crafting = snap.craftingOutputs();
        if (crafting.isEmpty()) {
            return items;
        }
        List<JsonObject> pinned = new ArrayList<>();
        Set<String> pinnedKeys = new HashSet<>();
        for (AEKey key : crafting) {
            JsonObject dto = toStackDto(key, snap.available.get(key), true);
            if (dto == null) {
                continue;
            }
            String itemKey = dto.get("key").getAsString();
            if (!pinnedKeys.add(itemKey)) {
                continue;
            }
            pinnedKeys.add(dto.get("id").getAsString());
            pinned.add(dto);
        }
        if (pinned.isEmpty()) {
            return items;
        }
        List<JsonObject> rest = new ArrayList<>(items.size());
        for (JsonObject o : items) {
            if (pinnedKeys.contains(o.get("key").getAsString()) || pinnedKeys.contains(o.get("id").getAsString())) {
                continue;
            }
            rest.add(o);
        }
        pinned.addAll(rest);
        return pinned;
    }

    /** id 的 namespace；无冒号时整段当作模组键 */
    private static String modIdOf(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        int colon = id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : id;
    }

    /** 扫描 CPU：忙闲计数 + 去重后的合成最终产物（置顶用） */
    private static CpuScan scanCpus(IGrid grid) {
        var cpus = grid.getCraftingService().getCpus();
        int busy = 0;
        List<AEKey> outputs = new ArrayList<>();
        Set<AEKey> seen = new HashSet<>();
        for (var cpu : cpus) {
            if (!cpu.isBusy()) {
                continue;
            }
            busy++;
            var status = cpu.getJobStatus();
            if (status == null) {
                continue;
            }
            var stack = status.crafting();
            if (stack == null || stack.what() == null || !seen.add(stack.what())) {
                continue;
            }
            outputs.add(stack.what());
        }
        return new CpuScan(cpus.size(), busy, List.copyOf(outputs));
    }

    private static JsonObject buildNetworkSummary(KeyCounter available, int cpuCount, int busyCpuCount, boolean online) {
        JsonObject net = new JsonObject();
        net.addProperty("online", online);
        long itemTypes = 0;
        for (AEKey ignored : available.keySet()) {
            itemTypes++;
        }
        net.addProperty("itemTypes", itemTypes);
        net.addProperty("cpuCount", cpuCount);
        net.addProperty("busyCpuCount", busyCpuCount);
        return net;
    }

    /**
     * 内容指纹：数量/种类/可合成/CPU/合成产物/通电状态任一变化即变。
     * 通电变化也要推 WS，否则顶栏会卡在「网络在线」。
     */
    private static long hashContent(
            KeyCounter available,
            Set<AEKey> craftables,
            int cpuCount,
            int busyCpuCount,
            List<AEKey> craftingOutputs,
            boolean online
    ) {
        long h = 0xcbf29ce484222325L;
        for (AEKey key : available.keySet()) {
            h ^= key.hashCode() * 0x9E3779B97F4A7C15L;
            h = Long.rotateLeft(h, 13);
            h ^= available.get(key);
            h = Long.rotateLeft(h, 7);
        }
        h ^= (long) available.size() * 0x100000001b3L;
        for (AEKey key : craftables) {
            h ^= key.hashCode() * 0x9E3779B97F4A7C15L;
            h = Long.rotateLeft(h, 11);
        }
        h ^= craftables.size();
        h ^= (long) cpuCount << 32 | (busyCpuCount & 0xffffffffL);
        for (AEKey key : craftingOutputs) {
            h ^= key.hashCode() * 0x9E3779B97F4A7C15L;
            h = Long.rotateLeft(h, 17);
        }
        h ^= craftingOutputs.size();
        h ^= online ? 0x9E3779B97F4A7C15L : 0xC2B2AE3D27D4EB4FL;
        return h;
    }

    /**
     * 启动 AE2 合成规划计算，缓存计划并返回缺失原料等信息。
     * <p>
     * 使用 {@link CalculationStrategy#REPORT_MISSING_ITEMS}，便于前端展示缺料。
     * future.get 超时来自配置 CRAFT_PLAN_TIMEOUT_MS。
     * </p>
     *
     * @param server     用于 overworld 与 ActionSource
     * @param grid       目标网络
     * @param playerUuid 规划归属玩家（提交时校验）
     * @param key        物品键字符串
     * @param amountStr  数量（必须 &gt; 0）
     */
    public static JsonObject plan(MinecraftServer server, IGrid grid, UUID playerUuid, String key, String amountStr) throws Exception {
        AEItemKey itemKey = parseItemKey(key);
        long amount = Long.parseLong(amountStr);
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }

        IActionSource source = actionSource(server, playerUuid);
        ICraftingService crafting = grid.getCraftingService();
        // beginCraftingCalculation 可能异步；必须在主线程发起
        Future<ICraftingPlan> future = crafting.beginCraftingCalculation(
                server.overworld(),
                () -> source,
                itemKey,
                amount,
                CalculationStrategy.REPORT_MISSING_ITEMS
        );
        ICraftingPlan plan = future.get(ModConfig.CRAFT_PLAN_TIMEOUT_MS.get(), TimeUnit.MILLISECONDS);

        String planId = UUID.randomUUID().toString();
        // 60s 窗口：足够前端确认，又限制陈旧计划占用 CPU
        PLAN_CACHE.put(planId, new CachedPlan(plan, playerUuid, System.currentTimeMillis() + 60_000));

        JsonObject root = new JsonObject();
        root.addProperty("planId", planId);
        root.addProperty("ok", true);
        root.add("output", toItemDto(itemKey, amount, true));
        long bytesRequired = plan.bytes();
        root.addProperty("bytes", Long.toString(bytesRequired));

        CpuCapacity capacity = scanIdleCpuCapacity(grid);
        root.addProperty("bytesAvailable", Long.toString(capacity.bytesAvailable()));
        root.addProperty("coProcessors", capacity.coProcessors());
        root.addProperty("cpuCount", capacity.cpuCount());
        root.addProperty("idleCpuCount", capacity.idleCpuCount());
        root.addProperty("multiplePaths", plan.multiplePaths());
        root.add("cpus", listSelectableCpus(grid, bytesRequired));

        JsonArray missing = new JsonArray();
        var missingItems = plan.missingItems();
        for (AEKey k : missingItems.keySet()) {
            JsonObject dto = toStackDto(k, missingItems.get(k), false);
            if (dto != null) {
                missing.add(dto);
            }
        }
        root.add("missing", missing);

        JsonArray used = new JsonArray();
        var usedItems = plan.usedItems();
        for (AEKey k : usedItems.keySet()) {
            JsonObject dto = toStackDto(k, usedItems.get(k), false);
            if (dto != null) {
                used.add(dto);
            }
        }
        root.add("usedItems", used);

        JsonObject tree = buildRecipeTree(plan, missingItems, 0);
        if (tree != null) {
            root.add("tree", tree);
        }

        boolean bytesOk = capacity.bytesAvailable() >= bytesRequired;
        boolean canSubmit = missing.size() == 0 && bytesOk && capacity.idleCpuCount() > 0;
        root.addProperty("canSubmit", canSubmit);
        String warning = "";
        if (missing.size() > 0) {
            warning = "Missing ingredients";
        } else if (capacity.idleCpuCount() <= 0) {
            warning = "No idle crafting CPU";
        } else if (!bytesOk) {
            warning = "Insufficient crafting CPU storage";
        }
        root.addProperty("warning", warning);
        return root;
    }

    private record CpuCapacity(long bytesAvailable, int coProcessors, int cpuCount, int idleCpuCount) {
    }

    /** 空闲 CPU：可用存储取最大（单任务占一台），协处理器求和。 */
    private static CpuCapacity scanIdleCpuCapacity(IGrid grid) {
        long maxBytes = 0;
        int coProcessors = 0;
        int cpuCount = 0;
        int idle = 0;
        for (ICraftingCPU cpu : grid.getCraftingService().getCpus()) {
            cpuCount++;
            if (cpu.isBusy()) {
                continue;
            }
            idle++;
            maxBytes = Math.max(maxBytes, cpu.getAvailableStorage());
            coProcessors += Math.max(0, cpu.getCoProcessors());
        }
        return new CpuCapacity(maxBytes, coProcessors, cpuCount, idle);
    }

    /** 可选 CPU 列表（含忙碌项；suitable=空闲且存储够）。 */
    private static JsonArray listSelectableCpus(IGrid grid, long bytesRequired) {
        List<ICraftingCPU> cpus = new ArrayList<>(grid.getCraftingService().getCpus());
        List<String> labels = uniqueCpuLabels(cpus);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < cpus.size(); i++) {
            ICraftingCPU cpu = cpus.get(i);
            long storage = cpu.getAvailableStorage();
            boolean busy = cpu.isBusy();
            boolean suitable = !busy && storage >= bytesRequired;
            JsonObject row = new JsonObject();
            row.addProperty("cpuName", labels.get(i));
            row.addProperty("busy", busy);
            row.addProperty("bytesAvailable", Long.toString(storage));
            row.addProperty("coProcessors", Math.max(0, cpu.getCoProcessors()));
            row.addProperty("suitable", suitable);
            arr.add(row);
        }
        return arr;
    }

    /**
     * 以 finalOutput 为根，用 patternTimes 递归展开配方树（深度上限防环）。
     */
    private static JsonObject buildRecipeTree(
            ICraftingPlan plan,
            KeyCounter missingItems,
            int depth
    ) {
        GenericStack finalOut = plan.finalOutput();
        if (finalOut == null || finalOut.what() == null) {
            return null;
        }
        return buildPatternNode(finalOut.what(), finalOut.amount(), plan.patternTimes(), missingItems, depth, new IdentityHashMap<>());
    }

    private static JsonObject buildPatternNode(
            AEKey want,
            long amount,
            Map<IPatternDetails, Long> patternTimes,
            KeyCounter missingItems,
            int depth,
            IdentityHashMap<IPatternDetails, Boolean> stack
    ) {
        JsonObject node = new JsonObject();
        JsonObject outDto = toStackDto(want, amount, true);
        if (outDto != null) {
            node.add("output", outDto);
        }
        node.addProperty("times", Long.toString(Math.max(1, amount)));
        boolean isMissing = missingItems.get(want) > 0;
        node.addProperty("missing", isMissing);

        if (depth >= 12) {
            node.addProperty("mode", "leaf");
            node.add("inputs", new JsonArray());
            return node;
        }

        IPatternDetails best = null;
        long bestTimes = 0;
        for (var entry : patternTimes.entrySet()) {
            IPatternDetails pattern = entry.getKey();
            if (pattern == null || stack.containsKey(pattern)) {
                continue;
            }
            if (!patternProduces(pattern, want)) {
                continue;
            }
            long times = entry.getValue() == null ? 0L : entry.getValue();
            if (times > bestTimes || best == null) {
                best = pattern;
                bestTimes = times;
            }
        }

        if (best == null) {
            node.addProperty("mode", "leaf");
            node.add("inputs", new JsonArray());
            return node;
        }

        node.addProperty("mode", Ae2PatternService.patternMode(best));
        node.addProperty("patternId", Ae2PatternService.patternId(best));
        node.addProperty("times", Long.toString(Math.max(1, bestTimes)));

        stack.put(best, Boolean.TRUE);
        JsonArray inputs = new JsonArray();
        for (IPatternDetails.IInput in : best.getInputs()) {
            if (in == null) {
                continue;
            }
            GenericStack[] possible = in.getPossibleInputs();
            if (possible == null || possible.length == 0 || possible[0] == null || possible[0].what() == null) {
                continue;
            }
            AEKey inKey = possible[0].what();
            long inAmount = possible[0].amount() * Math.max(1L, in.getMultiplier()) * Math.max(1L, bestTimes);
            JsonObject edge = new JsonObject();
            JsonObject itemDto = toStackDto(inKey, inAmount, false);
            if (itemDto != null) {
                edge.add("item", itemDto);
            }
            edge.addProperty("missing", missingItems.get(inKey) > 0);
            // 若该输入仍由其它样板产出，挂 child
            if (hasProducer(patternTimes, inKey, stack)) {
                JsonObject child = buildPatternNode(inKey, inAmount, patternTimes, missingItems, depth + 1, stack);
                if (child != null) {
                    edge.add("child", child);
                }
            }
            inputs.add(edge);
        }
        stack.remove(best);
        node.add("inputs", inputs);
        return node;
    }

    private static boolean patternProduces(IPatternDetails pattern, AEKey want) {
        for (GenericStack out : pattern.getOutputs()) {
            if (out != null && out.what() != null && out.what().equals(want)) {
                return true;
            }
        }
        GenericStack primary = pattern.getPrimaryOutput();
        return primary != null && primary.what() != null && primary.what().equals(want);
    }

    private static boolean hasProducer(
            Map<IPatternDetails, Long> patternTimes,
            AEKey want,
            IdentityHashMap<IPatternDetails, Boolean> stack
    ) {
        for (IPatternDetails pattern : patternTimes.keySet()) {
            if (pattern == null || stack.containsKey(pattern)) {
                continue;
            }
            if (patternProduces(pattern, want)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 提交缓存中的合成计划到合成 CPU。
     * 过期、不存在或 UUID 不匹配统一抛 plan_expired（刻意不区分，防枚举）。
     * {@code cpuName} 为空则自动选 CPU；非空须匹配空闲且存储足够的 CPU。
     */
    public static JsonObject submit(MinecraftServer server, IGrid grid, UUID playerUuid, String planId, String cpuName) throws Exception {
        CachedPlan cached = PLAN_CACHE.remove(planId);
        if (cached == null || cached.expiresAt < System.currentTimeMillis()) {
            throw new IllegalStateException("plan_expired");
        }
        // 防止 token 被盗后提交他人规划
        if (!cached.playerUuid.equals(playerUuid)) {
            throw new IllegalStateException("plan_expired");
        }
        IActionSource source = actionSource(server, playerUuid);
        ICraftingCPU target = null;
        if (cpuName != null && !cpuName.isBlank()) {
            target = findCpuByName(grid, cpuName.trim());
            if (target == null) {
                throw new IllegalStateException("cpu_not_found");
            }
            if (target.isBusy()) {
                throw new IllegalStateException("cpu_busy");
            }
            if (target.getAvailableStorage() < cached.plan.bytes()) {
                throw new IllegalStateException("cpu_insufficient_storage");
            }
        }
        // simulate=true：允许在缺料已检查后真正占用 CPU
        ICraftingSubmitResult result = grid.getCraftingService().submitJob(cached.plan, null, target, true, source);
        JsonObject root = new JsonObject();
        root.addProperty("ok", result.successful());
        String errMsg = "";
        try {
            // AE2 不同版本 error / getError 命名不一，反射兼容
            Object err = result.getClass().getMethod("error").invoke(result);
            errMsg = err == null ? "" : String.valueOf(err);
        } catch (ReflectiveOperationException ignored) {
            try {
                Object err = result.getClass().getMethod("getError").invoke(result);
                errMsg = err == null ? "" : String.valueOf(err);
            } catch (ReflectiveOperationException ignored2) {
                errMsg = "rejected";
            }
        }
        root.addProperty("message", result.successful() ? "Crafting job submitted" : errMsg);
        if (result.link() != null) {
            root.addProperty("jobId", result.link().getCraftingID().toString());
        }
        return root;
    }

    /** 兼容旧调用：自动选 CPU。 */
    public static JsonObject submit(MinecraftServer server, IGrid grid, UUID playerUuid, String planId) throws Exception {
        return submit(server, grid, playerUuid, planId, null);
    }

    private static ICraftingCPU findCpuByName(IGrid grid, String cpuName) {
        List<ICraftingCPU> cpus = new ArrayList<>(grid.getCraftingService().getCpus());
        List<String> labels = uniqueCpuLabels(cpus);
        for (int i = 0; i < cpus.size(); i++) {
            if (labels.get(i).equals(cpuName)) {
                return cpus.get(i);
            }
        }
        return null;
    }

    /**
     * 一步规划并提交：缺料时返回规划 JSON 且 ok=false，不发起 submit。
     */
    public static JsonObject submitDirect(MinecraftServer server, IGrid grid, UUID playerUuid, String key, String amountStr) throws Exception {
        JsonObject planned = plan(server, grid, playerUuid, key, amountStr);
        if (!planned.get("canSubmit").getAsBoolean()) {
            planned.addProperty("ok", false);
            return planned;
        }
        return submit(server, grid, playerUuid, planned.get("planId").getAsString(), null);
    }

    /**
     * 列出各合成 CPU 忙闲与进度。
     * <p>
     * 进度条优先用 AE2 合成树；数量用最终产物 {@code crafted}/{@code requested}。
     * 树为 {@code Integer.MAX_VALUE} 等脏数据时，进度条改用最终产物比例。
     * CPU 名保证唯一，防止多个未命名 CPU 都叫 "CPU" 导致完成推送误触发。
     * </p>
     */
    public static JsonObject jobs(IGrid grid) {
        JsonArray arr = new JsonArray();
        List<ICraftingCPU> cpus = new ArrayList<>(grid.getCraftingService().getCpus());
        List<String> labels = uniqueCpuLabels(cpus);
        for (int i = 0; i < cpus.size(); i++) {
            ICraftingCPU cpu = cpus.get(i);
            JsonObject job = new JsonObject();
            job.addProperty("cpuName", labels.get(i));
            job.addProperty("busy", cpu.isBusy());
            job.addProperty("status", cpu.isBusy() ? "crafting" : "idle");
            if (cpu.isBusy()) {
                CraftingJobStatus status = cpu.getJobStatus();
                if (status != null) {
                    GenericStack stack = status.crafting();
                    if (stack != null) {
                        JsonObject output = toStackDto(stack.what(), stack.amount(), true);
                        if (output != null) {
                            job.add("output", output);
                            job.addProperty("detail", output.get("id").getAsString() + " x" + output.get("amount").getAsString());
                        }
                    }
                    writeJobProgress(job, cpu, status);
                    job.addProperty("elapsedNanos", Long.toString(Math.max(0, status.elapsedTimeNanos())));
                } else {
                    job.addProperty("detail", cpu.toString());
                }
            }
            arr.add(job);
        }
        JsonObject root = new JsonObject();
        root.add("jobs", arr);
        return root;
    }

    /**
     * 按 {@link #jobs} 返回的唯一 cpuName 取消任务；未找到或空闲返回 ok=false。
     */
    public static JsonObject cancel(IGrid grid, String cpuName) {
        boolean cancelled = false;
        List<ICraftingCPU> cpus = new ArrayList<>(grid.getCraftingService().getCpus());
        List<String> labels = uniqueCpuLabels(cpus);
        for (int i = 0; i < cpus.size(); i++) {
            ICraftingCPU cpu = cpus.get(i);
            if (labels.get(i).equals(cpuName) && cpu.isBusy()) {
                cpu.cancelJob();
                cancelled = true;
                break;
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("ok", cancelled);
        root.addProperty("message", cancelled ? "cancelled" : "cpu_not_found_or_idle");
        return root;
    }

    /**
     * 进度写入：树进度（进度条）与最终产物数量（crafted/requested）同时给出。
     * <ul>
     *   <li>{@code crafted}/{@code requested}：最终产物已交付 / 请求量。</li>
     *   <li>处理样板脏树（totalItems≈MAX_VALUE）时：百分比来自合成树；
     *       remaining 读不到则用百分比估算 crafted，避免一直显示 0/N。</li>
     * </ul>
     */
    private static void writeJobProgress(JsonObject job, ICraftingCPU cpu, CraftingJobStatus status) {
        GenericStack stack = status.crafting();
        long requested = stack != null ? Math.max(0, stack.amount()) : 0;
        Long remaining = finalOutputRemaining(cpu);
        Long craftedFromRemaining = null;
        if (remaining != null && requested > 0) {
            long rem = Math.min(requested, Math.max(0, remaining));
            craftedFromRemaining = requested - rem;
        }
        if (requested > 0) {
            job.addProperty("requested", Long.toString(requested));
        }

        long treeTotal = Math.max(0, status.totalItems());
        long treeProgress = Math.max(0, status.progress());

        // 正常合成树：计数与百分比都可用
        if (isSaneTreeProgress(treeTotal, treeProgress)) {
            long capped = Math.min(treeProgress, treeTotal);
            job.addProperty("progress", Long.toString(capped));
            job.addProperty("totalItems", Long.toString(treeTotal));
            job.addProperty("progressPercent", 100.0 * capped / (double) treeTotal);
            // 有最终产物 remaining 时仍写出 crafted，供数量文案
            if (craftedFromRemaining != null) {
                job.addProperty("crafted", Long.toString(craftedFromRemaining));
            }
            return;
        }

        // 脏树百分比（处理样板常见）
        Double treePct = null;
        if (treeTotal > 0 && treeProgress >= 0 && treeProgress <= treeTotal) {
            treePct = 100.0 * treeProgress / (double) treeTotal;
        }

        Long crafted = craftedFromRemaining;
        // remaining 跨模组读失败或处理样板未及时扣减时，用树百分比估算交付量
        if (crafted == null && treePct != null && requested > 0) {
            crafted = estimateCraftedFromPercent(requested, treePct);
        } else if (crafted != null && treePct != null && requested > 0) {
            // remaining 卡在满额但树已推进：取较大值，避免进度条走了数量仍为 0
            long fromTree = estimateCraftedFromPercent(requested, treePct);
            if (fromTree > crafted) {
                crafted = fromTree;
            }
        }

        if (crafted != null && requested > 0) {
            job.addProperty("crafted", Long.toString(crafted));
            job.addProperty("progress", Long.toString(crafted));
            job.addProperty("totalItems", Long.toString(requested));
            if (treePct != null) {
                job.addProperty("progressPercent", treePct);
            } else {
                job.addProperty("progressPercent", 100.0 * crafted / (double) requested);
            }
            return;
        }

        if (treePct != null) {
            job.addProperty("progressPercent", treePct);
            if (requested > 0) {
                job.addProperty("totalItems", Long.toString(requested));
            }
            return;
        }

        if (requested > 0) {
            job.addProperty("totalItems", Long.toString(requested));
        }
    }

    /** 由进度百分比估算已交付最终产物数；未完成时不超过 requested-1。 */
    private static long estimateCraftedFromPercent(long requested, double percent) {
        if (requested <= 0) {
            return 0;
        }
        double p = Math.min(100.0, Math.max(0.0, percent));
        long estimated = Math.round(requested * p / 100.0);
        if (p < 99.5 && estimated >= requested) {
            estimated = requested - 1;
        }
        if (p <= 0.0) {
            return 0;
        }
        return Math.min(requested, Math.max(0, estimated));
    }

    private static boolean isSaneTreeProgress(long total, long progress) {
        if (total <= 0 || total >= Integer.MAX_VALUE) {
            return false;
        }
        // 进度因容器物品等偶发略超总量；过大则视为脏数据
        return progress >= 0 && progress <= total * 2L;
    }

    /**
     * 读取最终产物剩余量：本模组包内反射/VarHandle + 监视器回退。
     */
    private static Long finalOutputRemaining(ICraftingCPU cpu) {
        if (cpu instanceof CraftingCPUCluster cluster) {
            return Ae2CraftingJobAccess.finalOutputRemaining(cluster);
        }
        return null;
    }

    /** 与游戏内「处理器 #N」一致：无自定义名用 CPU #i；重名则全部追加序号保证唯一。 */
    private static List<String> uniqueCpuLabels(List<ICraftingCPU> cpus) {
        List<String> bases = new ArrayList<>(cpus.size());
        for (int i = 0; i < cpus.size(); i++) {
            bases.add(baseCpuLabel(cpus.get(i), i));
        }
        Map<String, Integer> counts = new HashMap<>();
        for (String base : bases) {
            counts.merge(base, 1, Integer::sum);
        }
        Map<String, Integer> seq = new HashMap<>();
        List<String> out = new ArrayList<>(cpus.size());
        for (String base : bases) {
            if (counts.getOrDefault(base, 0) <= 1) {
                out.add(base);
                continue;
            }
            int n = seq.merge(base, 1, Integer::sum);
            out.add(base + " #" + n);
        }
        return out;
    }

    private static String baseCpuLabel(ICraftingCPU cpu, int index) {
        var name = cpu.getName();
        if (name != null) {
            String s = name.getString();
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return "CPU #" + (index + 1);
    }

    /**
     * 构造 AE2 IActionSource：在线玩家优先 ofPlayer；离线则尝试 empty/EMPTY。
     * 两者皆不可用时拒绝提交，避免无安全上下文的「幽灵」合成。
     */
    private static IActionSource actionSource(MinecraftServer server, UUID playerUuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerUuid);
        if (online != null) {
            return playerActionSource(online);
        }
        // 离线合成：优先空机器源，不冒充在线玩家权限
        try {
            var empty = IActionSource.class.getMethod("empty").invoke(null);
            if (empty instanceof IActionSource src) {
                return src;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            var field = IActionSource.class.getField("EMPTY");
            Object empty = field.get(null);
            if (empty instanceof IActionSource src) {
                return src;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        // 部分 AE2 构建仅有 ofPlayer；离线无法安全提交
        throw new IllegalStateException("offline_action_source_unavailable");
    }

    /**
     * 反射调用 IActionSource.ofPlayer；失败说明当前 AE2 无该工厂方法。
     */
    private static IActionSource playerActionSource(Player player) {
        try {
            var method = IActionSource.class.getMethod("ofPlayer", Player.class);
            Object src = method.invoke(null, player);
            if (src instanceof IActionSource actionSource) {
                return actionSource;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        throw new IllegalStateException("player_action_source_unavailable");
    }

    /**
     * 解析前端传来的物品键：支持 {@code item:ns:path}、{@code ns:path}，
     * 以及带 NBT 花括号前缀的 id（花括号后内容当前丢弃，仅用注册名建栈）。
     */
    private static AEItemKey parseItemKey(String key) {
        // formats: item:minecraft:iron_ingot  OR minecraft:iron_ingot  OR ae key toString
        String raw = key;
        if (raw.startsWith("item:")) {
            raw = raw.substring(5);
        }
        // 含 { 时只取注册名段，避免 TagParser 复杂度进入热路径
        ItemStack stack = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                net.minecraft.resources.ResourceLocation.tryParse(raw.contains("{") ? raw.substring(0, raw.indexOf('{')) : raw)
        ));
        AEItemKey aeKey = AEItemKey.of(stack);
        if (aeKey == null) {
            throw new IllegalArgumentException("invalid_item");
        }
        return aeKey;
    }

    /**
     * 物品或流体或其它 AEKey → DTO；无法识别时以 kind=other 暴露（预留扩展）。
     * package 可见供 PatternService / 配方树序列化复用。
     */
    static JsonObject toStackDto(AEKey key, long amount, boolean craftable) {
        if (key instanceof AEItemKey itemKey) {
            return toItemDto(itemKey, amount, craftable);
        }
        if (key instanceof AEFluidKey fluidKey) {
            return toFluidDto(fluidKey, amount, craftable);
        }
        return toOtherDto(key, amount, craftable);
    }

    /**
     * 统一物品 DTO：key/id/显示名/数量字符串/可合成/图标 URL。
     * amount 用字符串避免 JS 大整数精度问题。
     */
    private static JsonObject toItemDto(AEItemKey itemKey, long amount, boolean craftable) {
        JsonObject dto = new JsonObject();
        String id = itemKey.getId().toString();
        dto.addProperty("key", "item:" + id);
        dto.addProperty("id", id);
        dto.addProperty("kind", "item");
        dto.addProperty("displayName", itemKey.getDisplayName().getString());
        dto.addProperty("amount", Long.toString(amount));
        dto.addProperty("craftable", craftable);
        dto.addProperty("isFluid", false);
        dto.addProperty("amountPerUnit", itemKey.getAmountPerUnit());
        // 图标路由由前端/静态服务约定；此处只给相对路径
        dto.addProperty("iconUrl", "/api/v1/icons/item/" + id.replace(":", "/"));
        return dto;
    }

    /**
     * 流体 DTO：amount 为 AE 内部单位（Forge 上通常为 mB，1000 = 1 桶）。
     * amountPerUnit 供前端换算桶/mB 显示。
     */
    private static JsonObject toFluidDto(AEFluidKey fluidKey, long amount, boolean craftable) {
        JsonObject dto = new JsonObject();
        String id = fluidKey.getId().toString();
        dto.addProperty("key", "fluid:" + id);
        dto.addProperty("id", id);
        dto.addProperty("kind", "fluid");
        dto.addProperty("displayName", fluidKey.getDisplayName().getString());
        dto.addProperty("amount", Long.toString(amount));
        dto.addProperty("craftable", craftable);
        dto.addProperty("isFluid", true);
        dto.addProperty("amountPerUnit", fluidKey.getAmountPerUnit());
        dto.addProperty("iconUrl", "/api/v1/icons/fluid/" + id.replace(":", "/"));
        return dto;
    }

    /**
     * 非物品/流体的 AEKey 兜底 DTO（matter 等未来类型）；kind=other。
     */
    private static JsonObject toOtherDto(AEKey key, long amount, boolean craftable) {
        JsonObject dto = new JsonObject();
        String id = key.toString();
        dto.addProperty("key", "other:" + id);
        dto.addProperty("id", id);
        dto.addProperty("kind", "other");
        dto.addProperty("displayName", key.getDisplayName().getString());
        dto.addProperty("amount", Long.toString(amount));
        dto.addProperty("craftable", craftable);
        dto.addProperty("isFluid", false);
        dto.addProperty("amountPerUnit", key.getAmountPerUnit());
        dto.addProperty("iconUrl", "");
        return dto;
    }

    /**
     * 过滤：kind=item/fluid/other；filter=stocked/craftable；query 匹配显示名或 id。
     * other 匹配一切非 item/fluid 的 kind（含显式 other 与未来新类型字符串）。
     */
    private static boolean matches(JsonObject dto, String query, String kind, String filter) {
        String dtoKind = resolveDtoKind(dto);
        if ("item".equalsIgnoreCase(kind) && !"item".equalsIgnoreCase(dtoKind)) {
            return false;
        }
        if ("fluid".equalsIgnoreCase(kind) && !"fluid".equalsIgnoreCase(dtoKind)) {
            return false;
        }
        if ("other".equalsIgnoreCase(kind) && ("item".equalsIgnoreCase(dtoKind) || "fluid".equalsIgnoreCase(dtoKind))) {
            return false;
        }
        if ("stocked".equalsIgnoreCase(filter) && Long.parseLong(dto.get("amount").getAsString()) <= 0) {
            return false;
        }
        if ("craftable".equalsIgnoreCase(filter) && !dto.get("craftable").getAsBoolean()) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase(Locale.ROOT);
        return dto.get("displayName").getAsString().toLowerCase(Locale.ROOT).contains(q)
                || dto.get("id").getAsString().toLowerCase(Locale.ROOT).contains(q);
    }

    /** 优先读 kind；兼容仅有 isFluid 的旧缓存/客户端 */
    private static String resolveDtoKind(JsonObject dto) {
        if (dto.has("kind") && !dto.get("kind").isJsonNull()) {
            String k = dto.get("kind").getAsString();
            if (k != null && !k.isBlank()) {
                return k;
            }
        }
        boolean isFluid = dto.has("isFluid") && dto.get("isFluid").getAsBoolean();
        return isFluid ? "fluid" : "item";
    }

    /** all / craftable 需要把零库存可合成行并入列表 */
    private static boolean includesCraftableZeros(String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String f = filter.toLowerCase(Locale.ROOT);
        return "all".equals(f) || "craftable".equals(f);
    }

    /**
     * 进程内规划缓存条目。
     *
     * @param plan       AE2 规划对象
     * @param playerUuid 归属玩家
     * @param expiresAt  过期 epoch ms
     */
    private record CachedPlan(ICraftingPlan plan, UUID playerUuid, long expiresAt) {
    }

    /** 单次网格库存采样：revision + 原始栈 + 网络摘要 + 正在合成产物（置顶）。 */
    private record GridSnap(
            long contentRevision,
            KeyCounter available,
            Set<AEKey> craftables,
            JsonObject network,
            List<AEKey> craftingOutputs
    ) {
    }

    private record CpuScan(int cpuCount, int busyCpuCount, List<AEKey> craftingOutputs) {
    }

    /** 某组筛选/排序下的完整列表（分页前）。 */
    private record ViewSnap(long contentRevision, List<JsonObject> items) {
    }
}
