package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.client.RailGrindClientMotion;
import net.juniknytt.createrailgrinding.client.RailGrindDebugSyncCache;
import net.juniknytt.createrailgrinding.client.RailGrindLeanTracker;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.juniknytt.createrailgrinding.sound.GrindSoundController;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ClientPayloadHandler {
    private ClientPayloadHandler() {}

    public static void handleDebugSync(RailGrindDebugSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RailGrindDebugSyncCache.store(payload));
    }

    /**
     * Lean broadcast: drives the per-player smoothed lean visual on remote observers. The
     * local player's lean feed comes from direct key polling in {@link
     * net.juniknytt.createrailgrinding.client.ClientInputHandler#onSteerTick} instead of the
     * server round-trip, so we explicitly skip ourselves here to avoid the latency-stamped
     * payload value fighting the live key state.
     */
    public static void handleLeanSync(RailGrindLeanSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && payload.playerId().equals(mc.player.getUUID())) return;
            RailGrindLeanTracker.setRawSign(payload.playerId(), payload.steerSign());
        });
    }

    public static void handleTarget(RailGrindTargetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            RailGrindClientMotion.setTargetAndVelocity(
                    payload.target(), payload.velocity(), payload.serverAuthoritative());
            // Mirror the server-side experienced slope onto the common-side cache. The
            // active PlayerNoPhysicsTickMixin derives its client-side slope value from
            // the local deltaMovement (latency-free) and does NOT read this field —
            // sourcing locally avoids the {@code L+1}-ticks staleness the synced value
            // accumulates under multiplayer round-trip latency, which was the cause of
            // the "phases through walls on flat track" multiplayer regression. The
            // mirror stays for debug HUDs and future server-authoritative consumers.
            RailGrindHandler.clientLocalSlope = payload.slope();
        });
    }

    public static void handleSync(RailGrindSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            BalancingPoseTracker.setBalancing(payload.playerId(), payload.grinding());

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            Player player = mc.level.getPlayerByUUID(payload.playerId());
            if (player == null) return;
            player.noPhysics = payload.grinding();

            // Drop the EMA target on dismount so a stale value can't chase the player
            // back toward the rail after stopWithLaunch sets the launch velocity, or after
            // any other server-side stop path fires. Only meaningful for the local player
            // (target is a singleton not keyed by UUID), so gate on equality before clearing.
            // Reset clientLocalSlope alongside the target — both are paired client mirrors
            // of the local grinding session; leaving the slope at its last value would
            // leak into a future grind's first tick before the target payload refreshes it.
            if (!payload.grinding() && mc.player != null
                    && payload.playerId().equals(mc.player.getUUID())) {
                RailGrindClientMotion.clearTarget();
                RailGrindHandler.clientLocalSlope = 0.0;
            }

            // Silent grind-start sync = cross-dim re-attach (the only path that sets silent=true).
            // Arm the trigger-based grace-release ack so the client pings the server the
            // instant chunks-at-player-position have loaded; the server collapses its grace
            // counter on receipt and motion resumes promptly. Gated on the local player's UUID
            // because the ack is per-client (each client's chunk-readiness is its own).
            if (payload.grinding() && payload.silent()
                    && mc.player != null && payload.playerId().equals(mc.player.getUUID())) {
                net.juniknytt.createrailgrinding.client.ClientInputHandler.requestCrossDimGraceAck();
            }

            // One-shot grind-start sound. Plays for any grinding player visible to this
            // client; playCollide routes through level.playLocalSound so the sound engine
            // attenuates remote starts by distance and the local player's start stays
            // full-volume because their listener position equals the sound origin.
            //
            // Silent grind-start syncs (portal-driven re-grinds) skip both the collide sound
            // and the dismount-prompt overlay so the cross-dim handoff feels like a
            // continuation of the same grind, not a fresh mount.
            if (payload.grinding() && !payload.silent()) {
                GrindSoundController.playCollide(player);

                // Show the dismount prompt for the local player only — same path vanilla
                // uses for "Press [Sneak Key] to Dismount" on mount (ClientPacketListener:
                // gui.setOverlayMessage with mount.onboard). Reuses the vanilla overlay
                // message slot, so it gets the identical 60-tick lifespan, fade over the
                // last 20 ticks, and bottom-center anchor above the hotbar. We swap in the
                // jump-key translated label because dismount here is bound to jump, not sneak.
                if (mc.player != null && payload.playerId().equals(mc.player.getUUID())) {
                    Component prompt = Component.translatable(
                        "createrailgrinding.dismount_prompt",
                        mc.options.keyJump.getTranslatedKeyMessage());
                    mc.gui.setOverlayMessage(prompt, false);
                }
            }
        });
    }
}
