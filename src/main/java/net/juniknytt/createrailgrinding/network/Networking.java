package net.juniknytt.createrailgrinding.network;

import com.simibubi.create.content.equipment.armor.DivingBootsItem;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import com.simibubi.create.content.trains.track.ITrackBlock;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

@EventBusSubscriber(modid = RailGrind.MODID, bus = EventBusSubscriber.Bus.MOD)
public class Networking {
    /**
     * Maximum world-space distance from the player's feet to a rail spline (any track type —
     * plain block or bezier curve) that still counts as "near a rail" for the polling jump+sneak
     * trigger. 1.75 captures a rail under the player's feet (~0.5–0.87 depending on geometry),
     * a same-level adjacent rail (~1.12), and gives a quarter-block of extra leniency so the
     * player doesn't have to be perfectly aligned to grab on. Squared at the sample site.
     */
    private static final double NEAREST_RAIL_MAX_DIST = 1.75;

    /**
     * Right-click → grind init payload.
     *  - target: world-space position to teleport the player to before grind setup. For plain
     *    track blocks that's the block's bottom center; for bezier hits it's the actual point
     *    on the curve that was clicked.
     *  - bezier: present iff the click landed on a bezier curve segment (TrackBlockOutline.result
     *    was non-null on the client). Carries the endpoint block + BezierTrackPointLocation so
     *    the server can resolve the exact curve point via TrackGraphHelper.getBezierGraphLocationAt
     *    instead of snapping to an endpoint.
     */
    public record TeleportToRailPacket(Vec3 target, @Nullable BezierTarget bezier) implements CustomPacketPayload {
        public record BezierTarget(BlockPos endpoint, BezierTrackPointLocation loc) {}

        public static final Type<TeleportToRailPacket> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "teleport_to_rail"));

        public static final StreamCodec<FriendlyByteBuf, TeleportToRailPacket> STREAM_CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeDouble(packet.target.x);
                    buf.writeDouble(packet.target.y);
                    buf.writeDouble(packet.target.z);
                    boolean hasBezier = packet.bezier != null;
                    buf.writeBoolean(hasBezier);
                    if (hasBezier) {
                        buf.writeBlockPos(packet.bezier.endpoint());
                        BezierTrackPointLocation.STREAM_CODEC.encode(buf, packet.bezier.loc());
                    }
                },
                buf -> {
                    Vec3 target = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                    BezierTarget bezier = null;
                    if (buf.readBoolean()) {
                        BlockPos endpoint = buf.readBlockPos();
                        BezierTrackPointLocation loc = BezierTrackPointLocation.STREAM_CODEC.decode(buf);
                        bezier = new BezierTarget(endpoint, loc);
                    }
                    return new TeleportToRailPacket(target, bezier);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar(RailGrind.MODID)
                .playToServer(
                        TeleportToRailPacket.TYPE,
                        TeleportToRailPacket.STREAM_CODEC,
                        Networking::handleTeleport)
                .playToServer(
                        StopGrindPayload.TYPE,
                        StopGrindPayload.STREAM_CODEC,
                        Networking::handleStopGrind)
                .playToServer(
                        SteerInputPayload.TYPE,
                        SteerInputPayload.STREAM_CODEC,
                        Networking::handleSteerInput)
                .playToServer(
                        GrindAccelInputPayload.TYPE,
                        GrindAccelInputPayload.STREAM_CODEC,
                        Networking::handleAccelInput)
                .playToServer(
                        StartGrindFromNearestPayload.TYPE,
                        StartGrindFromNearestPayload.STREAM_CODEC,
                        Networking::handleStartFromNearest);
    }

    /**
     * Polling jump+sneak trigger handler. Doesn't take a target from the client — instead
     * resolves the nearest spline point on any track graph to the player's feet via
     * {@link RailGrindHandler#findNearestRailLocation}. Handles plain straight tracks and
     * bezier curves uniformly because both live as edges in the graph, so the player can
     * trigger from a curve segment without aiming at it.
     */
    private static void handleStartFromNearest(StartGrindFromNearestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (RailGrindHandler.isGrinding(player)) return;
            if (RailGrindHandler.isPlayerOnRailGrindCooldown(player)) return;
            if (RailGrindHandler.isPlayerCrushedByTrain(player)) return;
            if (!(player.getItemBySlot(EquipmentSlot.FEET).getItem() instanceof DivingBootsItem)) return;

            TrackGraphLocation loc = RailGrindHandler.findNearestRailLocation(
                    player.level(), player.position(), NEAREST_RAIL_MAX_DIST);
            if (loc == null) return;

            // No teleport — railgrinding() snaps the player onto the rail bar itself, so
            // the prePos passed in here is just used to pick which side of the rail to ride.
            Vec3 prePos = player.position();
            double entryVelocity = player.getDeltaMovement().length();
            RailGrindHandler.railgrinding(player, loc, prePos, entryVelocity);
        });
    }

    private static void handleStopGrind(StopGrindPayload payload, IPayloadContext context) {
        // Only the jump-key handler in ClientInputHandler sends StopGrindPayload, so any
        // arrival here is a deliberate dismount that should produce the launch boost. Other
        // server-side stop paths (rail end, login/logout, stuck) still call plain stop().
        // The held-jump charge ticks are clamped to the configured window inside stopWithLaunch
        // → computeChargeRatio, so an out-of-range value from a malicious client just saturates
        // at the max ratio.
        context.enqueueWork(() -> RailGrindHandler.stopWithLaunch(context.player(), payload.chargeTicks()));
    }

    private static void handleSteerInput(SteerInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RailGrindHandler.setSteerInput(context.player(), payload.sign()));
    }

    private static void handleAccelInput(GrindAccelInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RailGrindHandler.setAccelInputMode(context.player(), payload.mode()));
    }

    private static void handleTeleport(TeleportToRailPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (RailGrindHandler.isGrinding(player)) {
                RailGrindHandler.stop(player);
                return;
            }
            // Bail before teleportTo runs — otherwise cooldown-rejected attempts (especially
            // from the polling jump+sneak trigger, which fires every tick) still yank the
            // player onto the rail even though railgrinding() refuses to start.
            if (RailGrindHandler.isPlayerOnRailGrindCooldown(player)) return;
            // Same early-bail logic for the train-crush gate: refusing inside railgrinding()
            // alone would still teleport the player onto a rail every tick they're crushed,
            // which is jarring even though the grind itself never starts.
            if (RailGrindHandler.isPlayerCrushedByTrain(player)) return;
            if (player.getMainHandItem().getItem() != Items.AIR) return;
            if (!(player.getItemBySlot(EquipmentSlot.FEET).getItem() instanceof DivingBootsItem)) return;
            Vec3 t = payload.target;
            if (player.position().distanceToSqr(t) > 100) return;
            // Capture pre-teleport position so railgrinding can pick which rail bar to ride on.
            Vec3 prePos = player.position();
            // Absolute velocity at the moment of interaction — added to the grind's initial
            // speed so a sprint or a fall feeds momentum into the grind instead of being
            // discarded by the velocity-zero in railgrinding(). Captured before teleportTo
            // because some teleport paths can clear deltaMovement.
            double entryVelocity = player.getDeltaMovement().length();
            player.teleportTo(t.x, t.y, t.z);

            if (payload.bezier != null) {
                // Curve hit. getBezierGraphLocationAt resolves the (endpoint, BezierTrackPointLocation)
                // pair to a graph location whose `position` is the distance along the bezier edge
                // to the clicked segment — so the grind starts at the click point, not the endpoint.
                // The AxisDirection just selects which endpoint counts as the "from" node for the
                // returned location; railgrinding() decides actual travel direction from the player's
                // look angle, so either direction is fine — try positive first, fall back to negative.
                Level level = player.level();
                TrackGraphLocation loc = TrackGraphHelper.getBezierGraphLocationAt(
                        level, payload.bezier.endpoint(), Direction.AxisDirection.POSITIVE, payload.bezier.loc());
                if (loc == null) loc = TrackGraphHelper.getBezierGraphLocationAt(
                        level, payload.bezier.endpoint(), Direction.AxisDirection.NEGATIVE, payload.bezier.loc());
                if (loc != null) RailGrindHandler.railgrinding(player, loc, prePos, entryVelocity);
                return;
            }

            BlockPos railPos = findRailBlockAt(player.level(), t);
            if (railPos != null) RailGrindHandler.railgrinding(player, railPos, prePos, entryVelocity);
        });
    }

    private static BlockPos findRailBlockAt(Level level, Vec3 target) {
        BlockPos here = BlockPos.containing(target);
        if (level.getBlockState(here).getBlock() instanceof ITrackBlock) return here;
        BlockPos below = here.below();
        if (level.getBlockState(below).getBlock() instanceof ITrackBlock) return below;
        return null;
    }
}
