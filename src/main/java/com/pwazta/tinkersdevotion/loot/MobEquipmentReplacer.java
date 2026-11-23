package com.pwazta.tinkersdevotion.loot;

import com.pwazta.tinkersdevotion.Config;
import com.pwazta.tinkersdevotion.TinkersDevotion;
import com.pwazta.tinkersdevotion.loot.ai.RangedGoalHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingEquipmentChangeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import slimeknights.tconstruct.library.materials.MaterialRegistry;

/**
 * Replaces vanilla tools, armor, and ranged weapons on mobs at spawn time with TC equivalents.
 *
 * EntityJoinLevelEvent fires after {@code Mob#finalizeSpawn()} populates equipment slots.
 * LOWEST priority so we run after other mods' equipment handlers on the same event.
 * Idempotent on re-fire: tryReplace only matches items we replace; TC items are skipped.
 *
 * LivingEquipmentChangeEvent catches gear other mods apply a tick or two after spawn
 * (e.g. Guard Villagers), which EntityJoinLevelEvent is too early to see.
 */
@Mod.EventBusSubscriber(modid = TinkersDevotion.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MobEquipmentReplacer {

    // Max age (ticks) for an equipment change to count as deferred spawn gear.
    private static final int SPAWN_EQUIP_WINDOW_TICKS = 10;

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

    // Some mods equip mobs a tick or two after spawn (e.g. Guard Villagers loot-table gear),
    // after EntityJoinLevelEvent. Window excludes later item pickups.
    @SubscribeEvent
    public static void onLivingEquipmentChange(LivingEquipmentChangeEvent event) {
        if (!Config.replaceMobEquipment || !Config.replaceDeferredSpawnEquipment) return;
        if (!(event.getEntity() instanceof Mob mob)) return;
        if (mob.tickCount > SPAWN_EQUIP_WINDOW_TICKS) return;
        if (!MaterialRegistry.isFullyLoaded()) return;

        ItemStack current = event.getTo();
        if (current.isEmpty()) return;

        ItemStack replacement = TinkerToolBuilder.tryReplace(current, mob.getRandom());
        if (replacement == null) return;

        mob.setItemSlot(event.getSlot(), replacement);
        if (Config.replaceMobRangedAI) RangedGoalHelper.ensureGoalsForRangedWeapons(mob);
    }
}
