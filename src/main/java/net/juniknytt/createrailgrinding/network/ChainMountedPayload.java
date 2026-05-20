package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ChainMountedPayload() implements CustomPacketPayload {
    public static final ChainMountedPayload INSTANCE = new ChainMountedPayload();

    public static final Type<ChainMountedPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "chain_mounted"));

    public static final StreamCodec<ByteBuf, ChainMountedPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
