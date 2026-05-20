package net.juniknytt.createrailgrinding.network;

import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.enchantment.ModEnchantments;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

@EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD)
public class Networking {

    private static final double NEAREST_RAIL_MAX_DIST = 1.75;

    public record TeleportToRailPacket(Vec3 target) implements CustomPacketPayload {
        public static final Type<TeleportToRailPacket> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "teleport_to_rail"));

        public static final StreamCodec<FriendlyByteBuf, TeleportToRailPacket> STREAM_CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeDouble(packet.target.x);
                    buf.writeDouble(packet.target.y);
                    buf.writeDouble(packet.target.z);
                },
                buf -> new TeleportToRailPacket(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(RailGrind.MODID)
                .playToServer(
                        TeleportToRailPacket.TYPE,
                        TeleportToRailPacket.STREAM_CODEC,
                        Networking::handleTeleport)
                .playToServer(
                        StopGrindPayload.TYPE,
                        StopGrindPayload.STREAM_CODEC,
                        Networking::handleStopGrind)
                .playToServer(
                        SteerInputPayload.TYPE,
                        SteerInputPayload.STREAM_CODEC,
                        Networking::handleSteerInput)
                .playToServer(
                        GrindAccelInputPayload.TYPE,
                        GrindAccelInputPayload.STREAM_CODEC,
                        Networking::handleAccelInput)
                .playToServer(
                        StartGrindFromNearestPayload.TYPE,
                        StartGrindFromNearestPayload.STREAM_CODEC,
                        Networking::handleStartFromNearest)
                .playToServer(
                        ChainMountedPayload.TYPE,
                        ChainMountedPayload.STREAM_CODEC,
                        Networking::handleChainMounted)
                .playToServer(
                        CrossDimGraceReleasePayload.TYPE,
                        CrossDimGraceReleasePayload.STREAM_CODEC,
                        Networking::handleCrossDimGraceRelease)
                .playToServer(
                        BlockedByObstaclePayload.TYPE,
                        BlockedByObstaclePayload.STREAM_CODEC,
                        Networking::handleBlockedByObstacle);
    }

    private static void handleBlockedByObstacle(BlockedByObstaclePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (RailGrindHandler.isInReattachGrace(player)) return;
            if (RailGrindHandler.isGrinding(player)) {
                RailGrindHandler.stop(player, RailGrindHandler.StopReason.BLOCKED);
            }
        });
    }

    private static void handleCrossDimGraceRelease(CrossDimGraceReleasePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RailGrindHandler.releaseReattachGrace(context.player()));
    }

    private static void handleChainMounted(ChainMountedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (RailGrindHandler.isInReattachGrace(player)) return;
            if (RailGrindHandler.isGrinding(player)) {
                RailGrindHandler.stop(player, RailGrindHandler.StopReason.CHAIN_MOUNTED);
            }
        });
    }

    private static void handleStartFromNearest(StartGrindFromNearestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (RailGrindHandler.isGrinding(player)) return;
            if (RailGrindHandler.isPlayerOnRailGrindCooldown(player)) return;
            if (RailGrindHandler.isPlayerCrushedByTrain(player)) return;

            if (player.isPassenger()) return;
            if (player.isSpectator()) return;
            if (!ModEnchantments.isWearingRailGrindBoots(player)) return;

            RailGrindHandler.RailHit hit = RailGrindHandler.findNearestRailLocation(
                    player.level(), player.position(), NEAREST_RAIL_MAX_DIST);
            if (hit == null) return;

            Vec3 prePos = player.position();
            double entryVelocity = player.getDeltaMovement().length();
            RailGrindHandler.railgrinding(player, hit.loc(), prePos, entryVelocity, hit.subLevel());
        });
    }

    private static void handleStopGrind(StopGrindPayload payload, IPayloadContext context) {

        context.enqueueWork(() -> RailGrindHandler.stopWithLaunch(context.player(), payload.chargeTicks(), RailGrindHandler.StopReason.USER_DISMOUNT));
    }

    private static void handleSteerInput(SteerInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RailGrindHandler.setSteerInput(context.player(), payload.sign()));
    }

    private static void handleAccelInput(GrindAccelInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RailGrindHandler.setAccelInputMode(context.player(), payload.mode()));
    }

    private static void handleTeleport(TeleportToRailPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            Vec3 interactionEnd = payload.target;

            if (player.position().distanceToSqr(interactionEnd) > 100) return;

            if (RailGrindHandler.isGrinding(player)) {

                TrackGraphLocation candidate = RailGrindHandler.findNearestRailInLevel(
                        player.level(), interactionEnd, NEAREST_RAIL_MAX_DIST);
                if (candidate != null
                        && RailGrindHandler.isOnDifferentTrackGraph(player, candidate.graph)) {
                    RailGrindHandler.swapGrindToNewLocation(player, candidate, interactionEnd, null);
                    return;
                }
                RailGrindHandler.stop(player, RailGrindHandler.StopReason.TELEPORT_REQUEST);
                return;
            }
            if (RailGrindHandler.isPlayerOnRailGrindCooldown(player)) return;
            if (RailGrindHandler.isPlayerCrushedByTrain(player)) return;
            if (player.isPassenger()) return;
            if (player.isSpectator()) return;
            if (player.getMainHandItem().getItem() != Items.AIR) return;
            if (!ModEnchantments.isWearingRailGrindBoots(player)) return;

            TrackGraphLocation loc = RailGrindHandler.findNearestRailInLevel(
                    player.level(), interactionEnd, NEAREST_RAIL_MAX_DIST);
            if (loc == null) return;

            Vec3 prePos = player.position();
            double entryVelocity = player.getDeltaMovement().length();

            player.teleportTo(interactionEnd.x, interactionEnd.y, interactionEnd.z);

            RailGrindHandler.railgrinding(player, loc, prePos, entryVelocity);
        });
    }
}
