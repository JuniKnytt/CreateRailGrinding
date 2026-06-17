package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;

public record ChainMountedPayload() {
    public static final ChainMountedPayload INSTANCE = new ChainMountedPayload();

    public void encode(FriendlyByteBuf buf) {}

    public static ChainMountedPayload decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}
