package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: the local player's current intersection-steer input while grinding.
 *  -1 = left  (vanilla strafe-left key held)
 *   0 = none  (neither held, or both)
 *  +1 = right (vanilla strafe-right key held)
 *
 * Sent only when the value flips, so straight-line grinding costs no traffic. The handler
 * stores it on RailGrindHandler.GrindState.steerSign; advanceJunction() consumes it as the
 * targetDot in the same lateral-projection algorithm Create's TravellingPoint.steer uses
 * for player-controlled trains.
 */
public record SteerInputPayload(byte sign) implements CustomPacketPayload {
    public static final Type<SteerInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "steer_input"));

    public static final StreamCodec<FriendlyByteBuf, SteerInputPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeByte(payload.sign),
            buf -> new SteerInputPayload(buf.readByte())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
