package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class ModNetworking {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(RailGrind.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private static int id = 0;

    private ModNetworking() {}

    private static int nextId() {
        return id++;
    }

    public static void register() {
        // Client -> Server
        CHANNEL.registerMessage(nextId(), Networking.TeleportToRailPacket.class,
                Networking.TeleportToRailPacket::encode, Networking.TeleportToRailPacket::decode,
                Networking::handleTeleport, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), StopGrindPayload.class,
                StopGrindPayload::encode, StopGrindPayload::decode,
                Networking::handleStopGrind, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), SteerInputPayload.class,
                SteerInputPayload::encode, SteerInputPayload::decode,
                Networking::handleSteerInput, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), GrindAccelInputPayload.class,
                GrindAccelInputPayload::encode, GrindAccelInputPayload::decode,
                Networking::handleAccelInput, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), StartGrindFromNearestPayload.class,
                StartGrindFromNearestPayload::encode, StartGrindFromNearestPayload::decode,
                Networking::handleStartFromNearest, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), ChainMountedPayload.class,
                ChainMountedPayload::encode, ChainMountedPayload::decode,
                Networking::handleChainMounted, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), CrossDimGraceReleasePayload.class,
                CrossDimGraceReleasePayload::encode, CrossDimGraceReleasePayload::decode,
                Networking::handleCrossDimGraceRelease, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), BlockedByObstaclePayload.class,
                BlockedByObstaclePayload::encode, BlockedByObstaclePayload::decode,
                Networking::handleBlockedByObstacle, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        // Server -> Client
        CHANNEL.registerMessage(nextId(), RailGrindSyncPayload.class,
                RailGrindSyncPayload::encode, RailGrindSyncPayload::decode,
                RailGrindSyncPayload::handleClient, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), RailGrindDebugSyncPayload.class,
                RailGrindDebugSyncPayload::encode, RailGrindDebugSyncPayload::decode,
                RailGrindDebugSyncPayload::handleClient, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), RailGrindTargetPayload.class,
                RailGrindTargetPayload::encode, RailGrindTargetPayload::decode,
                RailGrindTargetPayload::handleClient, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), RailGrindLeanSyncPayload.class,
                RailGrindLeanSyncPayload::encode, RailGrindLeanSyncPayload::decode,
                RailGrindLeanSyncPayload::handleClient, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), RailGrindAccelSyncPayload.class,
                RailGrindAccelSyncPayload::encode, RailGrindAccelSyncPayload::decode,
                RailGrindAccelSyncPayload::handleClient, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), GrindParticleBurstPayload.class,
                GrindParticleBurstPayload::encode, GrindParticleBurstPayload::decode,
                GrindParticleBurstPayload::handleClient, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void toServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }

    public static void toPlayer(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    public static void toTrackingAndSelf(Entity entity, Object msg) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), msg);
    }
}
