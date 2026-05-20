package net.juniknytt.createrailgrinding.mixin;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class LivingEntityFallFlyingMixin {

    @Inject(method = "tryToStartFallFlying", at = @At("HEAD"), cancellable = true)
    private void createrailgrinding$blockFallFlyingDuringGrind(CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (RailGrindHandler.isGrinding(self) || BalancingPoseTracker.isBalancing(self)) {
            cir.setReturnValue(false);
        }
    }
}
