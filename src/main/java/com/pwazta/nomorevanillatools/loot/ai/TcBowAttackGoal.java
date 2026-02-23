package com.pwazta.nomorevanillatools.loot.ai;

import java.util.EnumSet;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import com.pwazta.nomorevanillatools.util.TcRangedItems;
import slimeknights.tconstruct.library.modifiers.hook.interaction.GeneralInteractionModifierHook;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableBowItem;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

/**
 * TC-aware bow attack goal for mobs. Copy of vanilla {@code RangedBowAttackGoal} with targeted changes:
 * <ul>
 *   <li>{@code instanceof} checks expanded to include {@link ModifiableBowItem}</li>
 *   <li>{@code startDrawtime()} called when mob begins drawing — required because mobs don't
 *       go through {@code Item.use()}, so drawtime persistent data is never set otherwise</li>
 *   <li>TC bows fire arrows with TC stats applied (damage, velocity, modifier hooks) via
 *       {@link TcProjectileHelper} — mirrors TC's internal {@code releaseUsing()} formula</li>
 * </ul>
 *
 * <p>TC's {@code releaseUsing()} requires ammo items which mobs don't carry, so we create
 * the arrow ourselves and apply TC stats directly via {@link TcProjectileHelper#applyLauncherStats}.
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

    private boolean isHoldingBow() {
        return this.mob.isHolding(is -> TcRangedItems.isBow(is.getItem()));
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
                    fireTcBowArrow(target);
                    this.attackTime = this.attackIntervalMin;
                }
            }
        } else if (--this.attackTime <= 0 && this.seeTime >= -60) {
            this.mob.startUsingItem(ProjectileUtil.getWeaponHoldingHand(this.mob, TcRangedItems::isBow));
            // Mobs bypass Item.use() — init drawtime manually to avoid divide-by-zero in getToolCharge()
            initDrawtimeIfTcBow();
        }
    }

    /**
     * Creates and fires an arrow with TC stats applied. Falls back to vanilla if not a TC bow.
     *
     * <p>Arrow creation follows vanilla's {@code AbstractSkeleton.performRangedAttack()} pattern.
     * TC stats (damage, velocity, modifier hooks) are applied via {@link TcProjectileHelper}.
     */
    private void fireTcBowArrow(LivingEntity target) {
        ItemStack bowStack = this.mob.getItemInHand(
            ProjectileUtil.getWeaponHoldingHand(this.mob, TcRangedItems::isBow));

        // Fall back to vanilla if not a TC bow (handles weapon swap mid-goal)
        if (bowStack.isEmpty() || !(bowStack.getItem() instanceof ModifiableBowItem)) {
            this.mob.performRangedAttack(target, 1.0F);
            return;
        }

        Level level = this.mob.level();
        ArrowItem arrowItem = (ArrowItem) Items.ARROW;
        AbstractArrow arrow = arrowItem.createArrow(level, new ItemStack(Items.ARROW), this.mob);

        // Apply TC stats (shared helper — same formula as TC's internal code)
        ToolStack tool = ToolStack.from(bowStack);
        TcProjectileHelper.applyLauncherStats(tool, this.mob, arrow);

        // Velocity from TC stats
        float velocity = TcProjectileHelper.getLauncherVelocity(tool, this.mob);

        // Trajectory (same math as AbstractSkeleton.performRangedAttack)
        double dx = target.getX() - this.mob.getX();
        double dy = target.getY(0.3333333333333333D) - arrow.getY();
        double dz = target.getZ() - this.mob.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        int difficulty = this.mob.level().getDifficulty().getId();
        float inaccuracy = (float)(14 - difficulty * 4);

        arrow.shoot(dx, dy + dist * 0.2D, dz, velocity * 1.6F, inaccuracy);

        // Tool damage + fire
        TcProjectileHelper.damageLauncher(tool, this.mob);
        level.addFreshEntity(arrow);
        this.mob.playSound(SoundEvents.SKELETON_SHOOT, 1.0F,
            1.0F / (this.mob.getRandom().nextFloat() * 0.4F + 0.8F));
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
