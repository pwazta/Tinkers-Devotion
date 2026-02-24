package com.pwazta.nomorevanillatools.loot.ai;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedCrossbowAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.ItemStack;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableBowItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem;

/**
 * Encapsulated AI goal management for mobs holding TC ranged weapons.
 * Detects TC bows/crossbows, removes incompatible vanilla goals, and attaches TC-aware replacements.
 *
 * <p>Idempotent: checks for existing TC goal before adding. Safe to call on every EntityJoinLevelEvent.
 * Handles chunk reload: goals are lost on unload, but mob still holds TC weapon — re-attaches automatically.
 *
 * <p>Performance: for mobs without TC ranged weapons (99%), cost is 2 getItemInHand() calls + 4 instanceof checks.
 */
public class RangedGoalHelper {

    /**
     * Ensures mobs holding TC ranged weapons have the correct AI goals.
     * Call after equipment replacement in MobEquipmentReplacer.
     *
     * <p>Priority 3 for bow: one above the melee fallback (priority 4) that
     * {@code AbstractSkeleton.reassessWeaponGoal()} adds when it doesn't recognize TC bow as {@code Items.BOW}.
     * The leftover melee goal serves as a natural fallback if the bow breaks.
     *
     * <p>Priority 3 for crossbow: matches Pillager's vanilla registration priority.
     */
    public static void ensureGoalsForRangedWeapons(Mob mob) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = mob.getItemInHand(hand);
            if (held.isEmpty()) continue;

            if (held.getItem() instanceof ModifiableBowItem) {
                if (mob instanceof RangedAttackMob && !hasGoalOfType(mob, TcBowAttackGoal.class)) {
                    removeGoalOfType(mob, RangedBowAttackGoal.class);
                    mob.goalSelector.addGoal(3, new TcBowAttackGoal<>((Mob & RangedAttackMob) mob, 1.0D, 20, 15.0F));
                }
                return;
            }
            if (held.getItem() instanceof ModifiableCrossbowItem) {
                if (mob instanceof Monster && mob instanceof RangedAttackMob && mob instanceof CrossbowAttackMob
                        && !hasGoalOfType(mob, TcCrossbowAttackGoal.class)) {
                    removeGoalOfType(mob, RangedCrossbowAttackGoal.class);
                    mob.goalSelector.addGoal(3, new TcCrossbowAttackGoal<>((Monster & RangedAttackMob & CrossbowAttackMob) mob, 1.0D, 8.0F));
                }
                return;
            }
        }
    }

    private static boolean hasGoalOfType(Mob mob, Class<?> goalClass) {
        return mob.goalSelector.getAvailableGoals().stream()
            .anyMatch(wrapped -> goalClass.isInstance(wrapped.getGoal()));
    }

    private static void removeGoalOfType(Mob mob, Class<?> goalClass) {
        // Stop running goals before removal, matching GoalSelector.removeGoal() behavior
        mob.goalSelector.getAvailableGoals().stream()
            .filter(wrapped -> goalClass.isInstance(wrapped.getGoal()))
            .filter(WrappedGoal::isRunning)
            .forEach(WrappedGoal::stop);
        mob.goalSelector.getAvailableGoals()
            .removeIf(wrapped -> goalClass.isInstance(wrapped.getGoal()));
    }
}
