package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RailGrindAccelSyncPayload(UUID playerId, boolean accelerating) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(playerId);
        buf.writeBoolean(accelerating);
    }

    public static RailGrindAccelSyncPayload decode(FriendlyByteBuf buf) {
        return new RailGrindAccelSyncPayload(buf.readUUID(), buf.readBoolean());
    }

    public void handleClient(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPayloadHandler.handleAccelSync(this)));
        context.setPacketHandled(true);
    }
}
