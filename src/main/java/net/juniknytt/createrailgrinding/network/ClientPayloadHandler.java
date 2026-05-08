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

            // One-shot grind-start sound. Plays for any grinding player visible to this
            // client; playCollide routes through level.playLocalSound so the sound engine
            // attenuates remote starts by distance and the local player's start stays
            // full-volume because their listener position equals the sound origin.
            if (payload.grinding()) {
                GrindSoundController.playCollide(player);
            }
        });
    }
}
