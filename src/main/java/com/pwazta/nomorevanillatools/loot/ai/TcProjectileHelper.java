package com.pwazta.nomorevanillatools.loot.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import slimeknights.tconstruct.library.modifiers.ModifierEntry;
import slimeknights.tconstruct.library.modifiers.ModifierHooks;
import slimeknights.tconstruct.library.modifiers.hook.build.ConditionalStatModifierHook;
import slimeknights.tconstruct.library.tools.capability.EntityModifierCapability;
import slimeknights.tconstruct.library.tools.capability.PersistentDataCapability;
import slimeknights.tconstruct.library.tools.helper.ToolDamageUtil;
import slimeknights.tconstruct.library.tools.nbt.ModDataNBT;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;
import slimeknights.tconstruct.library.tools.stat.ToolStats;

/**
 * Applies TC launcher (bow/crossbow) stats to arrow entities.
 *
 * <p>Mirrors the damage/velocity/modifier hook pattern used internally by TC in both
 * {@code ModifiableBowItem.releaseUsing()} and {@code ModifiableCrossbowItem.fireCrossbow()}.
 * Verified against decompiled TC 1.20.1 source.
 *
 * <p>The crossbow path uses {@code fireCrossbow()} directly (TC's API handles everything).
 * The bow path calls this helper because TC's {@code releaseUsing()} requires ammo items
 * that mobs don't carry, so we must apply stats manually.
 */
public final class TcProjectileHelper {

    private TcProjectileHelper() {}

    /**
     * Applies TC tool stats and modifier hooks to an arrow entity.
     *
     * <p>Formula (from decompiled TC source — identical in releaseUsing and fireCrossbow):
     * <pre>
     * baseDamage = (arrow.getBaseDamage() - 2.0) + tool.getStats().get(PROJECTILE_DAMAGE)
     * modifiedDamage = ConditionalStatModifierHook.getModifiedStat(tool, shooter, PROJECTILE_DAMAGE, baseDamage)
     * arrow.setBaseDamage(modifiedDamage)
     * </pre>
     *
     * @param tool    the TC launcher ToolStack (bow or crossbow)
     * @param shooter the mob firing the arrow
     * @param arrow   the arrow entity to modify (vanilla or TC — works on any AbstractArrow)
     */
    public static void applyLauncherStats(ToolStack tool, LivingEntity shooter, AbstractArrow arrow) {
        // Damage: zero out vanilla base (2.0), replace with TC PROJECTILE_DAMAGE
        float baseDamage = (float)(arrow.getBaseDamage() - 2.0)
            + tool.getStats().get(ToolStats.PROJECTILE_DAMAGE);
        float modifiedDamage = ConditionalStatModifierHook.getModifiedStat(
            tool, shooter, ToolStats.PROJECTILE_DAMAGE, baseDamage);
        arrow.setBaseDamage(modifiedDamage);

        // Critical arrow (TC pattern: always crit when fired from launcher)
        arrow.setCritArrow(true);

        // Transfer modifier data for hit hooks (Fiery, Piercing, etc.)
        // TC registers `entity instanceof Projectile` predicate, so vanilla arrows have these capabilities
        EntityModifierCapability.getCapability(arrow).addModifiers(tool.getModifiers());
        ModDataNBT persistentData = PersistentDataCapability.getOrWarn(arrow);
        for (ModifierEntry modifier : tool.getModifierList()) {
            modifier.getHook(ModifierHooks.PROJECTILE_LAUNCH)
                .onProjectileLaunch(tool, modifier, shooter, arrow, arrow, persistentData, true);
        }
    }

    /**
     * Returns the TC velocity stat for a launcher, with conditional modifiers applied.
     */
    public static float getLauncherVelocity(ToolStack tool, LivingEntity shooter) {
        return ConditionalStatModifierHook.getModifiedStat(tool, shooter, ToolStats.VELOCITY);
    }

    /**
     * Damages the launcher tool by 1 durability (standard per-shot cost).
     */
    public static void damageLauncher(ToolStack tool, LivingEntity shooter) {
        ToolDamageUtil.damageAnimated(tool, 1, shooter);
    }
}
