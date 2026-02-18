package com.pwazta.nomorevanillatools.mixin;

import com.pwazta.nomorevanillatools.util.TcRangedItems;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import slimeknights.tconstruct.library.tools.item.IModifiable;

/**
 * Common (client+server) mixin for {@link AbstractPiglin#isHoldingMeleeWeapon()}.
 *
 * <p>Vanilla checks {@code instanceof TieredItem} which fails for TC tools ({@link IModifiable}
 * extends {@code Item}, not {@code TieredItem}). Without this mixin, piglins holding TC melee
 * weapons get {@code PiglinArmPose.DEFAULT} instead of {@code ATTACKING_WITH_MELEE_WEAPON},
 * producing a wrong idle-looking stance.
 *
 * <p>TC ranged weapons are excluded via {@link TcRangedItems#isRangedWeapon} since they should
 * NOT be treated as melee.
 */
@Mixin(AbstractPiglin.class)
public abstract class AbstractPiglinMixin {

    @Inject(method = "isHoldingMeleeWeapon", at = @At("HEAD"), cancellable = true)
    private void nmvt_isHoldingMeleeWeapon(CallbackInfoReturnable<Boolean> cir) {
        AbstractPiglin self = (AbstractPiglin) (Object) this;
        var item = self.getMainHandItem().getItem();
        if (item instanceof IModifiable && !TcRangedItems.isRangedWeapon(item)) {
            cir.setReturnValue(true);
        }
    }
}
