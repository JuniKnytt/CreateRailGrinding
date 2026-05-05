package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server signal that the local player wants to grind from whatever rail is closest
 * to their feet — sent by the polling jump+sneak trigger in ClientInputHandler. The server
 * resolves the nearest spline point on any TrackGraph via
 * {@link net.juniknytt.createrailgrinding.rail.RailGrindHandler#findNearestRailLocation},
 * which sees plain straight tracks and bezier curves uniformly. No payload fields — the
 * player and their feet position come from the packet context.
 */
public record StartGrindFromNearestPayload() implements CustomPacketPayload {
    public static final StartGrindFromNearestPayload INSTANCE = new StartGrindFromNearestPayload();

    public static final Type<StartGrindFromNearestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "start_grind_from_nearest"));

    public static final StreamCodec<ByteBuf, StartGrindFromNearestPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
