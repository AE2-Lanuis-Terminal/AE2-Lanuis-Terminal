package com.lanuis.ae2web.auth;

import com.lanuis.ae2web.config.ModConfig;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * Web / 命令共用的 OP 管理员判定（原版权限等级）。
 */
public final class AdminAccess {
    private AdminAccess() {
    }

    public static int requiredLevel() {
        return ModConfig.ADMIN_PERMISSION_LEVEL.get();
    }

    /**
     * 是否达到 {@link ModConfig#ADMIN_PERMISSION_LEVEL}。
     * 离线玩家按 ops 列表 / 单人房主判定。
     */
    public static boolean isAdmin(MinecraftServer server, UUID playerUuid) {
        if (server == null || playerUuid == null) {
            return false;
        }
        int required = requiredLevel();
        ServerPlayer online = server.getPlayerList().getPlayer(playerUuid);
        if (online != null) {
            return server.getProfilePermissions(online.getGameProfile()) >= required;
        }
        GameProfile profile = resolveProfile(server, playerUuid);
        return server.getProfilePermissions(profile) >= required;
    }

    private static GameProfile resolveProfile(MinecraftServer server, UUID uuid) {
        Optional<GameProfile> cached = server.getProfileCache() == null
                ? Optional.empty()
                : server.getProfileCache().get(uuid);
        return cached.orElseGet(() -> new GameProfile(uuid, ""));
    }
}
