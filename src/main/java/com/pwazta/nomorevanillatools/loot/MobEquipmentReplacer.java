package com.pwazta.nomorevanillatools.loot;

import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.ExampleMod;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import slimeknights.tconstruct.library.materials.MaterialRegistry;

/**
 * Replaces vanilla tools and armor on mobs at spawn time with TC equivalents.
 * Uses FinalizeSpawnEvent so mobs visually hold TC tools AND drop them naturally on death.
 *
 * Drop chances are not affected — mob.setItemSlot() only changes the item,
 * drop chances are already set by populateDefaultEquipmentSlots().
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MobEquipmentReplacer {

    @SubscribeEvent
    public static void onMobSpawn(MobSpawnEvent.FinalizeSpawn event) {
        if (!Config.replaceMobEquipment) return;
        if (!MaterialRegistry.isFullyLoaded()) return;

        Mob mob = event.getEntity();
        RandomSource random = mob.getRandom();

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack current = mob.getItemBySlot(slot);
            if (current.isEmpty()) continue;

            ItemStack replacement = TinkerToolBuilder.tryReplace(current, random);
            if (replacement != null) mob.setItemSlot(slot, replacement);
        }
    }
}
