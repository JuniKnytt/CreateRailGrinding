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
 * Server → client lean-state broadcast. Carries the latest steer input sign for a grinding
 * player (-1 left, 0 none, +1 right) so observers can drive the lean visual (model tilt +
 * optional camera roll) on remote players. Edge-triggered: emitted only when the value flips
 * server-side, plus a one-shot seed via {@code syncStateToObserver} on entity-tracking start.
 *
 * <p>Local-player lean is fed by direct key polling in
 * {@link net.juniknytt.createrailgrinding.client.ClientInputHandler#onSteerTick} (avoiding
 * a server round-trip on the player's own input) — the broadcast handler intentionally
 * ignores its own UUID to prevent the round-tripped value from fighting local input.
 */
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
