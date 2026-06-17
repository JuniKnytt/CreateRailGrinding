package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;

public record BlockedByObstaclePayload() {
    public static final BlockedByObstaclePayload INSTANCE = new BlockedByObstaclePayload();

    public void encode(FriendlyByteBuf buf) {}

    public static BlockedByObstaclePayload decode(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}
