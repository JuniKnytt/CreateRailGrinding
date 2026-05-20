package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

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
