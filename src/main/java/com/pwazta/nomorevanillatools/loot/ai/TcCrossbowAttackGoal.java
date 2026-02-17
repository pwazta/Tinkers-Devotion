package com.pwazta.nomorevanillatools.loot.ai;

import java.util.EnumSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.TimeUtil;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.CrossbowAttackMob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import slimeknights.tconstruct.library.modifiers.hook.interaction.GeneralInteractionModifierHook;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableLauncherItem;
import slimeknights.tconstruct.library.tools.nbt.ToolStack;

/**
 * TC-aware crossbow attack goal for mobs. Copy of vanilla {@code RangedCrossbowAttackGoal} with targeted changes:
 * <ul>
 *   <li>{@code instanceof} checks expanded to include {@link ModifiableCrossbowItem}</li>
 *   <li>Charge duration: reads from TC persistent data ({@code tconstruct:drawtime}) instead of
 *       {@code CrossbowItem.getChargeDuration()}</li>
 *   <li>Charged state: checks {@code tconstruct:crossbow_ammo} presence instead of vanilla {@code "Charged"} tag</li>
 *   <li>{@code startDrawtime()} called when entering CHARGING state — required because mobs don't
 *       go through {@code Item.use()}, so drawtime is never initialized otherwise</li>
 *   <li>{@code stop()}: clears both {@code tconstruct:crossbow_ammo} and {@code tconstruct:drawback_ammo}
 *       from persistent data on goal interruption</li>
 * </ul>
 */
public class TcCrossbowAttackGoal<T extends Monster & RangedAttackMob & CrossbowAttackMob> extends Goal {
    public static final UniformInt PATHFINDING_DELAY_RANGE = TimeUtil.rangeOfSeconds(1, 2);
    private final T mob;
    private CrossbowState crossbowState = CrossbowState.UNCHARGED;
    private final double speedModifier;
    private final float attackRadiusSqr;
    private int seeTime;
    private int attackDelay;
    private int updatePathDelay;

    public TcCrossbowAttackGoal(T pMob, double pSpeedModifier, float pAttackRadius) {
        this.mob = pMob;
        this.speedModifier = pSpeedModifier;
        this.attackRadiusSqr = pAttackRadius * pAttackRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return this.isValidTarget() && this.isHoldingCrossbow();
    }

    private boolean isHoldingCrossbow() {
        return this.mob.isHolding(is -> is.getItem() instanceof CrossbowItem || is.getItem() instanceof ModifiableCrossbowItem);
    }

    @Override
    public void start() {
        super.start();
        this.mob.setAggressive(true);
    }

    @Override
    public boolean canContinueToUse() {
        return this.isValidTarget() && (this.canUse() || !this.mob.getNavigation().isDone()) && this.isHoldingCrossbow();
    }

    private boolean isValidTarget() {
        return this.mob.getTarget() != null && this.mob.getTarget().isAlive();
    }

    @Override
    public void stop() {
        super.stop();
        this.mob.setAggressive(false);
        this.mob.setTarget(null);
        this.seeTime = 0;
        if (this.mob.isUsingItem()) {
            this.mob.stopUsingItem();
            this.mob.setChargingCrossbow(false);
        }
        // Unconditionally clear charged state — covers CHARGED and READY_TO_ATTACK states
        // where isUsingItem() is false but crossbow_ammo may still be loaded.
        // Vanilla has this same gap (conditional clearing), but stale TC ammo interacts worse
        // with TC's loading logic (releaseUsing skips reload if crossbow_ammo exists).
        ItemStack weapon = this.mob.getItemInHand(ProjectileUtil.getWeaponHoldingHand(this.mob,
            item -> item instanceof CrossbowItem || item instanceof ModifiableCrossbowItem));
        clearChargedState(weapon);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = this.mob.getTarget();
        if (target == null) return;

        boolean canSee = this.mob.getSensing().hasLineOfSight(target);
        boolean wasSeen = this.seeTime > 0;
        if (canSee != wasSeen) this.seeTime = 0;

        if (canSee) ++this.seeTime;
        else --this.seeTime;

        double distSqr = this.mob.distanceToSqr(target);
        boolean shouldMove = (distSqr > (double) this.attackRadiusSqr || this.seeTime < 5) && this.attackDelay == 0;
        if (shouldMove) {
            --this.updatePathDelay;
            if (this.updatePathDelay <= 0) {
                this.mob.getNavigation().moveTo(target, this.canRun() ? this.speedModifier : this.speedModifier * 0.5D);
                this.updatePathDelay = PATHFINDING_DELAY_RANGE.sample(this.mob.getRandom());
            }
        } else {
            this.updatePathDelay = 0;
            this.mob.getNavigation().stop();
        }

        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (this.crossbowState == CrossbowState.UNCHARGED) {
            if (!shouldMove) {
                InteractionHand hand = ProjectileUtil.getWeaponHoldingHand(this.mob,
                    item -> item instanceof CrossbowItem || item instanceof ModifiableCrossbowItem);
                this.mob.startUsingItem(hand);
                // Initialize drawtime for TC crossbows — mobs don't go through Item.use()
                initDrawtimeIfTcCrossbow(hand);
                this.crossbowState = CrossbowState.CHARGING;
                this.mob.setChargingCrossbow(true);
            }
        } else if (this.crossbowState == CrossbowState.CHARGING) {
            if (!this.mob.isUsingItem()) {
                this.crossbowState = CrossbowState.UNCHARGED;
            }

            int ticksUsing = this.mob.getTicksUsingItem();
            ItemStack useItem = this.mob.getUseItem();
            if (ticksUsing >= getChargeDuration(useItem)) {
                this.mob.releaseUsingItem();
                this.crossbowState = CrossbowState.CHARGED;
                this.attackDelay = 20 + this.mob.getRandom().nextInt(20);
                this.mob.setChargingCrossbow(false);
            }
        } else if (this.crossbowState == CrossbowState.CHARGED) {
            --this.attackDelay;
            if (this.attackDelay == 0) {
                this.crossbowState = CrossbowState.READY_TO_ATTACK;
            }
        } else if (this.crossbowState == CrossbowState.READY_TO_ATTACK && canSee) {
            InteractionHand fireHand = ProjectileUtil.getWeaponHoldingHand(this.mob,
                item -> item instanceof CrossbowItem || item instanceof ModifiableCrossbowItem);
            ItemStack weapon = this.mob.getItemInHand(fireHand);
            if (weapon.getItem() instanceof ModifiableCrossbowItem) {
                // Vanilla performCrossbowAttack() fails instanceof CrossbowItem for TC crossbow.
                // Call TC's fireCrossbow() directly — it accepts any LivingEntity and handles
                // projectile creation, modifier hooks, ammo cleanup, and tool damage internally.
                ToolStack tool = ToolStack.from(weapon);
                CompoundTag ammoTag = tool.getPersistentData().getCompound(ModifiableCrossbowItem.KEY_CROSSBOW_AMMO);
                if (!ammoTag.isEmpty()) {
                    ModifiableCrossbowItem.fireCrossbow(tool, this.mob, false, fireHand, ammoTag);
                }
            } else {
                // Vanilla crossbow — use vanilla firing path
                this.mob.performRangedAttack(target, 1.0F);
                clearChargedState(weapon);
            }
            this.crossbowState = CrossbowState.UNCHARGED;
        }
    }

    private boolean canRun() {
        return this.crossbowState == CrossbowState.UNCHARGED;
    }

    /**
     * Returns charge duration in ticks. For TC crossbow, reads from persistent data drawtime.
     * For vanilla crossbow, delegates to {@code CrossbowItem.getChargeDuration()}.
     */
    private int getChargeDuration(ItemStack stack) {
        if (stack.getItem() instanceof ModifiableCrossbowItem) {
            ToolStack tool = ToolStack.from(stack);
            int drawtime = tool.getPersistentData().getInt(GeneralInteractionModifierHook.KEY_DRAWTIME);
            // Fallback: if drawtime is 0 (shouldn't happen since we call initDrawtime), use vanilla default
            return drawtime > 0 ? drawtime : CrossbowItem.getChargeDuration(stack);
        }
        return CrossbowItem.getChargeDuration(stack);
    }

    /**
     * Clears the charged/ammo state for a crossbow. For TC crossbow, removes
     * {@code tconstruct:crossbow_ammo} and {@code tconstruct:drawback_ammo} from persistent data.
     * For vanilla crossbow, calls {@code CrossbowItem.setCharged(stack, false)}.
     */
    private static void clearChargedState(ItemStack stack) {
        if (stack.getItem() instanceof ModifiableCrossbowItem) {
            ToolStack tool = ToolStack.from(stack);
            tool.getPersistentData().remove(ModifiableCrossbowItem.KEY_CROSSBOW_AMMO);
            tool.getPersistentData().remove(ModifiableLauncherItem.KEY_DRAWBACK_AMMO);
        } else {
            CrossbowItem.setCharged(stack, false);
        }
    }

    /** Initializes drawtime in persistent data if the mob is holding a TC crossbow. */
    private void initDrawtimeIfTcCrossbow(InteractionHand hand) {
        ItemStack weapon = this.mob.getItemInHand(hand);
        if (!weapon.isEmpty() && weapon.getItem() instanceof ModifiableCrossbowItem) {
            ToolStack toolStack = ToolStack.from(weapon);
            GeneralInteractionModifierHook.startDrawtime(toolStack, this.mob, 1.0f);
        }
    }

    enum CrossbowState {
        UNCHARGED, CHARGING, CHARGED, READY_TO_ATTACK
    }
}
