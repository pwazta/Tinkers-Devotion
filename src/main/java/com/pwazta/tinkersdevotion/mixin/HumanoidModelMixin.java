package com.pwazta.tinkersdevotion.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.pwazta.tinkersdevotion.util.TcRangedItems;

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
     * <p>Clears any stale pose we previously set on the shared model instance, then applies ours.
     *
     * <p>Bows use {@code BOW_AND_ARROW}, crossbows {@code CROSSBOW_CHARGE} while using and
     * {@code CROSSBOW_HOLD} otherwise. Launchers that draw with {@link UseAnim#SPEAR}, such as
     * Tinkers' Thinking's atlatl, get {@code THROW_SPEAR} for the draw only: their drawing model
     * carries a 180 degree flip that the raised arm is drawn against, and the plain model they
     * revert to afterwards is not, so the pose has to track the model swap exactly.
     */
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void nmvt_fixTcRangedArmPoses(T entity, float limbSwing, float limbSwingAmount,
                                           float ageInTicks, float netHeadYaw, float headPitch,
                                           CallbackInfo ci) {
        if (!(entity instanceof Mob mob)) return;

        ItemStack mainHand = mob.getItemInHand(InteractionHand.MAIN_HAND);
        // DrownedModel owns THROW_SPEAR for tridents, setting it in prepareMobModel and re-reading
        // it after super.setupAnim, so leave those poses alone entirely.
        if (mainHand.is(Items.TRIDENT)) return;

        // Model instances are shared per entity type and HumanoidMobRenderer never resets them
        // (unlike PlayerRenderer), so clear our own stale poses.
        if (nmvt_isOurs(this.rightArmPose)) this.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        if (nmvt_isOurs(this.leftArmPose)) this.leftArmPose = HumanoidModel.ArmPose.EMPTY;

        Item item = mainHand.getItem();
        HumanoidModel.ArmPose pose = null;

        if (TcRangedItems.isBow(item)) {
            // Same condition TinkerItemProperties.CHARGING uses to swap in the drawing model.
            boolean drawing = mob.getUseItem() == mainHand;
            if (drawing || mob.isAggressive()) {
                if (mainHand.getUseAnimation() == UseAnim.SPEAR) {
                    // The drawing model flips the item 180 degrees, so raise the arm only while it applies.
                    if (drawing) pose = HumanoidModel.ArmPose.THROW_SPEAR;
                } else {
                    pose = HumanoidModel.ArmPose.BOW_AND_ARROW;
                }
            }
        } else if (TcRangedItems.isCrossbow(item)) {
            pose = mob.isUsingItem() ? HumanoidModel.ArmPose.CROSSBOW_CHARGE
                                     : HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }

        if (pose != null) {
            if (mob.getMainArm() == HumanoidArm.RIGHT) this.rightArmPose = pose;
            else this.leftArmPose = pose;
        }
    }

    @Unique
    private static boolean nmvt_isOurs(HumanoidModel.ArmPose pose) {
        return pose == HumanoidModel.ArmPose.BOW_AND_ARROW
            || pose == HumanoidModel.ArmPose.CROSSBOW_HOLD
            || pose == HumanoidModel.ArmPose.CROSSBOW_CHARGE
            || pose == HumanoidModel.ArmPose.THROW_SPEAR;
    }
}
