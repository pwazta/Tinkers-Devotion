package com.pwazta.nomorevanillatools.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.SkeletonModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.pwazta.nomorevanillatools.util.TcRangedItems;

/**
 * Client-only mixin for {@link SkeletonModel}.
 *
 * <p>{@link SkeletonModel#setupAnim} has a zombie-arms fallback that activates when
 * {@code !itemstack.is(Items.BOW)}, overwriting the correct arm rotations set by
 * {@link HumanoidModelMixin}. This redirect makes the check return true for TC ranged weapons,
 * preventing the zombie-arms code from running.
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
     * Redirect the {@code itemstack.is(Items.BOW)} check in setupAnim's zombie-arms condition.
     * Returns true for TC ranged weapons so the condition {@code !is(Items.BOW)} evaluates to false,
     * preventing the zombie-arms fallback from overwriting correct arm rotations.
     */
    @Redirect(
        method = "setupAnim(Lnet/minecraft/world/entity/Mob;FFFFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/world/item/Item;)Z")
    )
    private boolean nmvt_isBowOrTcRanged(ItemStack stack, Item item) {
        return stack.is(item) || TcRangedItems.isRangedWeapon(stack.getItem());
    }
}
