package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record RailGrindAccelSyncPayload(UUID playerId, boolean accelerating) implements CustomPacketPayload {
    public static final Type<RailGrindAccelSyncPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "rail_grind_accel_sync"));

    public static final StreamCodec<ByteBuf, RailGrindAccelSyncPayload> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, RailGrindAccelSyncPayload::playerId,
        ByteBufCodecs.BOOL, RailGrindAccelSyncPayload::accelerating,
        RailGrindAccelSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
