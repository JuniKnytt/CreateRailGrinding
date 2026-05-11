package net.juniknytt.createrailgrinding.mixin;

import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Make dimensional portal traversal instant while rail-grinding — same effective behavior as
 * creative mode's getPortalTransitionTime = 1, but achieved by short-circuiting the
 * {@code portalTime++ >= threshold} check so it fires on the first overlap tick regardless of
 * portal type. Targets {@link PortalProcessor}, the single point in 1.21.1 that gates all
 * vanilla- and {@link net.minecraft.world.level.block.Portal Portal}-interface mod portals,
 * so this one inject covers nether, end, and any custom modded portal block that hooks the
 * interface.
 *
 * <p>The canChangeDimensions guard mirrors the original check (we still respect dimension-
 * change permissions like mounts and disabled levels). Only grinding {@link Player}s get the
 * instant path; everything else falls through to the unmodified counter, so the side effect
 * doesn't leak to mobs or non-grinding players sharing the dimension.
 */
@Mixin(PortalProcessor.class)
public abstract class PortalProcessorInstantMixin {

    @Inject(method = "processPortalTeleportation", at = @At("HEAD"), cancellable = true)
    private void createrailgrinding$instantWhileGrinding(
            ServerLevel level, Entity entity, boolean canChangeDimensions,
            CallbackInfoReturnable<Boolean> cir) {
        if (!canChangeDimensions) return;
        if (!(entity instanceof Player player)) return;
        if (!RailGrindHandler.isGrinding(player)) return;
        // Anti-ping-pong: while the post-transit cooldown is active, fall through to vanilla's
        // normal portalTime counter. Lets the grind motion carry the player past the exit
        // portal's AABB without rapid-firing instant transits back the way they came.
        if (RailGrindHandler.isOnPostPortalTransitCooldown(player)) return;
        cir.setReturnValue(Boolean.TRUE);
    }
}
