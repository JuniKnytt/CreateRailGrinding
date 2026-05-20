package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record BlockedByObstaclePayload() implements CustomPacketPayload {
    public static final BlockedByObstaclePayload INSTANCE = new BlockedByObstaclePayload();

    public static final Type<BlockedByObstaclePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "blocked_by_obstacle"));

    public static final StreamCodec<ByteBuf, BlockedByObstaclePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
