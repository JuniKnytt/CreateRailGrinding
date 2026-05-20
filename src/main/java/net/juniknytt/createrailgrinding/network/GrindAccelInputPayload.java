package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GrindAccelInputPayload(byte mode) implements CustomPacketPayload {
    public static final byte VANILLA = 0;
    public static final byte OVERRIDE_OFF = 1;
    public static final byte OVERRIDE_ON = 2;

    public static final Type<GrindAccelInputPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "grind_accel_input"));

    public static final StreamCodec<FriendlyByteBuf, GrindAccelInputPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeByte(payload.mode),
            buf -> new GrindAccelInputPayload(buf.readByte())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
