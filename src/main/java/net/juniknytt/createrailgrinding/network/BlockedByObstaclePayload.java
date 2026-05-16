package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server "the local detector saw an obstacle" signal. Sent by
 * {@link net.juniknytt.createrailgrinding.client.RailGrindClientMotion} after a 3-tick
 * confirmation that the player can't follow the rail past current geometry — either
 * because vanilla collision is clipping the requested per-tick advance (flat regime) or
 * because a forward bounding-box probe found solid block geometry in the rail's
 * direction (slope regime, where {@code noPhysics = true} so the Δ-ratio check would be
 * blind to walls).
 *
 * <p>Detection lives on the client because the inputs ({@code playerPos}, requested
 * {@code deltaMovement}) are local to the client tick — they aren't contaminated by
 * server-side {@code setPos} anchoring, {@code MovePlayer} rejection, or client-EMA
 * convergence the way the server-side {@code STUCK} / {@code MAX_DRIFT} predicates are.
 * Mirrors Create's chain conveyor scheme ({@code ChainConveyorRidingHandler.clientTick}
 * runs the drift check on the client and sends a stop packet on dismount).
 *
 * <p>No payload fields — player identity comes from packet context, and arrival IS the
 * signal. Server-side STUCK / MAX_DRIFT stay as a very-loose anti-cheat tripwire for the
 * case where the client never sends this packet (modded / disconnected / packet dropped).
 */
public record BlockedByObstaclePayload() implements CustomPacketPayload {
    public static final BlockedByObstaclePayload INSTANCE = new BlockedByObstaclePayload();

    public static final Type<BlockedByObstaclePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "blocked_by_obstacle"));

    public static final StreamCodec<ByteBuf, BlockedByObstaclePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
