package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RailGrindLeanSyncPayload(UUID playerId, byte steerSign) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(playerId);
        buf.writeByte(steerSign);
    }

    public static RailGrindLeanSyncPayload decode(FriendlyByteBuf buf) {
        return new RailGrindLeanSyncPayload(buf.readUUID(), buf.readByte());
    }

    public void handleClient(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPayloadHandler.handleLeanSync(this)));
        context.setPacketHandled(true);
    }
}
