package com.pwazta.nomorevanillatools.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.pwazta.nomorevanillatools.util.TcRangedItems;

/**
 * Client-only mixin for {@link HumanoidModel}.
 *
 * <p>Generic catch-all that sets the correct {@link HumanoidModel.ArmPose} for any humanoid mob
 * holding a TC ranged weapon. Vanilla only recognises {@code Items.BOW} and
 * {@code CrossbowItem} when determining arm poses, so TC's {@link ModifiableBowItem} and
 * {@link ModifiableCrossbowItem} are left with {@link HumanoidModel.ArmPose#EMPTY EMPTY}.
 *
 * <p>Injected at HEAD of {@code setupAnim} so arm pose fields are set before the
 * {@code poseRightArm}/{@code poseLeftArm} switch statements apply rotations. This covers
 * all models that extend {@code HumanoidModel} (zombies, piglins, skeletons, modded mobs, etc.).
 *
 * <p>Players ({@code Player}) are excluded via the {@code instanceof Mob} guard &mdash; player
 * arm poses are handled by their own renderer and TC's item use actions.
 *
 * <p><b>Note:</b> Subclass models that unconditionally overwrite arm rotations after
 * {@code super.setupAnim()} (e.g. {@code SkeletonModel}'s zombie-arms fallback) still need
 * their own redirect mixins to prevent the overwrite. See {@link SkeletonModelMixin}.
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin<T extends LivingEntity> {

    @Shadow public HumanoidModel.ArmPose rightArmPose;
    @Shadow public HumanoidModel.ArmPose leftArmPose;

    /**
     * Set the correct arm pose for TC ranged weapons before any rotation logic runs.
     *
     * <p>Model instances are shared across all entities of the same type, so arm pose fields
     * persist between render calls. Vanilla's {@code PlayerRenderer} resets arm poses every frame,
     * but {@code HumanoidMobRenderer} does not. We must reset to EMPTY for mobs first, then set
     * the correct pose if applicable — otherwise a stale CROSSBOW_HOLD from one mob bleeds into
     * the next mob rendered with the same model.
     *
     * <ul>
     *   <li>{@link ModifiableBowItem}: {@code BOW_AND_ARROW} (only when aggressive)</li>
     *   <li>{@link ModifiableCrossbowItem}: {@code CROSSBOW_CHARGE} when using, {@code CROSSBOW_HOLD} otherwise</li>
     * </ul>
     */
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void nmvt_fixTcRangedArmPoses(T entity, float limbSwing, float limbSwingAmount,
                                           float ageInTicks, float netHeadYaw, float headPitch,
                                           CallbackInfo ci) {
        if (!(entity instanceof Mob mob)) return;

        // Reset arm poses for mobs — HumanoidMobRenderer doesn't do this (unlike PlayerRenderer),
        // so values set for one entity persist into the next entity sharing the same model instance.
        this.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        this.leftArmPose = HumanoidModel.ArmPose.EMPTY;

        ItemStack mainHand = mob.getItemInHand(InteractionHand.MAIN_HAND);
        Item item = mainHand.getItem();
        HumanoidModel.ArmPose pose = null;

        if (TcRangedItems.isBow(item)) {
            if (mob.isAggressive()) pose = HumanoidModel.ArmPose.BOW_AND_ARROW;
        } else if (TcRangedItems.isCrossbow(item)) {
            pose = mob.isUsingItem() ? HumanoidModel.ArmPose.CROSSBOW_CHARGE
                                     : HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }

        if (pose != null) {
            if (mob.getMainArm() == HumanoidArm.RIGHT) this.rightArmPose = pose;
            else this.leftArmPose = pose;
        }
    }
}
