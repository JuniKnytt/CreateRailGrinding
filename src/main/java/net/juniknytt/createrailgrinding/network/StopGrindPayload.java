package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;

public record StopGrindPayload(int chargeTicks) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(chargeTicks);
    }

    public static StopGrindPayload decode(FriendlyByteBuf buf) {
        return new StopGrindPayload(buf.readVarInt());
    }
}
