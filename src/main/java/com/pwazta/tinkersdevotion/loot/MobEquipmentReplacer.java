package com.pwazta.tinkersdevotion.loot;

import com.pwazta.tinkersdevotion.Config;
import com.pwazta.tinkersdevotion.TinkersDevotion;
import com.pwazta.tinkersdevotion.loot.ai.RangedGoalHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import slimeknights.tconstruct.library.materials.MaterialRegistry;

/**
 * Replaces vanilla tools, armor, and ranged weapons on mobs at spawn time with TC equivalents.
 *
 * EntityJoinLevelEvent fires after {@code Mob#finalizeSpawn()} populates equipment slots.
 * LOWEST priority so we run after other mods' equipment handlers on the same event.
 * Idempotent on re-fire: tryReplace only matches vanilla items; TC items are skipped.
 */
@Mod.EventBusSubscriber(modid = TinkersDevotion.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MobEquipmentReplacer {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!Config.replaceMobEquipment) return;
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (!MaterialRegistry.isFullyLoaded()) return;

        RandomSource random = mob.getRandom();

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack current = mob.getItemBySlot(slot);
            if (current.isEmpty()) continue;

            ItemStack replacement = TinkerToolBuilder.tryReplace(current, random);
            if (replacement != null) mob.setItemSlot(slot, replacement);
        }

        if (Config.replaceMobRangedAI)
            RangedGoalHelper.ensureGoalsForRangedWeapons(mob);
    }
}
