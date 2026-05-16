package net.juniknytt.createrailgrinding.mixin;

import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops incoming {@code ServerboundMovePlayerPacket} from a client whose grind is mid-cross-dim
 * handoff — either parked in the pending-regrind queue (Fix B's anchored window before the
 * destination grind starts) or inside the {@code reattachGraceTicks} server-authoritative
 * window (Fix C). In both cases the server has authoritative position via the per-tick
 * anchor in {@link RailGrindHandler}; if we let vanilla process the C2S move it would clobber
 * the anchor with whatever the still-loading or just-respawned LocalPlayer happens to report
 * (typically a vanilla-physics fall, since the new client receives the noGravity SynchedEntityData
 * entry only after the respawn packet's already constructed a default-gravity LocalPlayer).
 *
 * <p>The mixin is HEAD-injected on {@code handleMovePlayer} and {@code ci.cancel()}'s when
 * {@link RailGrindHandler#shouldRejectMoveDuringCrossDim(net.minecraft.world.entity.player.Player)}
 * returns true. Other packet types (chat, interact, sneak) are unaffected — those still flow
 * normally; only position updates are intercepted.
 *
 * <p>Why not just trust the existing anchor and let MovePlayer through? Vanilla
 * {@code handleMovePlayer} updates {@code player.position} via {@code absMoveTo} on the main
 * thread at the START of each server tick, BEFORE {@code PlayerTickEvent.Post} fires our
 * {@code tick()}/{@code tickPendingRegrind()}. So between the start-of-tick MovePlayer apply
 * and the end-of-tick anchor re-apply, server-side reads of {@code player.position} (used in
 * particle emission, slope sampling for {@code experiencedSlope}, entity-tracker broadcasts
 * to remote observers) all see the stale client-reported value. The host watching another
 * player mid-cross-dim sees them as stationary at the client's fall position rather than
 * moving along the rail because the entity tracker broadcasts the wrong position. Dropping
 * the packet pre-empts that.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMovePacketMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void createrailgrinding$suppressDuringCrossDim(
            ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (player != null && RailGrindHandler.shouldRejectMoveDuringCrossDim(player)) {
            ci.cancel();
        }
    }
}
