package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server "ready" ack for the cross-dim re-grind grace window. Sent by the local
 * client after receiving a silent grind-start sync, once mc.player exists, mc.level is
 * attached, and the chunk at the player's block position is loaded. The server collapses
 * its {@code reattachGraceTicks} counter to a small post-ack tail on receipt, ending the
 * motion-pause as soon as the client can actually act on a moving target. Without this
 * trigger the server has to fall back to a fixed-duration grace tuned for worst-case
 * latency, which feels awkwardly long on fast clients and still times out before slow
 * clients (slow nether chunk loads) are ready.
 *
 * <p>No payload fields — the player identity comes from the packet context, and the
 * arrival of the packet IS the signal. Multiple arrivals are harmless: the server's
 * {@code releaseReattachGrace} is idempotent (it only reduces the counter, never raises
 * it, so a stale or repeated ack can't extend the grace).
 */
public record CrossDimGraceReleasePayload() implements CustomPacketPayload {
    public static final CrossDimGraceReleasePayload INSTANCE = new CrossDimGraceReleasePayload();

    public static final Type<CrossDimGraceReleasePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "cross_dim_grace_release"));

    public static final StreamCodec<ByteBuf, CrossDimGraceReleasePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
