package net.juniknytt.createrailgrinding.rail;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphHelper;
import com.simibubi.create.content.trains.graph.TrackGraphLocation;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.createmod.catnip.data.Couple;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.network.RailGrindSyncPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RailGrindHandler {
    // Train-collision damage type — used when the grinding cube overlaps a carriage AABB.
    // See data/createrailgrinding/damage_type/flattened.json and the death.attack.flattened
    // lang key. Looked up from the server level's registry on each hit because DamageType is
    // dynamic-registry-only (no static Holder). Public so ModEvents.onIncomingDamageStopGrind
    // can detect already-calibrated train-collision damage and skip its blanket speed×10
    // multiplier.
    public  static final ResourceKey<DamageType> FLATTENED_DAMAGE = ResourceKey.create(
        Registries.DAMAGE_TYPE,
        ResourceLocation.fromNamespaceAndPath(RailGrind.MODID, "flattened"));
    private static final Map<UUID, GrindState> ACTIVE = new ConcurrentHashMap<>();
    // game-tick at which post-dismount fall-damage immunity expires, per player UUID. Populated
    // by stop(), drained lazily by hasFallImmunity(). Server-side only — every stop() caller
    // (PlayerTickEvent.Post, network packet handlers, login/logout/respawn) runs on the server.
    private static final Map<UUID, Long> FALL_IMMUNITY_UNTIL = new ConcurrentHashMap<>();
    private static final int FALL_IMMUNITY_TICKS = 40;                    // 2 s — covers the landing arc after a stopWithLaunch dismount and the drop after stepping off an elevated rail
    // Remaining cooldown ticks before another grind can begin, per player UUID. Set to
    // START_COOLDOWN_TICKS by stop() (every grind exit), decremented once per server tick
    // by tickCooldown() (called from ModEvents.onPlayerTick), evicted when it hits 0.
    // Read by isPlayerOnRailGrindCooldown() — returns true while > 0, blocking both
    // Networking.handleTeleport (teleport-to-rail) and railgrinding() (start the ride).
    // Server-side only.
    private static final Map<UUID, Integer> START_COOLDOWN_REMAINING = new ConcurrentHashMap<>();
    private static final int START_COOLDOWN_TICKS = 10;                   // 0.5 s — gap after grind exit before the next grind can begin. Long enough that the polling jump+sneak trigger can't re-fire the instant after a manual dismount; short enough that a deliberate re-grind feels responsive.
    // Per-player counter of consecutive ticks the player's hitbox has been overlapping any
    // CarriageContraptionEntity's bounding box. Ticked every server tick by tickTrainOverlap();
    // increments while overlapping, hard-resets to 0 (entry removed) the first tick the
    // intersection clears — so a series of momentary brush-bys can never accumulate into a
    // kick. Once the count reaches TRAIN_OVERLAP_KICK_TICKS, the player counts as "crushed":
    // any active grind is dropped from inside tickTrainOverlap, and new grind starts are
    // refused by isPlayerCrushedByTrain (gates Networking.handleTeleport, handleStartFromNearest,
    // and railgrinding(loc, ...)). The counter keeps incrementing past the threshold while
    // overlap persists so the HUD can keep displaying the live "how long stuck" value, and
    // the absence of an entry (count == 0) doubles as the canonical "no overlap this tick"
    // signal — the HUD's intersect bool is just count > 0, no separate boolean needed.
    private static final Map<UUID, Integer> TRAIN_OVERLAP_TICKS = new ConcurrentHashMap<>();
    private static final int TRAIN_OVERLAP_KICK_TICKS = 5;                // 0.25 s — kick threshold. Brief enough that getting run over by a train terminates the grind well before drag/desync sets in, while still tolerating the 1–2 ticks of overlap the AABB lookup can pick up at the start or tail of a fast pass-through.
    public  static final double TOP_SPEED = 0.84;                         // shift held — 3× vanilla sprint (~16.8 m/s) — referenced by the client speedometer overlay to normalize the fill ratio
    private static final double CRUISE_SPEED = 0.13;                      // shift released — vanilla sprint pace
    private static final double ACCELERATION = 0.005;                     // ~0.005/tick — momentum feel: 0→CRUISE ≈ 1.3 s, 0→TOP (unboosted) ≈ 8.4 s
    private static final double BOOST_ACCEL_MULT = 2.0;                   // shift → accel × 2 (CRUISE → TOP boosted ≈ 3.5 s)
    private static final double DOWNHILL_FACTOR = 0.9;                    // top speed × (1 + |slope| · 0.9) on descents
    private static final double UPHILL_FACTOR = 1.5;                      // top speed × (1 − slope · 1.5) on ascents — steep up gets noticeably slow
    private static final double DOWNHILL_ACCEL_BOOST = 2.0;               // accel up to 2× on the steepest descents (gentler than before)
    private static final double CURVE_FACTOR = 0.75;                      // bezier turns trim 25%
    private static final double MIN_SPEED = 0.10;                         // floor at walking pace (~2 m/s) on steep climbs
    private static final double Y_OFFSET = 0.5;                           // vertical hover above rail line (collision bypassed by noPhysics)
    private static final double LATERAL_OFFSET = 1.0;                  // 5/16 — matches the rail bar's position within the track block, so the player rides on the bar instead of the block centerline. Side is picked once at grind init from the player's pre-teleport position (GrindState.lateralSign).
    private static final double MAX_STEP = 2.0;                           // smooth catch-up cap (~40 m/s) — must exceed TOP_SPEED · downhill boost
    private static final double REALIGN_GAIN = 0.15;                      // per-tick fraction of (playerPos − expectedPos) folded into expectedPos. Continuous soft drift correction: ≈0 when on track (playerPos ≈ expectedPos in steady state), proportional to drift when desync occurs. Replaces the prior binary box-threshold realign that toggled the velocity reference and produced visible jitter on bezier curves.
    // Half-extents of the rendered snap-target box (local frame: +Z = travel-aligned tangent,
    // +X = horizontal right-of-travel, +Y = perpendicular up). Visualization-only —
    // RailGrindDebugRenderer reads these to draw the cubes; the realignment behavior is
    // continuous (REALIGN_GAIN) and no longer threshold-driven, so these don't gate any logic.
    public static final double SNAP_BOX_HALF_W = 0.15;
    public static final double SNAP_BOX_HALF_H = 0.15;
    public static final double SNAP_BOX_HALF_L = 0.40;
    private static final double MAX_DRIFT = 10.0;                         // distance from snap target above which we hard-drop the grind (catastrophic desync — gs.position has advanced somewhere the player can't follow). Bypasses STUCK_GRACE_TICKS because at this scale the cause isn't sync lag.
    private static final double STUCK_VELOCITY_THRESHOLD = 0.05;          // per-tick displacement (blocks) below which the player counts as "not moving" — well under MIN_SPEED so legitimate steep-climb grinding never trips it
    private static final int STUCK_DROP_TICKS = 3;                        // 3 consecutive stuck ticks (after grace) → drop from grind
    private static final int STUCK_GRACE_TICKS = 8;                       // first 0.4 s of grind: ignore stuck (let noPhysics sync to client)
    private static final double BOGEY_AABB_INFLATE = 0.5;                 // inflation applied to each bogey's AABB before intersecting against the player hitbox. The two axle TravellingPoints define only a line; bogeys also have a wheel-gauge width and a frame reaching up to the carriage floor — half a block of inflation rounds the line out to roughly the right physical volume without spilling far past the bogey body.
    private static final double TRAIN_SEARCH_PADDING = 8.0;               // pad applied to the player's hitbox before getEntitiesOfClass(CarriageContraptionEntity.class, …). Covers Train.maxSpeed (~0.4 b/t) for several ticks of approach so a fast oncoming carriage can't enter the player's space between checks.
    private static final double LAUNCH_HORIZONTAL_MULT = 2.0;             // jump-off horizontal velocity = currentSpeed × this. >1 so dismount feels like a launch rather than coasting.
    private static final double LAUNCH_VERTICAL_BASE = 0.42;              // vanilla jump strength — minimum upward kick on jump-off, even at MIN_SPEED.
    private static final double LAUNCH_VERTICAL_SCALE = 0.6;              // extra vertical velocity per unit of currentSpeed. At TOP_SPEED this stacks ~0.5 on top of the base for ≈ 9-block launches.
    // Speed-tier dust tints for rail-grinding sparks.
    private static final DustParticleOptions WHITE_SPARK  = new DustParticleOptions(new Vector3f(1.0f, 1.0f, 1.0f), 1.0f);
    private static final DustParticleOptions YELLOW_SPARK = new DustParticleOptions(new Vector3f(1.0f, 1.0f, 0.0f), 1.0f);
    private static final DustParticleOptions RED_SPARK    = new DustParticleOptions(new Vector3f(1.0f, 0.0f, 0.0f), 1.0f);

    private RailGrindHandler() {}

    private static final class GrindState {
        final TrackGraph graph;
        TrackNode fromNode;
        TrackNode toNode;
        TrackEdge edge;
        double position;
        double currentSpeed;
        int stuckTicks;      // consecutive ticks the player's per-tick displacement was below STUCK_VELOCITY_THRESHOLD
        int totalTicks;      // ticks since grind began — used for the noPhysics-sync grace window
        Vec3 prevPos;        // player position at the start of the previous tick — used to derive experienced slope
        double experiencedSlope;  // motion.y / motion.length() from last tick (sin of pitch); +up / -down
        double lateralSign;  // +1 or -1, fixed for the grind: which rail bar the player is riding on. Picked at init from prePos.
        Vec3 expectedPos;    // where the player *should* be after last tick's velocity was applied — used as the velocity reference instead of player.position(), which lags the client by 1+ ticks and accumulates chord-cut drift on parallel curves.
        boolean collidingWithTrain; // set by checkTrainCollision each tick: true if the player's hitbox AABB intersects any bogey's AABB. Surfaced via GrindDebugInfo for the debug HUD; also gates whether train-collision damage is applied (after a relSpeed threshold).
        int steerSign;       // -1 = left, 0 = none, +1 = right. Synced from the local player via SteerInputPayload (sent only when the value flips). advanceJunction reads this as targetDot for the same lateral-projection algorithm Create's TravellingPoint.steer uses on player-controlled trains.

        GrindState(TrackGraph graph, TrackNode fromNode, TrackNode toNode, TrackEdge edge, double position) {
            this.graph = graph;
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.edge = edge;
            this.position = position;
            this.currentSpeed = CRUISE_SPEED;  // launch at sprint pace, not from a dead stop
            this.lateralSign = 1.0;            // overwritten by railgrinding once prePos is known
        }
    }

    /**
     * Updates the player's steer input (-1 left, 0 none, +1 right). No-op when the player
     * isn't grinding. Called from the SteerInputPayload handler on the server.
     */
    public static void setSteerInput(Player player, int steerSign) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;
        gs.steerSign = Math.max(-1, Math.min(1, steerSign));
    }

    public static boolean railgrinding(Player player, BlockPos trackPos, Vec3 prePos, double entryVelocity) {
        Level level = player.level();
        BlockState state = level.getBlockState(trackPos);
        if (!(state.getBlock() instanceof ITrackBlock track)) return false;
        if (track.getMaterial().trackType != TrackMaterial.TrackType.STANDARD) return false;

        List<Vec3> axes = track.getTrackAxes(level, trackPos, state);
        if (axes.isEmpty()) return false;

        TrackGraphLocation loc = null;
        outer:
        for (Vec3 axis : axes) {
            for (Direction.AxisDirection dir : Direction.AxisDirection.values()) {
                loc = TrackGraphHelper.getGraphLocationAt(level, trackPos, dir, axis);
                if (loc != null) break outer;
            }
        }
        if (loc == null) return false;
        return railgrinding(player, loc, prePos, entryVelocity);
    }

    /**
     * Init the grind from a precomputed graph location. Lets bezier-curve clicks reuse the same
     * setup path as plain track-block clicks — Networking#handleTeleport resolves curve hits via
     * TrackGraphHelper.getBezierGraphLocationAt and hands the result here, so the grind starts
     * at the exact spot on the curve the player clicked instead of snapping to an endpoint.
     */
    public static boolean railgrinding(Player player, TrackGraphLocation loc, Vec3 prePos, double entryVelocity) {
        // Both railgrinding() overloads converge here, so this is the single defensive gate
        // for the post-dismount cooldown. Networking.handleTeleport also checks earlier, so
        // the teleport itself is suppressed during cooldown, not just the grind init.
        if (isPlayerOnRailGrindCooldown(player)) return false;
        // Same convergence-gate logic for the train-crush check: refuse to start a grind
        // while the player has been overlapping a carriage long enough to count as crushed.
        // The network handlers also check earlier (so we don't waste a teleport/state-setup
        // pass for a request that would fail here), but this is the canonical block.
        if (isPlayerCrushedByTrain(player)) return false;

        TrackGraph graph = loc.graph;
        Couple<TrackNode> nodes = loc.edge.map(graph::locateNode);
        TrackNode first = nodes.getFirst();
        TrackNode second = nodes.getSecond();
        if (first == null || second == null) return false;

        TrackEdge forwardEdge = graph.getConnectionsFrom(first).get(second);
        if (forwardEdge == null) return false;

        Vec3 chord = second.getLocation().getLocation().subtract(first.getLocation().getLocation());
        boolean forward = player.getLookAngle().dot(chord) >= 0;

        TrackEdge edge;
        if (forward) {
            edge = forwardEdge;
        } else {
            edge = graph.getConnectionsFrom(second).get(first);
            if (edge == null) {
                // Graph doesn't store the reverse direction for this edge — fall back to forward
                // so the player can still grind even when looking against the rail's natural axis.
                edge = forwardEdge;
                forward = true;
            }
        }

        TrackNode fromNode = forward ? first : second;
        TrackNode toNode = forward ? second : first;
        double position = forward ? loc.position : edge.getLength() - loc.position;

        GrindState gs = new GrindState(graph, fromNode, toNode, edge, position);
        // Carry the player's pre-grind momentum into the grind, capped at MAX_STEP rather
        // than TOP_SPEED. Below MAX_STEP, applyTickMotion never scales down the per-tick
        // velocity, so expectedPos stays on the parallel-offset curve and the player tracks
        // the rail bar exactly. Above it, the velocity scaler there puts expectedPos on the
        // straight chord between reference and target, drifting the player off the rail
        // through bezier turns. Letting currentSpeed start above TOP_SPEED makes the entry
        // boost *visible*: it decays smoothly down to TOP_SPEED via the accel/decel logic
        // in tick(), giving a speed-bleed feel after a sprint or fall onto the rail.
        gs.currentSpeed = Math.min(gs.currentSpeed + entryVelocity, MAX_STEP);

        // Pick which rail bar the player rides on. Dot the player's pre-teleport position
        // (relative to the spawn spline point) with the right-of-travel perpendicular: positive
        // → right side (+1), negative → left side (-1). Locked for the rest of the grind so
        // they don't snap from one rail bar to the other when the spline tangent rotates.
        double edgeLenForSpawn = edge.getLength();
        double tSpawn = edgeLenForSpawn <= 0 ? 0 : Math.min(1.0, Math.max(0.0, position / edgeLenForSpawn));
        Vec3 splineSpawn = edge.getPosition(graph, tSpawn);
        Vec3 dirSpawn = sampleTangent(graph, edge, tSpawn);
        Vec3 spawnChord = toNode.getLocation().getLocation().subtract(fromNode.getLocation().getLocation());
        if (dirSpawn.x * spawnChord.x + dirSpawn.z * spawnChord.z < 0) dirSpawn = dirSpawn.scale(-1);
        double horizLenSpawn = Math.sqrt(dirSpawn.x * dirSpawn.x + dirSpawn.z * dirSpawn.z);
        if (horizLenSpawn > 1e-6) {
            double rxSpawn = -dirSpawn.z / horizLenSpawn;
            double rzSpawn =  dirSpawn.x / horizLenSpawn;
            double sideDot = rxSpawn * (prePos.x - splineSpawn.x) + rzSpawn * (prePos.z - splineSpawn.z);
            gs.lateralSign = sideDot >= 0 ? +1.0 : -1.0;
        }

        ACTIVE.put(player.getUUID(), gs);
        // Creative flight + grind state collide and leave the player stuck flying after stop.
        if (player.getAbilities().flying) {
            player.getAbilities().flying = false;
            if (player instanceof ServerPlayer sp) sp.onUpdateAbilities();
        }
        // Elytra (fall-flying) overlaps the grind controller — gliding velocity fights the
        // per-tick snap and the player ends up doing the elytra pose on the rail. Kick them
        // out before we start driving motion ourselves. stopFallFlying() toggles flag 7
        // true→false to force the entity-data sync; only call it when actually fall-flying so
        // we don't spam shared-flag packets.
        if (player.isFallFlying()) player.stopFallFlying();
        player.setNoGravity(true);
        player.noPhysics = true;

        Vec3 spawn = worldPos(gs).add(0, Y_OFFSET, 0);
        player.setPos(spawn.x, spawn.y, spawn.z);
        // setPos only moves the server-side player. Sync the client too so it doesn't
        // start the grind from wherever Networking.handleTeleport's teleportTo landed.
        if (player instanceof ServerPlayer sp) {
            sp.connection.teleport(spawn.x, spawn.y, spawn.z, sp.getYRot(), sp.getXRot());
        }
        gs.expectedPos = spawn;  // seed for the per-tick velocity reference
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;

        syncPose(player, true);
        return true;
    }

    public static void stop(Player player) {
        if (ACTIVE.remove(player.getUUID()) == null) return;
        player.setNoGravity(false);
        player.noPhysics = false;
        FALL_IMMUNITY_UNTIL.put(player.getUUID(), player.level().getGameTime() + FALL_IMMUNITY_TICKS);
        // Stamp the post-grind cooldown. Decremented every server tick by tickCooldown();
        // blocks Networking.handleTeleport (no teleport-onto-rail) and railgrinding() (no
        // grind start) while the counter is > 0.
        START_COOLDOWN_REMAINING.put(player.getUUID(), START_COOLDOWN_TICKS);
        syncPose(player, false);
    }

    /**
     * True for FALL_IMMUNITY_TICKS after the most recent grind exit. ModEvents uses this to
     * cancel fall damage so dismounts off elevated rails — and the landing arc from a
     * stopWithLaunch — don't immediately kill the player. Map entries self-evict on the first
     * read past the expiry tick.
     */
    public static boolean hasFallImmunity(Player player) {
        Long until = FALL_IMMUNITY_UNTIL.get(player.getUUID());
        if (until == null) return false;
        if (player.level().getGameTime() >= until) {
            FALL_IMMUNITY_UNTIL.remove(player.getUUID());
            return false;
        }
        return true;
    }

    /**
     * True while the player's post-grind cooldown counter is still above zero. The counter
     * is set to {@link #START_COOLDOWN_TICKS} when {@link #stop(Player)} runs (any grind
     * exit) and decremented once per server tick by {@link #tickCooldown(Player)}. Both
     * grind-init routines read this to gate themselves: Networking.handleTeleport refuses
     * to teleport the player onto a rail while it returns true, and {@link #railgrinding}
     * refuses to start the ride. Returns false (allow grind) once the counter hits 0 and
     * the entry is evicted by tickCooldown.
     */
    public static boolean isPlayerOnRailGrindCooldown(Player player) {
        Integer remaining = START_COOLDOWN_REMAINING.get(player.getUUID());
        return remaining != null && remaining > 0;
    }

    /**
     * Decrement the player's post-grind cooldown counter by one, evicting the map entry
     * when it hits zero. Called every server tick from {@code ModEvents.onPlayerTick}, so
     * the counter ticks down for grinding *and* non-grinding players alike — the cooldown
     * is only ever non-zero immediately after a {@link #stop(Player)}, so a grinding
     * player's entry is already absent in steady state.
     */
    public static void tickCooldown(Player player) {
        Integer remaining = START_COOLDOWN_REMAINING.get(player.getUUID());
        if (remaining == null) return;
        int next = remaining - 1;
        if (next <= 0) {
            START_COOLDOWN_REMAINING.remove(player.getUUID());
        } else {
            START_COOLDOWN_REMAINING.put(player.getUUID(), next);
        }
    }

    /**
     * Tick the train-overlap crush counter: increment while the player's hitbox intersects
     * any {@link CarriageContraptionEntity}'s bounding box, reset to 0 the first tick the
     * intersection clears. The hard reset (rather than a slow decay) ensures a series of
     * momentary brush-bys can never accumulate into a kick — only one continuous overlap
     * counts toward the threshold.
     *
     * <p>Once the counter reaches {@link #TRAIN_OVERLAP_KICK_TICKS} and the player is
     * actively grinding, {@link #stop(Player)} drops them. The same threshold gates new
     * grind starts via {@link #isPlayerCrushedByTrain}.
     *
     * <p>Runs every server tick for every player (from {@code ModEvents.onPlayerTick}),
     * not just grinders, because the crush gate also blocks non-grinding players from
     * starting a grind: standing inside a parked carriage and trying to grind should fail
     * outright rather than snap onto the rail and immediately re-drop.
     */
    public static void tickTrainOverlap(Player player) {
        boolean overlapping = !player.level().getEntitiesOfClass(
            CarriageContraptionEntity.class, player.getBoundingBox()).isEmpty();

        if (overlapping) {
            int next = TRAIN_OVERLAP_TICKS.getOrDefault(player.getUUID(), 0) + 1;
            TRAIN_OVERLAP_TICKS.put(player.getUUID(), next);
            if (next >= TRAIN_OVERLAP_KICK_TICKS && isGrinding(player)) {
                stop(player);
            }
        } else {
            TRAIN_OVERLAP_TICKS.remove(player.getUUID());
        }
    }

    /**
     * True while the player has been overlapping a carriage's bounding box for at least
     * {@link #TRAIN_OVERLAP_KICK_TICKS} consecutive ticks. Read by both grind-init paths
     * (Networking.handleTeleport and handleStartFromNearest) and by {@link #railgrinding}
     * to refuse new grinds while the player is being crushed. Becomes false the instant
     * {@link #tickTrainOverlap} sees no overlap, since that path removes the counter entry.
     */
    public static boolean isPlayerCrushedByTrain(Player player) {
        Integer ticks = TRAIN_OVERLAP_TICKS.get(player.getUUID());
        return ticks != null && ticks >= TRAIN_OVERLAP_KICK_TICKS;
    }

    /**
     * Live count of consecutive ticks the player's hitbox has been overlapping a carriage
     * bounding box. 0 when there's no current overlap (counter reset). Debug-only accessor
     * used by the HUD; canonical readers use {@link #isPlayerCrushedByTrain} for the gate
     * semantics. The counter intentionally isn't capped at the kick threshold — leaving it
     * free-running surfaces "still stuck after the kick" as a visibly growing number on the
     * HUD instead of a silent saturation, and the HUD's "intersectingTrainAABB" bool is
     * just {@code count > 0}.
     */
    public static int getTrainOverlapTicks(Player player) {
        return TRAIN_OVERLAP_TICKS.getOrDefault(player.getUUID(), 0);
    }

    /**
     * Find the nearest rail spline point to {@code origin} within {@code maxDist} blocks,
     * using the same two resolvers the right-click empty-hand interaction uses:
     * <ul>
     *   <li>Plain {@link ITrackBlock} blocks → {@link TrackGraphHelper#getGraphLocationAt}
     *       (the call {@code railgrinding(BlockPos, …)} delegates to internally).</li>
     *   <li>Bezier curves on {@link TrackBlockEntity} → {@link TrackGraphHelper#getBezierGraphLocationAt}
     *       (the call {@code Networking.handleTeleport} makes for the right-click bezier branch).</li>
     * </ul>
     * Anything the right-click resolves, this resolves — the failure mode where some rails
     * worked and others didn't was a hand-rolled graph traversal that bypassed those helpers.
     *
     * <p>Plain blocks are picked up by a simple {@code BlockPos} scan in a small cube around
     * {@code origin}. Bezier curves are picked up by walking 3×3 chunks around the player and
     * iterating each loaded {@link TrackBlockEntity}'s {@link BezierConnection} segments —
     * curves whose endpoint TBE is in any of those chunks are covered, including curves that
     * bulge through the player's chunk while their endpoints sit one chunk over.
     */
    public static TrackGraphLocation findNearestRailLocation(Level level, Vec3 origin, double maxDist) {
        BlockPos center = BlockPos.containing(origin);
        double bestDistSq = maxDist * maxDist;
        TrackGraphLocation best = null;

        // Plain ITrackBlock proximity scan — same resolution path as the right-click empty-hand
        // block hit (Networking.findRailBlockAt → railgrinding(BlockPos) → TrackGraphHelper.getGraphLocationAt).
        int blockRange = (int) Math.ceil(maxDist);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -blockRange; dx <= blockRange; dx++) {
            for (int dy = -blockRange; dy <= blockRange; dy++) {
                for (int dz = -blockRange; dz <= blockRange; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof ITrackBlock track)) continue;
                    if (track.getMaterial().trackType != TrackMaterial.TrackType.STANDARD) continue;
                    double d2 = cursor.getCenter().distanceToSqr(origin);
                    if (d2 < bestDistSq) {
                        BlockPos pos = cursor.immutable();
                        TrackGraphLocation loc = resolvePlainTrack(level, pos, state, track);
                        if (loc != null) {
                            bestDistSq = d2;
                            best = loc;
                        }
                    }
                }
            }
        }

        // Bezier proximity scan — same resolution path as the right-click empty-hand bezier
        // hit (TrackGraphHelper.getBezierGraphLocationAt). Iterate 3×3 chunks around the player
        // for TrackBlockEntity instances; for each connection sample every segment of the curve
        // and keep the closest within range.
        int chunkX = SectionPos.blockToSectionCoord(center.getX());
        int chunkZ = SectionPos.blockToSectionCoord(center.getZ());
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                ChunkAccess chunk = level.getChunk(chunkX + cx, chunkZ + cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockPos bePos : chunk.getBlockEntitiesPos()) {
                    BlockEntity be = level.getBlockEntity(bePos);
                    if (!(be instanceof TrackBlockEntity tbe)) continue;
                    for (Map.Entry<BlockPos, BezierConnection> entry : tbe.getConnections().entrySet()) {
                        BezierConnection conn = entry.getValue();
                        int segCount = conn.getSegmentCount();
                        for (int seg = 0; seg < segCount; seg++) {
                            float t = conn.getSegmentT(seg);
                            Vec3 p = conn.getPosition(t);
                            double d2 = p.distanceToSqr(origin);
                            if (d2 >= bestDistSq) continue;
                            BezierTrackPointLocation btpl = new BezierTrackPointLocation(entry.getKey(), seg);
                            TrackGraphLocation loc = TrackGraphHelper.getBezierGraphLocationAt(
                                    level, bePos, Direction.AxisDirection.POSITIVE, btpl);
                            if (loc == null) loc = TrackGraphHelper.getBezierGraphLocationAt(
                                    level, bePos, Direction.AxisDirection.NEGATIVE, btpl);
                            if (loc != null) {
                                bestDistSq = d2;
                                best = loc;
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    /** Identical to the lookup loop in {@code railgrinding(BlockPos, …)} — try every track axis × axis-direction until {@link TrackGraphHelper#getGraphLocationAt} returns a non-null location. */
    private static TrackGraphLocation resolvePlainTrack(Level level, BlockPos pos, BlockState state, ITrackBlock track) {
        for (Vec3 axis : track.getTrackAxes(level, pos, state)) {
            for (Direction.AxisDirection dir : Direction.AxisDirection.values()) {
                TrackGraphLocation loc = TrackGraphHelper.getGraphLocationAt(level, pos, dir, axis);
                if (loc != null) return loc;
            }
        }
        return null;
    }

    /**
     * Stops the grind and gives the player a launch impulse along the rail's tangent at their
     * current position, plus a vertical boost. Both components scale with the player's grind
     * speed at the moment of dismount, so jumping off at TOP_SPEED produces a long, high arc
     * while jumping off near MIN_SPEED is barely more than a vanilla jump.
     *
     * Tangent-based direction means uphill rails launch up-and-forward, downhill rails launch
     * forward-and-slightly-down (skater-off-a-ramp feel) — the vertical base is added on top so
     * even modest grinds always net some upward velocity unless the rail is steeply descending.
     */
    public static void stopWithLaunch(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) {
            stop(player);
            return;
        }

        // Snapshot speed and travel-direction tangent before stop() removes the state.
        double speed = gs.currentSpeed;
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 tangent = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);

        double horizMag = speed * LAUNCH_HORIZONTAL_MULT;
        double vertBoost = LAUNCH_VERTICAL_BASE + speed * LAUNCH_VERTICAL_SCALE;
        Vec3 launch = new Vec3(
            tangent.x * horizMag,
            tangent.y * horizMag + vertBoost,
            tangent.z * horizMag
        );

        stop(player);
        player.setDeltaMovement(launch);
        player.hurtMarked = true;  // forces a velocity packet to the client so the launch isn't predicted away
        player.fallDistance = 0.0F;
    }

    public static boolean isGrinding(Player player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    /** Server-authoritative grind speed in blocks/tick, or 0 if the player isn't grinding. */
    public static double getCurrentSpeed(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs == null ? 0.0 : gs.currentSpeed;
    }

    /**
     * Debug-only frame for the local player's current grind, or null if not grinding.
     * - origin: world-space spline centerline point (rail block axis).
     * - tangent: travel-aligned tangent at that point.
     * - snapTarget: world-space position the player is being snapped to this tick — the same
     *   value applyTickMotion() drives the player toward, so the player's actual position lags
     *   by 1+ ticks and won't match it exactly.
     */
    public record GrindFrame(Vec3 origin, Vec3 tangent, Vec3 snapTarget) {}

    public static GrindFrame getGrindFrame(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return null;
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 pos = gs.edge.getPosition(gs.graph, t);
        Vec3 dir = sampleTangent(gs.graph, gs.edge, t);
        // Defensive: if the edge's parameterization runs opposite to from→to, flip so the
        // tangent always points in the direction of travel.
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (dir.x * chord.x + dir.z * chord.z < 0) dir = dir.scale(-1);
        Vec3 snap = worldPos(gs).add(0, Y_OFFSET, 0);
        return new GrindFrame(pos, dir, snap);
    }

    /** Live snapshot of a grind's per-tick non-constants. Debug only. Returns null if the player is not grinding. */
    public record GrindDebugInfo(
        double currentSpeed,
        double targetSpeed,
        double acceleration,
        double topSpeed,
        double experiencedSlope,
        double position,
        double edgeLength,
        int stuckTicks,
        int totalTicks,
        double lateralSign,
        boolean edgeIsTurn,
        boolean shiftHeld,
        boolean collidingWithTrain
    ) {}

    public static GrindDebugInfo getGrindDebugInfo(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return null;
        return new GrindDebugInfo(
            gs.currentSpeed,
            computeTargetSpeed(gs, player),
            computeAcceleration(gs, player),
            TOP_SPEED,
            gs.experiencedSlope,
            gs.position,
            gs.edge.getLength(),
            gs.stuckTicks,
            gs.totalTicks,
            gs.lateralSign,
            gs.edge.isTurn(),
            player.isShiftKeyDown(),
            gs.collidingWithTrain
        );
    }

    /**
     * Local tangent at parameter t along an edge. TrackEdge.getDirectionAt(t) returns the
     * constant from→to chord on bezier edges (it's not the bezier derivative), which would
     * make any rotation derived from it freeze for the entire curve and snap at edge
     * boundaries. A centered finite difference of getPosition(t) tracks the curve smoothly
     * — same approach Create's TravellingPoint uses for bogey orientation.
     *
     * Sample points are clamped to [0, 1] so calls near the endpoints fall back to a one-
     * sided difference instead of reading off the edge.
     */
    private static Vec3 sampleTangent(TrackGraph graph, TrackEdge edge, double t) {
        final double eps = 0.001;
        double t0 = Math.max(0.0, t - eps);
        double t1 = Math.min(1.0, t + eps);
        if (t1 - t0 < 1e-9) return edge.getDirectionAt(t);
        Vec3 diff = edge.getPosition(graph, t1).subtract(edge.getPosition(graph, t0));
        double len = diff.length();
        if (len < 1e-9) return edge.getDirectionAt(t);
        return diff.scale(1.0 / len);
    }

    public static void tick(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;
        gs.totalTicks++;


        if (player.level() instanceof ServerLevel sl) {
            double speedRatio = Math.min(2.0, gs.currentSpeed / TOP_SPEED);
            // Below 25% max speed → no particles. 25–50% → white, 50–75% → yellow, 75%+ → red.
            if (speedRatio >= 0.25) {
                Vec3 feet = player.position();
                DustParticleOptions spark =
                    speedRatio >= 0.75 ? RED_SPARK
                    : speedRatio >= 0.50 ? YELLOW_SPARK
                    : WHITE_SPARK;
                int count = 1 + (int) Math.round(speedRatio * 4);
                double spread = 0.10 + speedRatio * 0.12;
                sl.sendParticles(spark, feet.x, feet.y + 0.05, feet.z, count, spread, 0.05, spread, 0.0);
            }
        }

        // Re-assert no-collision flags every tick. If anything (pose change, dismount logic,
        // a vanilla branch, or a network desync) flips noPhysics back to false even briefly,
        // the player would clip into the dirt/stone under the rail on slopes — which is
        // exactly the Y-jitter symptom. Same for noGravity. Cheap to set; safe to repeat.
        if (!player.noPhysics)     player.noPhysics = true;
        if (!player.isNoGravity()) player.setNoGravity(true);
        // Same defensive re-assert for elytra: if the fall-flying flag flips back on mid-grind
        // (mod, network desync, vanilla auto-deploy on a future tick), the gliding controller
        // fights our snap and the player T-poses through the rail.
        if (player.isFallFlying()) player.stopFallFlying();
        // onGround left over from before the grind triggers vanilla step-up / sneak / fall
        // handling that nudges Y even with noPhysics on. Force-clear it.
        player.setOnGround(false);

        // Sample the slope the player actually traversed last tick (Δy / Δdistance, in 3D).
        // Falls back to 0 on the first tick or when the player didn't move. The same
        // displacement length doubles as the player's absolute velocity for stuck detection
        // in applyTickMotion — see the comment there.
        Vec3 currentPos = player.position();
        double absVelocity = 0.0;
        if (gs.prevPos != null) {
            Vec3 motion = currentPos.subtract(gs.prevPos);
            absVelocity = motion.length();
            if (absVelocity > 1e-4) gs.experiencedSlope = motion.y / absVelocity;
        }
        gs.prevPos = currentPos;

        double targetSpeed = computeTargetSpeed(gs, player);
        double accel = computeAcceleration(gs, player);

        if (gs.currentSpeed < targetSpeed)
            gs.currentSpeed = Math.min(gs.currentSpeed + accel, targetSpeed);
        else if (gs.currentSpeed > targetSpeed)
            gs.currentSpeed = Math.max(gs.currentSpeed - accel, targetSpeed);

        if (gs.currentSpeed <= 1e-6) {
            applyTickMotion(player, gs, absVelocity);
            return;
        }

        double remaining = gs.currentSpeed;
        int safety = 256;
        while (remaining > 1e-6 && safety-- > 0) {
            double edgeLen = gs.edge.getLength();
            double room = edgeLen - gs.position;
            if (room > remaining) {
                gs.position += remaining;
                remaining = 0;
            } else {
                remaining -= Math.max(room, 0);
                gs.position = edgeLen;
                if (!advanceJunction(gs, player)) {
                    stop(player);
                    return;
                }
            }
        }

        applyTickMotion(player, gs, absVelocity);
        checkTrainCollision(player, gs);
    }

    /**
     * Detect the player's hitbox intersecting any bogey's AABB and damage them. The
     * intersection itself is what {@link GrindState#collidingWithTrain} reports — the
     * debug bool flips on AABB-vs-AABB overlap, independent of the speed gate that decides
     * whether damage actually lands.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>{@code level.getEntitiesOfClass(CarriageContraptionEntity.class, …)} around
     *       {@code player.getBoundingBox().inflate(TRAIN_SEARCH_PADDING)} — same spatial
     *       lookup Create uses, just from the opposite side. The inflation absorbs a few
     *       ticks of train approach so a fast oncoming carriage can't enter the player's
     *       space between checks.</li>
     *   <li>For each carriage, build an AABB around each bogey from its two axle
     *       {@link TravellingPoint}s ({@link CarriageBogey#leading()} /
     *       {@link CarriageBogey#trailing()}), inflated by {@link #BOGEY_AABB_INFLATE} to
     *       give the line-pair real volume.</li>
     *   <li>If {@code player.getBoundingBox().intersects(bogeyAABB)} for any bogey, flag
     *       {@code gs.collidingWithTrain = true}.</li>
     *   <li>Then mirror Create's {@code diffMotion = trainMotion − entityMotion} using
     *       {@code cce.getDeltaMovement()} vs {@code player.getDeltaMovement()}, gate on
     *       {@code |diffMotion| > 0.35} (Create's threshold), and deal {@code relSpeed × 10}
     *       damage via {@link #FLATTENED_DAMAGE}. ModEvents skips its speed×10 multiplier
     *       for this source so the calibrated amount lands. Vanilla hurt I-frames throttle
     *       sustained overlap.</li>
     * </ol>
     */
    private static void checkTrainCollision(Player player, GrindState gs) {
        gs.collidingWithTrain = false;

        AABB playerBounds = player.getBoundingBox();
        List<CarriageContraptionEntity> nearby = player.level().getEntitiesOfClass(
            CarriageContraptionEntity.class, playerBounds.inflate(TRAIN_SEARCH_PADDING));
        if (nearby.isEmpty()) return;

        boolean damageImmune = player.isCreative() || player.isSpectator();

        for (CarriageContraptionEntity cce : nearby) {
            Carriage carriage = cce.getCarriage();
            if (carriage == null) continue;

            // Two bogeys per carriage; the second is null on single-bogey carriages.
            if (!intersectsBogeyAABB(playerBounds, carriage.bogeys.getFirst(), gs.graph)
                && !intersectsBogeyAABB(playerBounds, carriage.bogeys.getSecond(), gs.graph))
                continue;

            gs.collidingWithTrain = true;
            // Creative/spectator still surface the intersection in the debug HUD, just no hit.
            if (damageImmune) return;

            Train train = carriage.train;
            if (train == null) return;

            // diffMotion in Create's terms: carriage motion minus player motion.
            // Stationary-train + grinding-player and stationary-player + moving-train both
            // produce a real relSpeed; same-direction-same-speed produces ≈0.
            Vec3 trainVel = cce.getDeltaMovement();
            Vec3 playerVel = player.getDeltaMovement();
            double relSpeed = trainVel.subtract(playerVel).length();

            // 0.35 b/t gate matches Create's handleDamageFromTrain — exempts parked and
            // slow-coasting carriages even on overlap. CRUISE_SPEED grinding into a parked
            // train won't damage; anything from ~0.35 b/t up will.
            if (relSpeed < 0.35) return;

            float damage = (float) (relSpeed * 10.0);
            Holder<DamageType> dt = player.level().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(FLATTENED_DAMAGE);
            player.hurt(new DamageSource(dt), damage);
            return;
        }
    }

    /** True iff {@code target} intersects an AABB built from the bogey's two axle TravellingPoints inflated by {@link #BOGEY_AABB_INFLATE}. False on null bogey or unsettled axles. */
    private static boolean intersectsBogeyAABB(AABB target, CarriageBogey bogey, TrackGraph graph) {
        if (bogey == null) return false;
        TravellingPoint leading = bogey.leading();
        TravellingPoint trailing = bogey.trailing();
        if (leading == null || leading.edge == null
            || trailing == null || trailing.edge == null) return false;
        Vec3 a = leading.getPosition(graph);
        Vec3 b = trailing.getPosition(graph);
        return target.intersects(new AABB(a, b).inflate(BOGEY_AABB_INFLATE));
    }

    private static double computeTargetSpeed(GrindState gs, Player player) {
        double base = player.isShiftKeyDown() ? TOP_SPEED : CRUISE_SPEED;

        // Asymmetric slope scaling: descents lift the cap (DOWNHILL_FACTOR), ascents cut it (UPHILL_FACTOR).
        double slope = gs.experiencedSlope;  // +up / -down
        double factor = slope < 0 ? DOWNHILL_FACTOR : UPHILL_FACTOR;
        base *= Math.max(0.0, 1.0 - slope * factor);

        if (gs.edge.isTurn()) base *= CURVE_FACTOR;

        return Math.max(MIN_SPEED, base);
    }

    private static double computeAcceleration(GrindState gs, Player player) {
        double base = player.isShiftKeyDown() ? ACCELERATION * BOOST_ACCEL_MULT : ACCELERATION;
        double slope = gs.experiencedSlope;
        if (slope < 0) base *= 1.0 + (-slope) * (DOWNHILL_ACCEL_BOOST - 1.0);
        return base;
    }

    private static void applyTickMotion(Player player, GrindState gs, double absVelocity) {
        Vec3 target = worldPos(gs).add(0, Y_OFFSET, 0);

        // Velocity reference is gs.expectedPos (last tick's parallel-curve target), not
        // player.position(). Reason: target lives on the parallel offset curve, and any
        // residual sync lag in player.position() means (target - playerPos) is a chord that
        // crosses the curve rather than running along it. The lateral component of that chord
        // scales linearly with LATERAL_OFFSET, so the over-correction stays invisible at
        // small offsets (e.g. 0.3125) but turns into visible per-tick jitter once the offset
        // gets to a full block. Using expectedPos makes the per-tick velocity equal the
        // parallel-curve tangent itself — independent of LATERAL_OFFSET magnitude.
        //
        // Realignment (soft, continuous). Instead of a binary threshold that toggled the
        // velocity reference between expectedPos and playerPos — which produced violent
        // jitter on curves whenever drift hovered near the threshold — we lerp expectedPos a
        // small fraction toward playerPos every tick. In steady state playerPos ≈ expectedPos
        // (server's view of the player has no lag because the client report from the previous
        // tick already reflects what we told it to do), so the contribution is ≈0 and the
        // smooth-tangent path is preserved. When real drift occurs it's proportional and
        // ramps up smoothly, and the existing MAX_STEP cap on `velocity` keeps even a strong
        // pull bounded so a physical obstruction still throttles absVelocity below
        // STUCK_VELOCITY_THRESHOLD and the stuck-tick check drops the grind.
        Vec3 playerPos = player.position();
        // Catastrophic-desync bailout: at MAX_DRIFT blocks past target, the soft pull can't
        // recover in any reasonable time — gs.position has run far ahead of where the player
        // physically is. Drop unconditionally (skips STUCK_GRACE_TICKS), no launch impulse —
        // error recovery, not a deliberate dismount.
        if (playerPos.subtract(target).lengthSqr() > MAX_DRIFT * MAX_DRIFT) {
            stop(player);
            return;
        }
        Vec3 reference = gs.expectedPos != null
                ? gs.expectedPos.lerp(playerPos, REALIGN_GAIN)
                : playerPos;
        Vec3 velocity = target.subtract(reference);
        double speedSq = velocity.lengthSqr();

        // Cap per-tick velocity so the player smoothly catches up instead of snapping
        if (speedSq > MAX_STEP * MAX_STEP) {
            velocity = velocity.scale(MAX_STEP / Math.sqrt(speedSq));
        }
        player.setDeltaMovement(velocity.x, velocity.y, velocity.z);
        player.hurtMarked = true;
        player.fallDistance = 0.0F;

        // Advance the reference along the parallel curve. If velocity got capped, expectedPos
        // intentionally lags target rather than jumping ahead — next tick's chord picks up
        // from where the player actually went.
        gs.expectedPos = reference.add(velocity);

        // Stuck detection: drop the grind if the player's actual per-tick displacement is
        // below STUCK_VELOCITY_THRESHOLD — i.e., they aren't moving despite our setDeltaMovement.
        // The previous distance-based check (target − player.position) grew unboundedly any
        // time currentSpeed exceeded MAX_STEP: gs.position advanced by currentSpeed each tick
        // but the velocity cap held the player to MAX_STEP, so the gap widened by
        // (currentSpeed − MAX_STEP) per tick and falsely tripped stuck detection on legitimate
        // high-speed grinds. Velocity-based detection only fires for the actual stuck condition
        // (mod yanking the player, physics overriding setDeltaMovement, etc.).
        // Grace window at start: client noPhysics syncs ~1 tick after grind begins, and the
        // local player's collision pass clamps movement until the sync arrives. Skip stuck
        // detection during that window so the player isn't dropped before noPhysics lands.
        if (gs.totalTicks <= STUCK_GRACE_TICKS) {
            gs.stuckTicks = 0;
        } else if (absVelocity < STUCK_VELOCITY_THRESHOLD) {
            gs.stuckTicks++;
            if (gs.stuckTicks >= STUCK_DROP_TICKS) {
                stop(player);
            }
        } else {
            gs.stuckTicks = 0;
        }
    }

    private static Vec3 worldPos(GrindState gs) {
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 pos = gs.edge.getPosition(gs.graph, t);

        // Lateral offset from spline centerline to the rail bar. Uses the smooth bezier-aware
        // tangent so the perpendicular rotates correctly through curves — using
        // getDirectionAt(t) here would lock the perpendicular to the chord on bezier edges,
        // making the player visually drift off the rail bar through any turn.
        Vec3 dir = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (dir.x * chord.x + dir.z * chord.z < 0) dir = dir.scale(-1);
        double horizLen = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (horizLen < 1e-6) return pos;
        double off = LATERAL_OFFSET * gs.lateralSign;
        double rx = -dir.z / horizLen * off;
        double rz =  dir.x / horizLen * off;
        return pos.add(rx, 0, rz);
    }

    /**
     * Pick the next edge at an intersection — same algorithm Create's
     * {@code TravellingPoint.steer(SteerDirection, Vec3)} uses for player-controlled trains,
     * and same candidate-list filtering Create's {@code TravellingPoint.travel} applies before
     * passing the list to the selector.
     *
     * <p>Two-stage selection:
     * <ol>
     *   <li><b>Filter</b>: skip the back-edge (neighbor we came from) and any candidate whose
     *       start tangent isn't continuous with the current end tangent — the latter via
     *       {@link TrackEdge#canTravelTo(TrackEdge)}, which returns true only when
     *       {@code currentDir · candidateDir > 0.875} (≈ cos 29°). This is the same gate Create's
     *       {@code TravellingPoint.travel} applies (see its inner loop calling
     *       {@code this.edge.canTravelTo(candidate)} before adding to the option list). It's
     *       what stops the player from picking a perpendicular exit at a 4-way crossing or
     *       jumping to the other branch at a Y-merge — both would require physically reversing,
     *       which trains can't do here either.</li>
     *   <li><b>Pick</b>: among the survivors, compute the lateral signed projection
     *       {@code dot = (currentDir × up) · candidateDir} (straight ≈ 0, left negative, right
     *       positive) and pick whichever is closest to {@code targetDot = steerSign}
     *       (−1 / 0 / +1). targetDot = 0 reproduces the "go straightest" behavior; ±1 lets the
     *       player pick the leftmost / rightmost branch at a Y-fork.</li>
     * </ol>
     *
     * <p>{@code currentDir} is the tangent at the END of the edge we're leaving
     * ({@code gs.edge.getDirection(false)}, where {@code false} means the node2 side =
     * {@code gs.toNode}). Each candidate's {@code candidateDir} is its tangent at the
     * START ({@code getDirection(true)} = node1 side = {@code atNode}).
     */
    private static boolean advanceJunction(GrindState gs, Player player) {
        TrackNode atNode = gs.toNode;
        TrackNode previousFrom = gs.fromNode;

        Map<TrackNode, TrackEdge> connections = gs.graph.getConnectionsFrom(atNode);
        if (connections == null || connections.isEmpty()) return false;

        Vec3 currentDir = gs.edge.getDirection(false);
        Vec3 cross = currentDir.cross(new Vec3(0, 1, 0));
        double targetDot = gs.steerSign;

        TrackNode bestNeighbor = null;
        TrackEdge bestEdge = null;
        double bestDiff = Double.MAX_VALUE;

        for (Map.Entry<TrackNode, TrackEdge> entry : connections.entrySet()) {
            TrackNode neighbor = entry.getKey();
            if (neighbor == previousFrom) continue;
            TrackEdge candidate = entry.getValue();
            // Forward-continuity filter (cos 29° threshold). See javadoc above.
            if (!gs.edge.canTravelTo(candidate)) continue;
            Vec3 candidateDir = candidate.getDirection(true);
            double dot = cross.dot(candidateDir);
            double diff = Math.abs(targetDot - dot);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestNeighbor = neighbor;
                bestEdge = candidate;
            }
        }

        // No forward continuation → end of track (or only perpendicular / reverse exits exist).
        // Same dropout behavior as before: returning false causes tick() to call stop(player)
        // cleanly rather than reversing. Mirrors TravellingPoint setting blocked=true and
        // Train.tick() halting.
        if (bestEdge == null) return false;

        gs.fromNode = atNode;
        gs.toNode = bestNeighbor;
        gs.edge = bestEdge;
        gs.position = 0;
        return true;
    }

    private static void syncPose(Player player, boolean grinding) {
        if (player.level().isClientSide) {
            BalancingPoseTracker.setBalancing(player.getUUID(), grinding);
        } else if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                serverPlayer, new RailGrindSyncPayload(player.getUUID(), grinding));
        }
    }
}
