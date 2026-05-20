package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

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
