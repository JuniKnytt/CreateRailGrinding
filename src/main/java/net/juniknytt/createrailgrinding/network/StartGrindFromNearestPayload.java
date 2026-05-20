package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record StartGrindFromNearestPayload() implements CustomPacketPayload {
    public static final StartGrindFromNearestPayload INSTANCE = new StartGrindFromNearestPayload();

    public static final Type<StartGrindFromNearestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "start_grind_from_nearest"));

    public static final StreamCodec<ByteBuf, StartGrindFromNearestPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
