package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.client.RailGrindClientMotion;
import net.juniknytt.createrailgrinding.client.RailGrindDebugSyncCache;
import net.juniknytt.createrailgrinding.client.RailGrindLeanTracker;
import net.juniknytt.createrailgrinding.client.RailGrindParticleSpawner;
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

    public static void handleParticleBurst(GrindParticleBurstPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RailGrindParticleSpawner.spawn(payload));
    }

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

            if (!payload.grinding() && mc.player != null
                    && payload.playerId().equals(mc.player.getUUID())) {
                RailGrindClientMotion.clearTarget();
                RailGrindHandler.clientLocalSlope = 0.0;
            }

            if (payload.grinding() && payload.silent()
                    && mc.player != null && payload.playerId().equals(mc.player.getUUID())) {
                net.juniknytt.createrailgrinding.client.ClientInputHandler.requestCrossDimGraceAck();
            }

            if (payload.grinding() && !payload.silent()) {
                GrindSoundController.playCollide(player);

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
