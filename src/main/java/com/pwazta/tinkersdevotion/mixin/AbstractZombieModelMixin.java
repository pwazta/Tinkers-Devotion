package com.pwazta.tinkersdevotion.mixin;

import net.minecraft.client.model.AbstractZombieModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Monster;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.pwazta.tinkersdevotion.util.TcRangedItems;

/**
 * Client-only mixin for {@link AbstractZombieModel}.
 *
 * <p>{@link AbstractZombieModel#setupAnim} unconditionally calls
 * {@code AnimationUtils.animateZombieArms()} after {@code super.setupAnim()}, overwriting the
 * correct arm rotations set by {@link HumanoidModelMixin}. This mixin cancels the method just
 * before that call when the mob holds a TC ranged weapon, preserving the bow/crossbow animations.
 *
 * <p>Covers all zombie-type mobs: Zombie, Husk, Drowned, Zombie Villager, Giant, etc.
 */
@Mixin(AbstractZombieModel.class)
public abstract class AbstractZombieModelMixin<T extends Monster> extends HumanoidModel<T> {

    protected AbstractZombieModelMixin(ModelPart root) {
        super(root);
    }

    /**
     * Cancel before {@code animateZombieArms} when holding a TC ranged weapon.
     * Since the call is the last statement in the method, no other logic is lost.
     */
    @Inject(
        method = "setupAnim(Lnet/minecraft/world/entity/monster/Monster;FFFFF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/model/AnimationUtils;animateZombieArms(Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;ZFF)V"
        ),
        cancellable = true
    )
    private void nmvt_skipZombieArmsForTcRanged(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (TcRangedItems.isRangedWeapon(entity.getItemInHand(InteractionHand.MAIN_HAND).getItem())) {
            ci.cancel();
        }
    }
}
