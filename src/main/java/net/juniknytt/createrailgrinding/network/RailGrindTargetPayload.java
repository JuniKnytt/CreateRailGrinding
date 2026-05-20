package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record RailGrindTargetPayload(double x, double y, double z,
                                     double vx, double vy, double vz,
                                     double slope,
                                     boolean serverAuthoritative) implements CustomPacketPayload {
    public static final Type<RailGrindTargetPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "rail_grind_target"));

    public static final StreamCodec<ByteBuf, RailGrindTargetPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeDouble(p.x);
            buf.writeDouble(p.y);
            buf.writeDouble(p.z);
            buf.writeDouble(p.vx);
            buf.writeDouble(p.vy);
            buf.writeDouble(p.vz);
            buf.writeDouble(p.slope);
            buf.writeBoolean(p.serverAuthoritative);
        },
        buf -> new RailGrindTargetPayload(
            buf.readDouble(), buf.readDouble(), buf.readDouble(),
            buf.readDouble(), buf.readDouble(), buf.readDouble(),
            buf.readDouble(),
            buf.readBoolean()
        )
    );

    public Vec3 target()   { return new Vec3(x, y, z); }
    public Vec3 velocity() { return new Vec3(vx, vy, vz); }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
