package com.pwazta.nomorevanillatools.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.SkeletonModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableBowItem;
import slimeknights.tconstruct.library.tools.item.ranged.ModifiableCrossbowItem;

/**
 * Client-only mixin for {@link SkeletonModel}.
 *
 * <p>Fixes two issues with TC ranged weapons on skeletons:
 * <ol>
 *   <li><b>prepareMobModel</b>: Vanilla checks {@code itemstack.is(Items.BOW)} which fails for TC bows.
 *       Our injection at RETURN sets the correct arm pose after vanilla resets to EMPTY.</li>
 *   <li><b>setupAnim</b>: Vanilla's zombie-arms fallback activates when {@code !itemstack.is(Items.BOW)},
 *       overwriting correct arm rotations. Our redirect makes the check return true for TC ranged weapons,
 *       preventing the zombie-arms code from running.</li>
 * </ol>
 */
@Mixin(SkeletonModel.class)
public class SkeletonModelMixin<T extends Mob & RangedAttackMob> extends HumanoidModel<T> {

    protected SkeletonModelMixin(ModelPart root) {
        super(root);
    }

    /**
     * After vanilla resets arm poses to EMPTY and only sets BOW_AND_ARROW for Items.BOW,
     * set the correct arm pose for TC ranged weapons.
     */
    @Inject(method = "prepareMobModel(Lnet/minecraft/world/entity/Mob;FFF)V", at = @At("RETURN"))
    private void nmvt_prepareMobModel(T entity, float limbSwing, float limbSwingAmount,
                                       float partialTick, CallbackInfo ci) {
        if (!entity.isAggressive()) return;

        Item item = entity.getItemInHand(InteractionHand.MAIN_HAND).getItem();
        ArmPose pose = null;

        if (item instanceof ModifiableBowItem) {
            pose = ArmPose.BOW_AND_ARROW;
        } else if (item instanceof ModifiableCrossbowItem) {
            pose = entity.isUsingItem() ? ArmPose.CROSSBOW_CHARGE : ArmPose.CROSSBOW_HOLD;
        }

        if (pose != null) {
            if (entity.getMainArm() == HumanoidArm.RIGHT) this.rightArmPose = pose;
            else this.leftArmPose = pose;
        }
    }

    /**
     * Redirect the {@code itemstack.is(Items.BOW)} check in setupAnim's zombie-arms condition.
     * Returns true for TC ranged weapons so the condition {@code !is(Items.BOW)} evaluates to false,
     * preventing the zombie-arms fallback from overwriting correct arm rotations.
     */
    @Redirect(
        method = "setupAnim(Lnet/minecraft/world/entity/Mob;FFFFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/world/item/Item;)Z")
    )
    private boolean nmvt_isBowOrTcRanged(ItemStack stack, Item item) {
        return stack.is(item)
            || stack.getItem() instanceof ModifiableBowItem
            || stack.getItem() instanceof ModifiableCrossbowItem;
    }
}
