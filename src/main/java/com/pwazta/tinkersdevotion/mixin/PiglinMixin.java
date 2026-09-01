package com.pwazta.tinkersdevotion.mixin;

import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinArmPose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.pwazta.tinkersdevotion.util.TcRangedItems;

/**
 * Client-only mixin for {@link Piglin#getArmPose()}: vanilla's last branch tests
 * {@code instanceof CrossbowItem}, so a TC crossbow reports {@code DEFAULT}.
 * {@link HumanoidModelMixin} already poses the arms, but the correct value also lets
 * {@code PiglinModel} re-apply the hold pose, cancelling the idle arm sway.
 *
 * <p>Gated on {@code DEFAULT} at RETURN so the other four poses keep their vanilla precedence.
 */
@Mixin(Piglin.class)
public abstract class PiglinMixin {

    @Inject(method = "getArmPose", at = @At("RETURN"), cancellable = true)
    private void nmvt_getArmPose(CallbackInfoReturnable<PiglinArmPose> cir) {
        if (cir.getReturnValue() != PiglinArmPose.DEFAULT) return;

        Piglin self = (Piglin) (Object) this;
        if (self.isAggressive() && self.isHolding(is -> TcRangedItems.isCrossbow(is.getItem()))) {
            cir.setReturnValue(PiglinArmPose.CROSSBOW_HOLD);
        }
    }
}
