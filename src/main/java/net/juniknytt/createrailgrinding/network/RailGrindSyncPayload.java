package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RailGrindSyncPayload(UUID playerId, boolean grinding, boolean silent) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(playerId);
        buf.writeBoolean(grinding);
        buf.writeBoolean(silent);
    }

    public static RailGrindSyncPayload decode(FriendlyByteBuf buf) {
        return new RailGrindSyncPayload(buf.readUUID(), buf.readBoolean(), buf.readBoolean());
    }

    public void handleClient(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPayloadHandler.handleSync(this)));
        context.setPacketHandled(true);
    }
}
