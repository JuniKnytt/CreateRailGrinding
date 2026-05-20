package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RailGrindDebugSyncPayload(
        boolean hasGrindState,
        double currentSpeed,
        double targetSpeed,
        double acceleration,
        double topSpeed,
        double experiencedSlope,

        double experiencedCurve,
        double position,
        double edgeLength,
        int stuckTicks,
        int totalTicks,
        double lateralSign,
        boolean edgeIsTurn,
        boolean crouchAccelerating,
        boolean collidingWithTrain,
        double originX, double originY, double originZ,
        double tangentX, double tangentY, double tangentZ,
        double snapX, double snapY, double snapZ,
        int trainOverlapTicks,
        int fallImmunityTicks,
        int startCooldownTicks,

        double driftMargin,

        int reattachGraceTicks,

        int ticksSinceGraceEnded,

        boolean hasLastDrop,
        int lastDropReasonOrd,
        int lastDropTicksSinceGraceEnded
) implements CustomPacketPayload {

    public static final Type<RailGrindDebugSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "rail_grind_debug_sync"));

    private static final RailGrindDebugSyncPayload EMPTY = new RailGrindDebugSyncPayload(
            false,
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0,
            0, false, false, false,
            0, 0, 0,
            0, 0, 0,
            0, 0, 0,
            0, 0, 0,
            Double.NaN,
            0,
            -1,
            false, 0, -1
    );

    public static final StreamCodec<FriendlyByteBuf, RailGrindDebugSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBoolean(p.hasGrindState);
                buf.writeDouble(p.currentSpeed);
                buf.writeDouble(p.targetSpeed);
                buf.writeDouble(p.acceleration);
                buf.writeDouble(p.topSpeed);
                buf.writeDouble(p.experiencedSlope);
                buf.writeDouble(p.experiencedCurve);
                buf.writeDouble(p.position);
                buf.writeDouble(p.edgeLength);
                buf.writeVarInt(p.stuckTicks);
                buf.writeVarInt(p.totalTicks);
                buf.writeDouble(p.lateralSign);
                buf.writeBoolean(p.edgeIsTurn);
                buf.writeBoolean(p.crouchAccelerating);
                buf.writeBoolean(p.collidingWithTrain);
                buf.writeDouble(p.originX); buf.writeDouble(p.originY); buf.writeDouble(p.originZ);
                buf.writeDouble(p.tangentX); buf.writeDouble(p.tangentY); buf.writeDouble(p.tangentZ);
                buf.writeDouble(p.snapX); buf.writeDouble(p.snapY); buf.writeDouble(p.snapZ);
                buf.writeVarInt(p.trainOverlapTicks);
                buf.writeVarInt(p.fallImmunityTicks);
                buf.writeVarInt(p.startCooldownTicks);
                buf.writeDouble(p.driftMargin);
                buf.writeVarInt(p.reattachGraceTicks);
                buf.writeVarInt(p.ticksSinceGraceEnded);
                buf.writeBoolean(p.hasLastDrop);
                buf.writeVarInt(p.lastDropReasonOrd);
                buf.writeVarInt(p.lastDropTicksSinceGraceEnded);
            },

            buf -> {
                try {
                    return new RailGrindDebugSyncPayload(
                            buf.readBoolean(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readVarInt(), buf.readVarInt(),
                            buf.readDouble(),
                            buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                            buf.readDouble(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readBoolean(), buf.readVarInt(), buf.readVarInt()
                    );
                } catch (IndexOutOfBoundsException e) {
                    if (buf.isReadable()) buf.skipBytes(buf.readableBytes());
                    return EMPTY;
                }
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
