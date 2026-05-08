package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the local player just released jump while grinding (deliberate dismount).
 * {@code chargeTicks} is the number of client ticks jump was held before release; the server
 * clamps it to the [JUMP_TRICK_CHARGE_INPUT_TIME_MIN, JUMP_TRICK_CHARGE_INPUT_TIME_MAX] window
 * inside {@code RailGrindHandler.computeChargeRatio} and uses the resulting 0..1 ratio as a
 * multiplicative bonus on top of the existing speed-based launch impulse in stopWithLaunch.
 */
public record StopGrindPayload(int chargeTicks) implements CustomPacketPayload {
    public static final Type<StopGrindPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "stop_grind"));

    public static final StreamCodec<FriendlyByteBuf, StopGrindPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeVarInt(payload.chargeTicks),
            buf -> new StopGrindPayload(buf.readVarInt())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
