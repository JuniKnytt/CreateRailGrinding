package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record GrindParticleBurstPayload(UUID playerId,
                                        float tangentX, float tangentY, float tangentZ,
                                        float speedRatio,
                                        byte count) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(playerId);
        buf.writeFloat(tangentX);
        buf.writeFloat(tangentY);
        buf.writeFloat(tangentZ);
        buf.writeFloat(speedRatio);
        buf.writeByte(count);
    }

    public static GrindParticleBurstPayload decode(FriendlyByteBuf buf) {
        return new GrindParticleBurstPayload(
                buf.readUUID(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(),
                buf.readByte());
    }

    public void handleClient(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPayloadHandler.handleParticleBurst(this)));
        context.setPacketHandled(true);
    }
}
