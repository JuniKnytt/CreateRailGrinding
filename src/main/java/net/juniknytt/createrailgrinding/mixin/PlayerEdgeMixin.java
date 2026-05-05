package net.juniknytt.createrailgrinding.mixin;

import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's sneak-edge-protection (Player#maybeBackOffFromEdge) clamps motion when the player
 * is shift-holding near a block edge. While grinding we use crouch as a boost key and the
 * player passes through blocks via noPhysics, so the clamp is both inappropriate and the
 * source of the "sneak halts grind" bug.
 *
 * Bypass whenever noPhysics OR noGravity is set: both are turned on by RailGrindHandler
 * (server) / ClientPayloadHandler (client) and re-asserted every tick, so the OR catches
 * the case where one flag drifts off briefly between the sync packet and the next tick.
 */
@Mixin(Player.class)
public abstract class PlayerEdgeMixin {

    @Inject(
        method = "maybeBackOffFromEdge",
        at = @At("HEAD"),
        cancellable = true
    )
    private void createrailgrinding$bypassWhileGrinding(
            Vec3 vec, MoverType type, CallbackInfoReturnable<Vec3> cir) {
        Player self = (Player) (Object) this;
        if (self.noPhysics || self.isNoGravity()) {
            cir.setReturnValue(vec);
        }
    }
}
