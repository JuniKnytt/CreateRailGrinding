package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;

public record CrossDimGraceReleasePayload() {
    public static final CrossDimGraceReleasePayload INSTANCE = new CrossDimGraceReleasePayload();

    public void encode(FriendlyByteBuf buf) {}

    public static CrossDimGraceReleasePayload decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}
