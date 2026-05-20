package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record RailGrindLeanSyncPayload(UUID playerId, byte steerSign) implements CustomPacketPayload {
    public static final Type<RailGrindLeanSyncPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "rail_grind_lean_sync"));

    public static final StreamCodec<ByteBuf, RailGrindLeanSyncPayload> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, RailGrindLeanSyncPayload::playerId,
        ByteBufCodecs.BYTE, RailGrindLeanSyncPayload::steerSign,
        RailGrindLeanSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
