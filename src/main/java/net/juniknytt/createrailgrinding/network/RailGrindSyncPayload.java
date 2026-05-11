package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → client grind-state sync. {@code silent} suppresses the one-shot grind-start
 * effects (collide sound + dismount-prompt overlay) on the receiving client — used for
 * portal-driven re-grinds where the player was already grinding before the dimension
 * change and the start effects would feel like a fresh mount instead of a seamless
 * continuation.
 */
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
