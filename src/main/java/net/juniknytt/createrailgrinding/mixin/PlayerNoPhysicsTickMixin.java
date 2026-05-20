package net.juniknytt.createrailgrinding.mixin;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.client.RailGrindClientMotion;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerNoPhysicsTickMixin {

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;updateIsUnderwater()Z"
        )
    )
    private void createrailgrinding$reassertNoPhysicsForGrind(CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!(BalancingPoseTracker.isBalancing(self) || RailGrindHandler.isGrinding(self))) {
            return;
        }
        double slope;
        if (self.level().isClientSide()) {

            if (RailGrindClientMotion.isObstacleAheadOnSlope()) return;

            Vec3 dm = self.getDeltaMovement();
            double mag = dm.length();

            slope = mag > 1e-3 ? dm.y / mag : 0.0;
        } else {
            slope = RailGrindHandler.getExperiencedSlope(self);
        }
        if (Math.abs(slope) > RailGrindHandler.NO_PHYSICS_SLOPE_THRESHOLD) {
            self.noPhysics = true;
        }
    }
}
