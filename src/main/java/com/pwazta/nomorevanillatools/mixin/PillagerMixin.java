package com.pwazta.nomorevanillatools.mixin;

import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Pillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.pwazta.nomorevanillatools.util.TcRangedItems;

/**
 * Client-only mixin for {@link Pillager#getArmPose()}.
 *
 * <p>Vanilla checks {@code instanceof CrossbowItem} which fails for TC's {@link ModifiableCrossbowItem}
 * (extends {@code ModifiableLauncherItem}, not {@code CrossbowItem}). Without this mixin, pillagers
 * holding TC crossbows show ATTACKING pose (sword arm) instead of CROSSBOW_HOLD/CROSSBOW_CHARGE.
 */
@Mixin(Pillager.class)
public abstract class PillagerMixin {

    @Inject(method = "getArmPose", at = @At("HEAD"), cancellable = true)
    private void nmvt_getArmPose(CallbackInfoReturnable<AbstractIllager.IllagerArmPose> cir) {
        Pillager self = (Pillager) (Object) this;
        if (self.isHolding(is -> TcRangedItems.isCrossbow(is.getItem()))) {
            if (self.isChargingCrossbow()) {
                cir.setReturnValue(AbstractIllager.IllagerArmPose.CROSSBOW_CHARGE);
            } else {
                cir.setReturnValue(AbstractIllager.IllagerArmPose.CROSSBOW_HOLD);
            }
        }
    }
}
