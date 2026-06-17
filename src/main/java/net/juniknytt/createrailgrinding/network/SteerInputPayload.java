package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;

public record SteerInputPayload(byte sign) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(sign);
    }

    public static SteerInputPayload decode(FriendlyByteBuf buf) {
        return new SteerInputPayload(buf.readByte());
    }
}
