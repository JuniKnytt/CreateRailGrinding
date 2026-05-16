package net.juniknytt.createrailgrinding.mixin;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Blocks the elytra "jump to fly" deploy while a player is rail-grinding.
 *
 * <p>Without this, {@code LocalPlayer.aiStep} sees jump-held + {@code !onGround} +
 * {@code dy < 0} + chest-elytra and sends a {@code START_FALL_FLYING} C2S packet. The
 * server's {@code ServerGamePacketListener.handlePlayerCommand} dispatches into
 * {@link Player#tryToStartFallFlying()}, which sets shared flag 7 and flips the
 * player into {@code Pose.FALL_FLYING} — the camera drops to the gliding eye height
 * (the "squat down" the user reports) before {@link RailGrindHandler#tick} strips the
 * flag again on the next tick. Reactive strip in tick() can't cover that one-tick
 * pose-transition window.
 *
 * <p>This mixin cancels {@code tryToStartFallFlying} at HEAD when the player is grinding
 * (server) or balancing (client mirror), so the flag is never set in the first place.
 * The defensive {@code stopFallFlying()} re-asserts in {@code railgrinding()} init and
 * inside {@code tick()} stay as the safety net for paths that bypass this method
 * (other mods, entity-data desync).
 *
 * <p>Targets {@link Player} (not {@code LivingEntity}) — the elytra-specific
 * {@code tryToStartFallFlying} that does the chest-slot {@code canElytraFly} check lives
 * on Player in 1.21.1, not on the superclass.
 */
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
