package com.lanuis.ae2web.client.icon;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKeyType;
import com.lanuis.ae2web.icon.IconResourcePaths;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** 内置 item / fluid 两类 Provider。 */
public final class AeKeyImageProviders {
    private AeKeyImageProviders() {
    }

    public static List<AeKeyImageProvider<?>> all() {
        List<AeKeyImageProvider<?>> list = new ArrayList<>(2);
        list.add(ITEM);
        list.add(FLUID);
        return list;
    }

    public static final AeKeyImageProvider<AEItemKey> ITEM = new AeKeyImageProvider<>() {
        @Override
        public AEKeyType keyType() {
            return AEKeyType.items();
        }

        @Override
        public String kind() {
            return IconResourcePaths.KIND_ITEM;
        }

        @Override
        public Iterable<AEItemKey> allEntries() {
            List<AEItemKey> keys = new ArrayList<>();
            for (var item : ForgeRegistries.ITEMS) {
                keys.add(AEItemKey.of(item));
            }
            return keys;
        }
    };

    public static final AeKeyImageProvider<AEFluidKey> FLUID = new AeKeyImageProvider<>() {
        @Override
        public AEKeyType keyType() {
            return AEKeyType.fluids();
        }

        @Override
        public String kind() {
            return IconResourcePaths.KIND_FLUID;
        }

        @Override
        public Iterable<AEFluidKey> allEntries() {
            List<AEFluidKey> keys = new ArrayList<>();
            for (Fluid fluid : ForgeRegistries.FLUIDS) {
                if (!fluid.isSource(fluid.defaultFluidState())) {
                    continue;
                }
                keys.add(AEFluidKey.of(fluid));
            }
            return keys;
        }
    };
}
