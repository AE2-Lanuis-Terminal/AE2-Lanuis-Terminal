package com.lanuis.ae2web.ae2;

import appeng.blockentity.crafting.CraftingMonitorBlockEntity;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.lanuis.ae2web.Ae2LanuisMod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跨包读取 AE2 合成任务 {@code remainingAmount}。
 * 不声明 {@code package appeng.*}（避免 JPMS split package）；
 * Forge 模组间 {@code privateLookupIn} 常失败，优先 {@link ObfuscationReflectionHelper}。
 */
public final class Ae2CraftingJobAccess {
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private Ae2CraftingJobAccess() {
    }

    /**
     * 最终产物还剩多少未交付；无法读取时返回 null。
     */
    public static Long finalOutputRemaining(CraftingCPUCluster cluster) {
        if (cluster == null) {
            return null;
        }
        Long fromJob = remainingFromLogic(cluster.craftingLogic);
        if (fromJob != null) {
            return fromJob;
        }
        return remainingFromMonitors(cluster);
    }

    private static Long remainingFromLogic(CraftingCpuLogic logic) {
        if (logic == null) {
            return null;
        }
        try {
            ExecutingCraftingJob job = jobOf(logic);
            if (job == null) {
                return null;
            }
            Long remaining = readLongField(ExecutingCraftingJob.class, job, "remainingAmount");
            if (remaining != null) {
                return remaining;
            }
        } catch (Throwable t) {
            warnOnce(t);
        }
        return null;
    }

    private static ExecutingCraftingJob jobOf(CraftingCpuLogic logic) {
        try {
            ExecutingCraftingJob viaHelper = ObfuscationReflectionHelper.getPrivateValue(
                    CraftingCpuLogic.class, logic, "job");
            if (viaHelper != null) {
                return viaHelper;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            Field jobField = CraftingCpuLogic.class.getDeclaredField("job");
            jobField.setAccessible(true);
            Object job = jobField.get(logic);
            return job instanceof ExecutingCraftingJob executing ? executing : null;
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Long readLongField(Class<?> owner, Object instance, String name) {
        try {
            Object viaHelper = ObfuscationReflectionHelper.getPrivateValue(
                    (Class<Object>) owner, instance, name);
            if (viaHelper instanceof Long l) {
                return l;
            }
            if (viaHelper instanceof Number n) {
                return n.longValue();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.getLong(instance);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /**
     * 合成监视器上的显示栈即 remainingAmount（无监视器时为空）。
     */
    @SuppressWarnings("unchecked")
    private static Long remainingFromMonitors(CraftingCPUCluster cluster) {
        try {
            List<CraftingMonitorBlockEntity> monitors = null;
            try {
                monitors = ObfuscationReflectionHelper.getPrivateValue(
                        CraftingCPUCluster.class, cluster, "status");
            } catch (Throwable ignored) {
                Field statusField = CraftingCPUCluster.class.getDeclaredField("status");
                statusField.setAccessible(true);
                monitors = (List<CraftingMonitorBlockEntity>) statusField.get(cluster);
            }
            if (monitors == null || monitors.isEmpty()) {
                return null;
            }
            for (CraftingMonitorBlockEntity mon : monitors) {
                var progress = mon.getJobProgress();
                if (progress != null) {
                    return progress.amount();
                }
            }
        } catch (Throwable t) {
            warnOnce(t);
        }
        return null;
    }

    private static void warnOnce(Throwable t) {
        if (WARNED.compareAndSet(false, true)) {
            Ae2LanuisMod.LOGGER.debug("AE2 remainingAmount access failed (will estimate from tree %): {}", t.toString());
        }
    }
}
