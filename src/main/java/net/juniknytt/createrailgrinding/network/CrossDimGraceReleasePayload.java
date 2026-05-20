package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record CrossDimGraceReleasePayload() implements CustomPacketPayload {
    public static final CrossDimGraceReleasePayload INSTANCE = new CrossDimGraceReleasePayload();

    public static final Type<CrossDimGraceReleasePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "cross_dim_grace_release"));

    public static final StreamCodec<ByteBuf, CrossDimGraceReleasePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
