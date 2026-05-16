package net.juniknytt.createrailgrinding.network;

import io.netty.buffer.ByteBuf;
import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Server → client per-tick target position + velocity hint for the local grinding player.
 *
 * <p>The velocity field is the server's last-tick advance (currentTarget − previousTarget
 * in world space). The client uses it for inter-packet extrapolation in
 * {@code RailGrindClientMotion}'s predict-correct chase: each client tick the local
 * smoothedTarget advances by velocity, then corrects toward the latest received target.
 * That decouples per-tick motion from packet-arrival timing — packet jitter no longer
 * translates into per-tick chase-magnitude variation.
 *
 * <p>Why velocity rather than just target: rigid 100%-close-gap chase (the previous
 * scheme) moved the player by exactly the gap-to-target each tick. Under network jitter
 * the gap was 0 / 1 / 2 server-ticks worth depending on whether packets arrived between
 * client ticks, so the player visibly stuttered. With a velocity hint the client can
 * keep moving smoothly between packets and reconcile when fresh targets land.
 *
 * <p>{@code slope} ships the server's {@code GrindState.experiencedSlope} (motion.y /
 * motion.length() from the previous tick — sin of pitch, +up / -down). The client mirrors
 * it onto {@code RailGrindHandler.clientLocalSlope} for debug HUDs and any future
 * server-authoritative consumer. The active {@code PlayerNoPhysicsTickMixin} does NOT read
 * this synced value on the client — it derives the slope from {@code Player.getDeltaMovement}
 * locally to keep the noPhysics check latency-free. The mirror is retained for parity
 * with the server-side {@code GrindState.experiencedSlope} field.
 */
public record RailGrindTargetPayload(double x, double y, double z,
                                     double vx, double vy, double vz,
                                     double slope,
                                     boolean serverAuthoritative) implements CustomPacketPayload {
    public static final Type<RailGrindTargetPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "rail_grind_target"));

    // Manual encode/decode rather than StreamCodec.composite: the composite overloads cap
    // at 6 fields (Function6), and this payload has 7 doubles + 1 bool. Mirrors the
    // {@link RailGrindDebugSyncPayload} pattern.
    //
    // {@code serverAuthoritative} is true during the cross-dim re-attach window: the
    // client hard-snaps to {@code target} that tick (rather than running predict-correct)
    // so server↔client position stays bit-identical while the new LocalPlayer is still
    // settling. See {@code RailGrindClientMotion.onClientTickPre}.
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
