package com.lanuis.ae2web.ae2;

import appeng.api.networking.IGrid;
import com.google.gson.JsonObject;
import com.lanuis.ae2web.auth.BindingStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理员：枚举全部绑定并探测网络端点状态（须在主线程调用）。
 */
public final class AdminBindingService {
    private AdminBindingService() {
    }

    public static JsonObject listBindings(MinecraftServer server) {
        List<JsonObject> rows = new ArrayList<>();
        for (BindingStore.BindingRecord binding : BindingStore.listAll()) {
            rows.add(toRow(server, binding));
        }
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("total", rows.size());
        var arr = new com.google.gson.JsonArray();
        for (JsonObject row : rows) {
            arr.add(row);
        }
        root.add("bindings", arr);
        return root;
    }

    private static JsonObject toRow(MinecraftServer server, BindingStore.BindingRecord binding) {
        JsonObject row = new JsonObject();
        String uuid = binding.playerUuid == null ? "" : binding.playerUuid;
        row.addProperty("playerUuid", uuid);
        row.addProperty("playerName", binding.playerName == null ? "" : binding.playerName);
        row.addProperty("updatedAt", binding.updatedAt);
        boolean playerOnline = false;
        if (!uuid.isEmpty()) {
            try {
                ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(uuid));
                playerOnline = p != null;
            } catch (IllegalArgumentException ignored) {
            }
        }
        row.addProperty("playerOnline", playerOnline);
        row.addProperty("hasNetworkLink", binding.networkLink != null);

        JsonObject endpoint = new JsonObject();
        if (binding.linkSummary != null) {
            copyStr(binding.linkSummary, endpoint, "terminalItemId");
            copyStr(binding.linkSummary, endpoint, "dimension");
            copyStr(binding.linkSummary, endpoint, "playerPos");
        }
        row.add("endpoint", endpoint);

        boolean networkAvailable = false;
        boolean networkOnline = false;
        int itemTypes = 0;
        int cpuCount = 0;
        int busyCpuCount = 0;
        if (binding.networkLink != null) {
            Optional<IGrid> grid = Ae2QueryService.gridForBinding(server, binding);
            if (grid.isPresent()) {
                networkAvailable = true;
                JsonObject summary = Ae2QueryService.networkStatusLite(grid.get());
                networkOnline = summary.get("online").getAsBoolean();
                itemTypes = summary.get("itemTypes").getAsInt();
                cpuCount = summary.get("cpuCount").getAsInt();
                busyCpuCount = summary.get("busyCpuCount").getAsInt();
            }
        }
        row.addProperty("networkAvailable", networkAvailable);
        row.addProperty("networkOnline", networkOnline);
        row.addProperty("itemTypes", itemTypes);
        row.addProperty("cpuCount", cpuCount);
        row.addProperty("busyCpuCount", busyCpuCount);
        return row;
    }

    private static void copyStr(JsonObject from, JsonObject to, String key) {
        if (from.has(key) && from.get(key).isJsonPrimitive()) {
            to.addProperty(key, from.get(key).getAsString());
        } else {
            to.addProperty(key, "");
        }
    }
}
