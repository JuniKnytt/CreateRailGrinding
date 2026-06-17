package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;

public record StartGrindFromNearestPayload() {
    public static final StartGrindFromNearestPayload INSTANCE = new StartGrindFromNearestPayload();

    public void encode(FriendlyByteBuf buf) {}

    public static StartGrindFromNearestPayload decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}
