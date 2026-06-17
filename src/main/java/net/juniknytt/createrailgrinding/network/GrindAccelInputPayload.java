package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;

public record GrindAccelInputPayload(byte mode) {
    public static final byte VANILLA = 0;
    public static final byte OVERRIDE_OFF = 1;
    public static final byte OVERRIDE_ON = 2;

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(mode);
    }

    public static GrindAccelInputPayload decode(FriendlyByteBuf buf) {
        return new GrindAccelInputPayload(buf.readByte());
    }
}
