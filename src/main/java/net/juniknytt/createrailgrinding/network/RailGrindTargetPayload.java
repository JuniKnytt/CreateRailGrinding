package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RailGrindTargetPayload(double x, double y, double z,
                                     double vx, double vy, double vz,
                                     double slope,
                                     boolean serverAuthoritative) {
    public void encode(FriendlyByteBuf buf) {
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeDouble(vx);
        buf.writeDouble(vy);
        buf.writeDouble(vz);
        buf.writeDouble(slope);
        buf.writeBoolean(serverAuthoritative);
    }

    public static RailGrindTargetPayload decode(FriendlyByteBuf buf) {
        return new RailGrindTargetPayload(
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readDouble(),
                buf.readBoolean());
    }

    public Vec3 target()   { return new Vec3(x, y, z); }
    public Vec3 velocity() { return new Vec3(vx, vy, vz); }

    public void handleClient(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPayloadHandler.handleTarget(this)));
        context.setPacketHandled(true);
    }
}
