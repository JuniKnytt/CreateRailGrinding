package net.juniknytt.createrailgrinding.network;

import net.juniknytt.createrailgrinding.RailGrind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client per-player debug snapshot for the rail-grind HUD and debug renderer.
 * Emitted every server tick to each player when {@code Config.SYNC_DEBUG_TO_CLIENTS} is true
 * so QA can inspect grind state on a dedicated server (where the HUD/renderer's direct reads
 * of {@code RailGrindHandler.ACTIVE} return null because that map only exists server-side).
 *
 * <p>{@code hasGrindState} gates the grind block — when false the grind-only fields are
 * zero/unset and the client should hide the corresponding HUD lines and debug boxes. The
 * always-on counters (trainOverlapTicks / fallImmunityTicks / startCooldownTicks) are sent
 * regardless so the gate-state lines stay visible outside a grind.
 */
public record RailGrindDebugSyncPayload(
        boolean hasGrindState,
        double currentSpeed,
        double targetSpeed,
        double acceleration,
        double topSpeed,
        double experiencedSlope,
        // Signed curve direction at the player's spline position. +1 right, -1 left, 0 straight.
        // Smoothed server-side at CURVE_SMOOTH_RATE — see RailGrindHandler.GrindState.experiencedCurve.
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
        // Blocks of drift remaining before the catastrophic-desync bailout. NaN while
        // either grace gate is suppressing the drift check entirely. See GrindDebugInfo's
        // driftMargin doc for the full computation.
        double driftMargin,
        // Cross-dim re-attach grace counter — 0 when not in grace, > 0 during the
        // server-authoritative window. Surfaced to the HUD so the user can confirm
        // whether grace is active during a cross-dim handoff.
        int reattachGraceTicks,
        // -1 while either grace counter is still > 0; 0 on the first tick BOTH are 0; then
        // increments by 1 per tick. See RailGrindHandler.GrindState.ticksSinceGraceEnded.
        int ticksSinceGraceEnded,
        // Last-drop snapshot — the StopReason ordinal that triggered the most recent stop()
        // for this player, plus how many ticks past grace-end it happened. hasLastDrop=false
        // means no drop has been recorded this session. lastDropReasonOrd encodes
        // RailGrindHandler.StopReason by ordinal; clients clamp out-of-range values to UNKNOWN
        // (forward-compat with future enum additions). Lets the HUD render a human-readable
        // "Last cancel: <displayName>" line.
        boolean hasLastDrop,
        int lastDropReasonOrd,
        int lastDropTicksSinceGraceEnded
) implements CustomPacketPayload {

    public static final Type<RailGrindDebugSyncPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "rail_grind_debug_sync"));

    // Placeholder returned by the fail-soft decoder below when the incoming buffer is
    // shorter than expected (most commonly: server is running an older mod jar that
    // pre-dates trailing fields, so its writer ships N bytes while the new client's
    // reader wants N+k). hasGrindState=false hides the grind-only HUD lines; the
    // always-on counters render as 0.
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
            // Fail-soft decode: this is a non-essential debug payload, so any framing
            // mismatch (older server jar without the trailing fields, mid-packet
            // truncation by a networking mod, etc.) must NOT disconnect the client.
            // On any read failure, drain the remaining bytes and return EMPTY so the
            // debug HUD just shows the no-grind-state fallback for that frame.
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
