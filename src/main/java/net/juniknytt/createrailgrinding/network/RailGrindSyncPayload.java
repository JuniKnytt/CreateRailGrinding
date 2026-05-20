package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record RailGrindSyncPayload(UUID playerId, boolean grinding, boolean silent) implements CustomPacketPayload {
    public static final Type<RailGrindSyncPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "rail_grind_sync"));

    public static final StreamCodec<ByteBuf, RailGrindSyncPayload> STREAM_CODEC = StreamCodec.composite(
        UUIDUtil.STREAM_CODEC, RailGrindSyncPayload::playerId,
        ByteBufCodecs.BOOL, RailGrindSyncPayload::grinding,
        ByteBufCodecs.BOOL, RailGrindSyncPayload::silent,
        RailGrindSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
