package com.pwazta.nomorevanillatools.loot.ai;

import java.util.EnumSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import slimeknights.tconstruct.library.modifiers.hook.interaction.GeneralInteractionModifierHook;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableBowItem;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

/**
 * TC-aware bow attack goal for mobs. Copy of vanilla {@code RangedBowAttackGoal} with targeted changes:
 * <ul>
 *   <li>{@code instanceof} checks expanded to include {@link ModifiableBowItem}</li>
 *   <li>{@code startDrawtime()} called when mob begins drawing — required because mobs don't
 *       go through {@code Item.use()}, so drawtime persistent data is never set otherwise</li>
 * </ul>
 *
 * <p>Arrow creation uses vanilla's {@code performRangedAttack()} — TC's {@code releaseUsing()}
 * requires ammo items which mobs don't carry, so it can't handle mob arrow creation.
 * Arrows are vanilla-quality; TC bow modifiers don't apply to mob-fired arrows.
 */
public class TcBowAttackGoal<T extends Mob & RangedAttackMob> extends Goal {
    private final T mob;
    private final double speedModifier;
    private int attackIntervalMin;
    private final float attackRadiusSqr;
    private int attackTime = -1;
    private int seeTime;
    private boolean strafingClockwise;
    private boolean strafingBackwards;
    private int strafingTime = -1;

    public TcBowAttackGoal(T pMob, double pSpeedModifier, int pAttackIntervalMin, float pAttackRadius) {
        this.mob = pMob;
        this.speedModifier = pSpeedModifier;
        this.attackIntervalMin = pAttackIntervalMin;
        this.attackRadiusSqr = pAttackRadius * pAttackRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public void setMinAttackInterval(int pAttackCooldown) {
        this.attackIntervalMin = pAttackCooldown;
    }

    @Override
    public boolean canUse() {
        return this.mob.getTarget() != null && this.isHoldingBow();
    }

    private static boolean isBow(Item item) {
        return item instanceof BowItem || item instanceof ModifiableBowItem;
    }

    private boolean isHoldingBow() {
        return this.mob.isHolding(is -> isBow(is.getItem()));
    }

    @Override
    public boolean canContinueToUse() {
        return (this.canUse() || !this.mob.getNavigation().isDone()) && this.isHoldingBow();
    }

    @Override
    public void start() {
        super.start();
        this.mob.setAggressive(true);
    }

    @Override
    public void stop() {
        super.stop();
        this.mob.setAggressive(false);
        this.seeTime = 0;
        this.attackTime = -1;
        this.mob.stopUsingItem();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) return;

        double distSqr = this.mob.distanceToSqr(target.getX(), target.getY(), target.getZ());
        boolean canSee = this.mob.getSensing().hasLineOfSight(target);
        boolean wasSeen = this.seeTime > 0;
        if (canSee != wasSeen) this.seeTime = 0;

        if (canSee) ++this.seeTime;
        else --this.seeTime;

        if (distSqr <= (double) this.attackRadiusSqr && this.seeTime >= 20) {
            this.mob.getNavigation().stop();
            ++this.strafingTime;
        } else {
            this.mob.getNavigation().moveTo(target, this.speedModifier);
            this.strafingTime = -1;
        }

        if (this.strafingTime >= 20) {
            if ((double) this.mob.getRandom().nextFloat() < 0.3D) this.strafingClockwise = !this.strafingClockwise;
            if ((double) this.mob.getRandom().nextFloat() < 0.3D) this.strafingBackwards = !this.strafingBackwards;
            this.strafingTime = 0;
        }

        if (this.strafingTime > -1) {
            if (distSqr > (double) (this.attackRadiusSqr * 0.75F)) this.strafingBackwards = false;
            else if (distSqr < (double) (this.attackRadiusSqr * 0.25F)) this.strafingBackwards = true;

            this.mob.getMoveControl().strafe(this.strafingBackwards ? -0.5F : 0.5F, this.strafingClockwise ? 0.5F : -0.5F);
            Entity vehicle = this.mob.getControlledVehicle();
            if (vehicle instanceof Mob mount) mount.lookAt(target, 30.0F, 30.0F);
            this.mob.lookAt(target, 30.0F, 30.0F);
        } else {
            this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }

        if (this.mob.isUsingItem()) {
            if (!canSee && this.seeTime < -60) {
                this.mob.stopUsingItem();
            } else if (canSee) {
                int drawTicks = this.mob.getTicksUsingItem();
                if (drawTicks >= 20) {
                    this.mob.stopUsingItem();
                    // Use vanilla performRangedAttack() — TC's releaseUsing() requires ammo items mobs don't carry
                    this.mob.performRangedAttack(target, BowItem.getPowerForTime(drawTicks));
                    this.attackTime = this.attackIntervalMin;
                }
            }
        } else if (--this.attackTime <= 0 && this.seeTime >= -60) {
            this.mob.startUsingItem(ProjectileUtil.getWeaponHoldingHand(this.mob, TcBowAttackGoal::isBow));
            // Mobs bypass Item.use() — init drawtime manually to avoid divide-by-zero in getToolCharge()
            initDrawtimeIfTcBow();
        }
    }

    /** Initializes drawtime in persistent data if the mob is holding a TC bow. */
    private void initDrawtimeIfTcBow() {
        ItemStack weapon = this.mob.getUseItem();
        if (!weapon.isEmpty() && weapon.getItem() instanceof ModifiableBowItem) {
            ToolStack toolStack = ToolStack.from(weapon);
            GeneralInteractionModifierHook.startDrawtime(toolStack, this.mob, 1.0f);
        }
    }
}
