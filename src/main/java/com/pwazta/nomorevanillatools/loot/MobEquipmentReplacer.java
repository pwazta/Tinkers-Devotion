package com.pwazta.nomorevanillatools.loot;

import com.pwazta.nomorevanillatools.Config;
import com.pwazta.nomorevanillatools.ExampleMod;
import com.pwazta.nomorevanillatools.loot.ai.RangedGoalHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import slimeknights.tconstruct.library.materials.MaterialRegistry;

/**
 * Replaces vanilla tools, armor, and ranged weapons on mobs at spawn time with TC equivalents.
 *
 * Uses EntityJoinLevelEvent instead of FinalizeSpawn because FinalizeSpawn fires BEFORE
 * Mob#finalizeSpawn() runs — meaning populateDefaultEquipmentSlots() hasn't assigned
 * equipment yet. EntityJoinLevelEvent fires after all spawning logic is complete.
 *
 * Naturally idempotent on chunk reload: tryReplace only matches vanilla items (HashMap lookup),
 * so already-replaced TC items are skipped with negligible overhead.
 */
@Mod.EventBusSubscriber(modid = ExampleMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MobEquipmentReplacer {

    @SubscribeEvent
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
