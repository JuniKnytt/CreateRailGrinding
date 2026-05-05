package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.sound.GrindSoundController;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ClientPayloadHandler {
    private ClientPayloadHandler() {}

    public static void handleSync(RailGrindSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            BalancingPoseTracker.setBalancing(payload.playerId(), payload.grinding());

            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            Player player = mc.level.getPlayerByUUID(payload.playerId());
            if (player == null) return;
            player.noPhysics = payload.grinding();

            // One-shot grind-start sound — fires only when *we* started grinding,
            // not when a remote player did.
            if (payload.grinding() && player == mc.player) {
                GrindSoundController.playCollide();
            }
        });
    }
}
