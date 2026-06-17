package net.juniknytt.createrailgrinding.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

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
) {

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

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(hasGrindState);
        buf.writeDouble(currentSpeed);
        buf.writeDouble(targetSpeed);
        buf.writeDouble(acceleration);
        buf.writeDouble(topSpeed);
        buf.writeDouble(experiencedSlope);
        buf.writeDouble(experiencedCurve);
        buf.writeDouble(position);
        buf.writeDouble(edgeLength);
        buf.writeVarInt(stuckTicks);
        buf.writeVarInt(totalTicks);
        buf.writeDouble(lateralSign);
        buf.writeBoolean(edgeIsTurn);
        buf.writeBoolean(crouchAccelerating);
        buf.writeBoolean(collidingWithTrain);
        buf.writeDouble(originX); buf.writeDouble(originY); buf.writeDouble(originZ);
        buf.writeDouble(tangentX); buf.writeDouble(tangentY); buf.writeDouble(tangentZ);
        buf.writeDouble(snapX); buf.writeDouble(snapY); buf.writeDouble(snapZ);
        buf.writeVarInt(trainOverlapTicks);
        buf.writeVarInt(fallImmunityTicks);
        buf.writeVarInt(startCooldownTicks);
        buf.writeDouble(driftMargin);
        buf.writeVarInt(reattachGraceTicks);
        buf.writeVarInt(ticksSinceGraceEnded);
        buf.writeBoolean(hasLastDrop);
        buf.writeVarInt(lastDropReasonOrd);
        buf.writeVarInt(lastDropTicksSinceGraceEnded);
    }

    public static RailGrindDebugSyncPayload decode(FriendlyByteBuf buf) {
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

    public void handleClient(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPayloadHandler.handleDebugSync(this)));
        context.setPacketHandled(true);
    }
}
