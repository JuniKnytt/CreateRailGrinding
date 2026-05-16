package net.juniknytt.createrailgrinding.network;

import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.enchantment.ModEnchantments;
import net.juniknytt.createrailgrinding.rail.RailGrindHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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
     * Right-click → grind init payload. Carries the world-space interaction-end Vec3 (the
     * point the player's crosshair landed on a rail outline) — the server resolves the
     * nearest nodegraph from that point, teleports the player onto the rail, and starts
     * the grind. See {@link #handleTeleport}.
     */
    public record TeleportToRailPacket(Vec3 target) implements CustomPacketPayload {
        public static final Type<TeleportToRailPacket> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "teleport_to_rail"));

        public static final StreamCodec<FriendlyByteBuf, TeleportToRailPacket> STREAM_CODEC = StreamCodec.of(
                (buf, packet) -> {
                    buf.writeDouble(packet.target.x);
                    buf.writeDouble(packet.target.y);
                    buf.writeDouble(packet.target.z);
                },
                buf -> new TeleportToRailPacket(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()))
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
                        Networking::handleStartFromNearest)
                .playToServer(
                        ChainMountedPayload.TYPE,
                        ChainMountedPayload.STREAM_CODEC,
                        Networking::handleChainMounted)
                .playToServer(
                        CrossDimGraceReleasePayload.TYPE,
                        CrossDimGraceReleasePayload.STREAM_CODEC,
                        Networking::handleCrossDimGraceRelease)
                .playToServer(
                        BlockedByObstaclePayload.TYPE,
                        BlockedByObstaclePayload.STREAM_CODEC,
                        Networking::handleBlockedByObstacle);
    }

    /**
     * Client → server signal from {@link net.juniknytt.createrailgrinding.client.RailGrindClientMotion}
     * that its per-tick Δ_actual / Δ_intent detector (or the slope-branch forward bb-probe)
     * has confirmed the player is being blocked by geometry. Server stops the grind with
     * {@link RailGrindHandler.StopReason#BLOCKED}. {@code stop()} is a no-op if the player
     * isn't grinding, so a stray packet is harmless. Suppressed during cross-dim re-attach
     * grace because the detector explicitly skips those ticks and a packet arriving from
     * the previous-dim client during the transit window would be stale.
     */
    private static void handleBlockedByObstacle(BlockedByObstaclePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (RailGrindHandler.isInReattachGrace(player)) return;
            if (RailGrindHandler.isGrinding(player)) {
                RailGrindHandler.stop(player, RailGrindHandler.StopReason.BLOCKED);
            }
        });
    }

    /**
     * Client → server "ready" ack for the cross-dim re-grind grace window. Forwards to
     * {@link RailGrindHandler#releaseReattachGrace}, which collapses the grace counter
     * to a small post-ack tail so motion resumes promptly without waiting out the full
     * timeout. Idempotent on the receiving side — a spurious or repeated ack can't
     * extend grace, only end it sooner.
     */
    private static void handleCrossDimGraceRelease(CrossDimGraceReleasePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RailGrindHandler.releaseReattachGrace(context.player()));
    }

    /**
     * Client just embarked on a Create chain conveyor — drop the grind immediately so the
     * wrench-mount hands off into chain riding without fighting our setDeltaMovement for the
     * up-to-10-tick window before {@code ServerChainConveyorHandler.hangingPlayers} reflects
     * the ride. The redundant server-side {@code hangingPlayers.containsKey} check in
     * {@link RailGrindHandler#tick} is left in place as a fallback for any path that
     * populates the map without going through this client-driven payload. {@code stop()}
     * short-circuits if the player isn't grinding, so a stray packet is harmless.
     *
     * <p>Skipped during cross-dim re-attach grace: Create's
     * {@code ChainConveyorRidingHandler.ridingChainConveyor} is not cleared on cross-dim, so
     * the client-side {@link net.juniknytt.createrailgrinding.client.ClientInputHandler#onChainMountTick}
     * edge-trigger can fire a spurious payload on the first tick after re-grind starts in the
     * destination dim. The user reported this as an "occasional drop from railgrind mode" mid-
     * grace. A real chain-mount during the grace window will re-trigger after grace expires
     * via the 10-tick TTL on the server-side {@code hangingPlayers} map.
     */
    private static void handleChainMounted(ChainMountedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (RailGrindHandler.isInReattachGrace(player)) return;
            if (RailGrindHandler.isGrinding(player)) {
                RailGrindHandler.stop(player, RailGrindHandler.StopReason.CHAIN_MOUNTED);
            }
        });
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
            // Refuse before findNearestRailLocation runs — otherwise the polling trigger keeps
            // searching the track graph every tick while the player sits on a mob or Create seat.
            if (player.isPassenger()) return;
            if (!ModEnchantments.isWearingRailGrindBoots(player)) return;

            RailGrindHandler.RailHit hit = RailGrindHandler.findNearestRailLocation(
                    player.level(), player.position(), NEAREST_RAIL_MAX_DIST);
            if (hit == null) return;

            // No teleport — railgrinding() snaps the player onto the rail bar itself, so
            // the prePos passed in here is just used to pick which side of the rail to ride.
            // The sublevel handle (null when grinding on a parent-world rail) is forwarded
            // so the railgrinding handler routes worldPos / tangent / launch through the
            // sublevel's pose.
            Vec3 prePos = player.position();
            double entryVelocity = player.getDeltaMovement().length();
            RailGrindHandler.railgrinding(player, hit.loc(), prePos, entryVelocity, hit.subLevel());
        });
    }

    private static void handleStopGrind(StopGrindPayload payload, IPayloadContext context) {
        // Only the jump-key handler in ClientInputHandler sends StopGrindPayload, so any
        // arrival here is a deliberate dismount that should produce the launch boost. Other
        // server-side stop paths (rail end, login/logout, stuck) still call plain stop().
        // The held-jump charge ticks are clamped to the configured window inside stopWithLaunch
        // → computeChargeRatio, so an out-of-range value from a malicious client just saturates
        // at the max ratio.
        context.enqueueWork(() -> RailGrindHandler.stopWithLaunch(context.player(), payload.chargeTicks(), RailGrindHandler.StopReason.USER_DISMOUNT));
    }

    private static void handleSteerInput(SteerInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RailGrindHandler.setSteerInput(context.player(), payload.sign()));
    }

    private static void handleAccelInput(GrindAccelInputPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> RailGrindHandler.setAccelInputMode(context.player(), payload.mode()));
    }

    /**
     * Server side of the refactored right-click flow. The client picked an "interaction-end"
     * Vec3 — the precise world point the player aimed at on a rail outline (curve segment
     * point from Create's picker, or a hit point on a plain track block). The server:
     * <ol>
     *   <li>Runs all the canonical refuse-grind gates (cooldown, crushed, passenger, empty
     *       hand, boots, distance).</li>
     *   <li>Resolves the nearest nodegraph from the interaction-end via
     *       {@link RailGrindHandler#findNearestRailInLevel} — same scan jump+sneak uses,
     *       just rooted at the click point instead of the player's feet. The scan's
     *       per-block / per-curve {@link RailGrindHandler#isGrindableMaterial} filter
     *       enforces the rail-type gate (STANDARD / narrow_gauge / wide_gauge accepted;
     *       phantom variants, UNIVERSAL, MONORAIL rejected).</li>
     *   <li>Teleports the player to the interaction-end. We use the click point rather than
     *       reconstructing the rail's world coords from the {@link TrackGraphLocation}
     *       because {@code railgrinding} snaps the player to the rail bar via {@code setPos}
     *       anyway, and the click point is guaranteed within {@code NEAREST_RAIL_MAX_DIST}
     *       of the rail by the scan having returned non-null.</li>
     *   <li>Initiates the railgrind via {@link RailGrindHandler#railgrinding}, which applies
     *       its own canonical gates (post-cooldown, crushed, passenger, edge-material check)
     *       before flipping the state.</li>
     * </ol>
     *
     * <p>Strictly parent-world. Sublevel rail right-clicks route through
     * {@link StartGrindFromNearestPayload} on the client to avoid the chunk-cache disconnect
     * a teleport adjacent to a Sable plot has historically triggered.
     */
    private static void handleTeleport(TeleportToRailPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (RailGrindHandler.isGrinding(player)) {
                RailGrindHandler.stop(player, RailGrindHandler.StopReason.TELEPORT_REQUEST);
                return;
            }
            if (RailGrindHandler.isPlayerOnRailGrindCooldown(player)) return;
            if (RailGrindHandler.isPlayerCrushedByTrain(player)) return;
            if (player.isPassenger()) return;
            if (player.getMainHandItem().getItem() != Items.AIR) return;
            if (!ModEnchantments.isWearingRailGrindBoots(player)) return;

            Vec3 interactionEnd = payload.target;
            // Distance sanity check — client should only ever send a target within reach.
            if (player.position().distanceToSqr(interactionEnd) > 100) return;

            // Step 1: Resolve the nodegraph nearest the interaction-end. Rail-type gate
            // (isGrindableMaterial) is applied inside the scan, so non-grindable rails
            // return null here and we bail before teleporting.
            TrackGraphLocation loc = RailGrindHandler.findNearestRailInLevel(
                    player.level(), interactionEnd, NEAREST_RAIL_MAX_DIST);
            if (loc == null) return;

            // Step 2: Snapshot pre-teleport state for the launch / direction picks downstream.
            Vec3 prePos = player.position();
            double entryVelocity = player.getDeltaMovement().length();

            // Step 3: Teleport to the interaction-end (railgrinding will snap onto the bar).
            player.teleportTo(interactionEnd.x, interactionEnd.y, interactionEnd.z);

            // Step 4: Initiate the grind.
            RailGrindHandler.railgrinding(player, loc, prePos, entryVelocity);
        });
    }
}
