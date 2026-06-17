package net.juniknytt.createrailgrinding.network;

import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import net.juniknytt.createrailgrinding.enchantment.ModEnchantments;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class Networking {

    private static final double NEAREST_RAIL_MAX_DIST = 1.75;

    public record TeleportToRailPacket(Vec3 target) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeDouble(target.x);
            buf.writeDouble(target.y);
            buf.writeDouble(target.z);
        }

        public static TeleportToRailPacket decode(FriendlyByteBuf buf) {
            return new TeleportToRailPacket(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
        }
    }

    static void handleBlockedByObstacle(BlockedByObstaclePayload payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (RailGrindHandler.isInReattachGrace(player)) return;
            if (RailGrindHandler.isGrinding(player)) {
                RailGrindHandler.stop(player, RailGrindHandler.StopReason.BLOCKED);
            }
        });
        context.setPacketHandled(true);
    }

    static void handleCrossDimGraceRelease(CrossDimGraceReleasePayload payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) RailGrindHandler.releaseReattachGrace(player);
        });
        context.setPacketHandled(true);
    }

    static void handleChainMounted(ChainMountedPayload payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            if (RailGrindHandler.isInReattachGrace(player)) return;
            if (RailGrindHandler.isGrinding(player)) {
                RailGrindHandler.stop(player, RailGrindHandler.StopReason.CHAIN_MOUNTED);
            }
        });
        context.setPacketHandled(true);
    }

    static void handleStartFromNearest(StartGrindFromNearestPayload payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
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
            RailGrindHandler.railgrinding(player, hit.loc(), prePos, entryVelocity);
        });
        context.setPacketHandled(true);
    }

    static void handleStopGrind(StopGrindPayload payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                RailGrindHandler.stopWithLaunch(player, payload.chargeTicks(), RailGrindHandler.StopReason.USER_DISMOUNT);
            }
        });
        context.setPacketHandled(true);
    }

    static void handleSteerInput(SteerInputPayload payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) RailGrindHandler.setSteerInput(player, payload.sign());
        });
        context.setPacketHandled(true);
    }

    static void handleAccelInput(GrindAccelInputPayload payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) RailGrindHandler.setAccelInputMode(player, payload.mode());
        });
        context.setPacketHandled(true);
    }

    static void handleTeleport(TeleportToRailPacket payload, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            Vec3 interactionEnd = payload.target();

            if (player.position().distanceToSqr(interactionEnd) > 100) return;

            if (RailGrindHandler.isGrinding(player)) {

                TrackGraphLocation candidate = RailGrindHandler.findNearestRailInLevel(
                        player.level(), interactionEnd, NEAREST_RAIL_MAX_DIST);
                if (candidate != null
                        && RailGrindHandler.isOnDifferentTrackGraph(player, candidate.graph)) {
                    RailGrindHandler.swapGrindToNewLocation(player, candidate, interactionEnd);
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
        context.setPacketHandled(true);
    }
}
