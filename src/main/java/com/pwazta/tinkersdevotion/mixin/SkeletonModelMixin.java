package com.pwazta.tinkersdevotion.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.SkeletonModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.pwazta.tinkersdevotion.util.TcRangedItems;

/**
 * Client-only mixin for {@link SkeletonModel}.
 *
 * <p>{@link SkeletonModel#setupAnim} calls {@code super.setupAnim()} (where
 * {@link HumanoidModelMixin} sets correct arm poses and {@link HumanoidModel} applies the
 * corresponding rotations), then has a zombie-arms fallback that activates when
 * {@code !itemstack.is(Items.BOW)}, overwriting the correct arm rotations. Since TC ranged
 * weapons aren't {@code Items.BOW}, the fallback incorrectly triggers.
 *
 * <p>This mixin injects right after the {@code super.setupAnim()} call and cancels the rest
 * of the method when the mob holds a TC ranged weapon, preventing the zombie-arms fallback
 * from overwriting the correct bow/crossbow animations.
 *
 * <p>The zombie-arms fallback is the <em>only</em> code after the super call, so cancelling
 * loses no other functionality.
 *
 * <p>Arm pose <em>setting</em> is handled generically by {@link HumanoidModelMixin} &mdash;
 * this mixin only prevents the skeleton-specific overwrite.
 */
@Mixin(SkeletonModel.class)
public class SkeletonModelMixin<T extends Mob & RangedAttackMob> extends HumanoidModel<T> {

    protected SkeletonModelMixin(ModelPart root) {
        super(root);
    }

    /**
     * Cancel after {@code super.setupAnim()} when holding a TC ranged weapon.
     * The only code after the super call is the zombie-arms fallback, so no logic is lost.
     */
    @Inject(
        method = "setupAnim(Lnet/minecraft/world/entity/Mob;FFFFF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/model/HumanoidModel;setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void nmvt_skipZombieArmsFallback(T entity, float limbSwing, float limbSwingAmount,
                                              float ageInTicks, float netHeadYaw, float headPitch,
                                              CallbackInfo ci) {
        if (TcRangedItems.isRangedWeapon(entity.getItemInHand(InteractionHand.MAIN_HAND).getItem())) {
            ci.cancel();
        }
    }
}
