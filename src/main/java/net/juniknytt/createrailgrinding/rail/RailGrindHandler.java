package net.juniknytt.createrailgrinding.rail;

import com.simibubi.create.Create;
import com.simibubi.create.api.contraption.train.PortalTrackProvider;
import com.simibubi.create.content.kinetics.chainConveyor.ServerChainConveyorHandler;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
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
import net.createmod.catnip.math.BlockFace;
import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.compat.Mods;
import net.juniknytt.createrailgrinding.compat.SableSubLevels;
import net.juniknytt.createrailgrinding.effect.ModEffects;
import net.juniknytt.createrailgrinding.network.GrindAccelInputPayload;
import net.juniknytt.createrailgrinding.network.RailGrindDebugSyncPayload;
import net.juniknytt.createrailgrinding.network.RailGrindLeanSyncPayload;
import net.juniknytt.createrailgrinding.network.RailGrindSyncPayload;
import net.juniknytt.createrailgrinding.network.RailGrindTargetPayload;
import net.juniknytt.createrailgrinding.particle.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RailGrindHandler {
    private static final Map<UUID, GrindState> ACTIVE = new ConcurrentHashMap<>();
    /** Client-side mirror of the local player's slope. Only the local player's value is synced; remote grinders read the local value (acceptable, see PlayerNoPhysicsTickMixin). */
    public static volatile double clientLocalSlope = 0.0;
    private static final Map<UUID, Long> FALL_DAMAGE_IMMUNITY_TIME = new ConcurrentHashMap<>();
    private static final int FALL_IMMUNITY_TICKS = 25;
    private static final Map<UUID, Integer> START_COOLDOWN_REMAINING = new ConcurrentHashMap<>();
    private static final int START_COOLDOWN_TICKS = 10;
    // Counter resets to 0 (entry removed) the first tick overlap clears, so brush-bys can't accumulate.
    private static final Map<UUID, Integer> TRAIN_OVERLAP_TICKS = new ConcurrentHashMap<>();
    private static final int TRAIN_OVERLAP_KICK_TICKS = 5;
    // Wrapped in a method so HUD/sound pick up live config changes; falls back to default before SERVER spec syncs.
    public static double topSpeed() {
        return Config.SERVER_SPEC.isLoaded()
                ? Config.TOP_GRIND_SPEED.get()
                : Config.TOP_GRIND_SPEED.getDefault();
    }
    private static final double CRUISE_SPEED = 0.20;
    private static final double ACCELERATION = 0.005;
    // Fraction of remaining target-gap currentSpeed closes per tick; per-tick step still capped at ACCELERATION × boosts.
    private static final double SPEED_EASE_RATE = 0.08;
    // Low-pass filter on raw target to absorb slope-flicker between cruise and walk pace on curves.
    private static final double TARGET_SMOOTH_RATE = 0.18;
    private static final double DOWNHILL_FACTOR = 0.9;
    private static final double UPHILL_FACTOR = 1.5;
    private static final double DOWNHILL_CRUISE_MIN_FRACTION = 0.75;
    private static final double DOWNHILL_CRUISE_MAX_FRACTION = 1.00;
    private static final double DOWNHILL_ACCEL_BOOST = 5.0;
    private static final double CURVE_FACTOR = 0.75;
    // Asymmetric EMA: enter turns faster than exit, so S-bends don't bounce the smoothed factor back to 1.0 between turns.
    private static final double CURVE_FACTOR_ENTER_RATE = 0.05;
    private static final double CURVE_FACTOR_EXIT_RATE  = 0.02;
    // Half-width in t-space of the tangent finite-difference used to derive experiencedCurve.
    private static final double CURVE_SAMPLE_EPSILON = 0.05;
    // Gain on raw sin(angle) before clamping to ±1; tuned so 90° beziers register strongly without saturating gentle curves.
    private static final double CURVE_SIGNAL_GAIN = 5.0;
    private static final double CURVE_SMOOTH_RATE = 0.15;
    private static final double MIN_SPEED = 0.10;
    // Fluid drag factors. OTHER covers unknown mod fluids (treated water-like); when straddling fluids, slowest factor wins.
    private static final double WATER_FLUID_FACTOR = 0.5;
    private static final double LAVA_FLUID_FACTOR = 0.25;
    private static final double OTHER_FLUID_FACTOR = 0.5;
    // Depth strider eases drag linearly from fluid factor up to FLOOR at FULL_LEVEL; higher levels don't push further.
    private static final double DEPTH_STRIDER_FLUID_FLOOR = 0.8;
    private static final int DEPTH_STRIDER_FLUID_FULL_LEVEL = 3;
    private static final double Y_OFFSET = 0.5;
    // Standard-gauge rail-bar offset; per-track-type override lives in GrindState.lateralOffset.
    private static final double LATERAL_OFFSET = 1.0;
    // Steam'n'Rails track-type IDs. Matched via material.trackType.id so the mod can be absent without breaking class-load.
    private static final ResourceLocation RAILWAYS_NARROW_GAUGE = ResourceLocation.fromNamespaceAndPath("railways", "narrow_gauge");
    private static final ResourceLocation RAILWAYS_WIDE_GAUGE   = ResourceLocation.fromNamespaceAndPath("railways", "wide_gauge");
    private static final ResourceLocation RAILWAYS_MONORAIL     = ResourceLocation.fromNamespaceAndPath("railways", "monorail");
    // Universal track is compatible with every gauge; mirrored in advanceJunction's gauge gate.
    private static final ResourceLocation RAILWAYS_UNIVERSAL    = ResourceLocation.fromNamespaceAndPath("railways", "universal");
    private static final double LATERAL_OFFSET_NARROW = LATERAL_OFFSET - 0.5;
    private static final double LATERAL_OFFSET_WIDE   = LATERAL_OFFSET + 0.5;
    // Monorail beam runs down the centerline (no left/right bar to pick).
    private static final double LATERAL_OFFSET_MONORAIL = 0.0;
    // Monorail beam is full block tall, so player hovers one block above the nodegraph.
    private static final double Y_OFFSET_MONORAIL = 1.1;
    // Extra start-trigger reach for wide gauge, matching the half-block outboard offset of the wide rail bar.
    private static final double WIDE_GAUGE_START_RADIUS_BONUS = 0.5;
    // Entry-velocity carry cap (~40 m/s); per-tick motion cap proper lives client-side in RailGrindClientMotion.
    private static final double MAX_STEP = 2.0;
    // Half-extents of the rendered snap-target box (local frame: +Z = travel-aligned, +X = right-of-travel, +Y = up). Visualization-only.
    public static final double SNAP_BOX_HALF_W = 0.15;
    public static final double SNAP_BOX_HALF_H = 0.15;
    public static final double SNAP_BOX_HALF_L = 0.40;
    // Anti-cheat tripwire (loose). Primary obstacle detection is client-side via BlockedByObstaclePayload; this only fires on catastrophic desync.
    private static final double MAX_DRIFT = 20.0;
    /** Above this |slope|, PlayerNoPhysicsTickMixin re-asserts noPhysics so the player phases through rail supports. Below it, vanilla collision applies (snags on supports only happen while moving up/down). 0.25 ≈ sin(14.5°). */
    public static final double NO_PHYSICS_SLOPE_THRESHOLD = 0.25;
    /** |slope| above which a tick counts toward EXTREME_SLOPE drop (Sable only). 0.85 ≈ sin(58°). */
    private static final double EXTREME_SLOPE_THRESHOLD = 0.85;
    /** Consecutive over-threshold ticks needed to fire EXTREME_SLOPE; transient spikes release within ~2 ticks. */
    private static final int EXTREME_SLOPE_DROP_TICKS = 10;
    private static final double STUCK_VELOCITY_THRESHOLD = 0.05;
    // Anti-cheat tripwire. Primary obstacle detection is client-side; 30 ticks is generous enough to survive cross-dim anchor-release gaps.
    private static final int STUCK_DROP_TICKS = 30;
    private static final int STUCK_GRACE_TICKS = 3;
    // Base startup grace; railgrinding() seeds gs.startGraceTicks = START_GRACE_TICKS + (latency_ms / 50). Suppresses MAX_DRIFT and stuck (NOT train-overlap), and defers noPhysics=true server-side so vanilla collision bounds drift while client EMA-converges. Not used by cross-dim reattach (uses reattachGraceTicks).
    private static final int START_GRACE_TICKS = 10;
    // Cross-dim re-attach grace: trigger-based with client ack (CrossDimGraceReleasePayload collapses counter to AFTER_ACK_TICKS). Base 100 ticks is timeout failsafe; ack normally ends grace early. Latency adds REATTACH_GRACE_LATENCY_MULT ticks per tick of one-way latency. NOT seeded by normal starts.
    private static final int REATTACH_GRACE_BASE_TICKS = 100;
    private static final int REATTACH_GRACE_AFTER_ACK_TICKS = 2;
    // Post-reattach kick suppression seeded at grace end. Bridges the gap between server anchor release and first MovePlayer C2S: STUCK is short-circuited, gs.position is capped against running > POST_REATTACH_DRIFT_CAP ahead of sp.position projected on rail. Reactive MAX_DRIFT → snapGsToPlayer remains as safety net.
    private static final int POST_REATTACH_KICK_SUPPRESS_BASE_TICKS = 60;
    private static final int POST_REATTACH_KICK_SUPPRESS_LATENCY_MULT = 10;
    // Max blocks gs.position may lead sp.position (projected on edge tangent) during kick-suppress. Bounded well under MAX_DRIFT; reused as rubber-band distance.
    private static final double POST_REATTACH_DRIFT_CAP = 5.0;
    // extra_ticks = latency_ms * REATTACH_GRACE_LATENCY_MULT / 50. 30 = 30 ticks of grace per tick of one-way latency.
    private static final int REATTACH_GRACE_LATENCY_MULT = 30;
    private static final double LAUNCH_HORIZONTAL_MULT = 2.0;
    private static final double LAUNCH_VERTICAL_BASE = 0.42;
    private static final double LAUNCH_VERTICAL_SCALE = 0.6;
    // Jump-trick charge window. Client tracks jump-key hold time, server clamps to [MIN, MAX] and turns into 0..1 ratio.
    public  static final int JUMP_TRICK_CHARGE_INPUT_TIME_MIN = 0;
    public  static final int JUMP_TRICK_CHARGE_INPUT_TIME_MAX = 20;
    // Charge bonus is additive on top of speed-based launch; ratio 0 keeps speed-only behavior identical.
    private static final double LAUNCH_CHARGE_HORIZONTAL_BONUS_MULT = 1.0;
    private static final double LAUNCH_CHARGE_VERTICAL_BONUS_MULT   = 1.0;
    // Cross-dim portal-exit rail search radius. Larger than the default near-rail snap (1.75) so users get slack placing the matching rail near the exit frame.
    private static final double PORTAL_REGRIND_SEARCH_RANGE = 8.0;
    // Post-transit portal cooldown after Path 1 cross-dim teleport, preventing vanilla from sending the player back through the exit portal.
    private static final int PORTAL_REGRIND_COOLDOWN_TICKS = 80;
    // Our own post-transit gate. Vanilla's portalCooldown is too short (10 ticks) when the exit lands on/beside the matching frame; this is not reset by vanilla.
    private static final Map<UUID, Integer> PORTAL_TRANSIT_COOLDOWN = new ConcurrentHashMap<>();
    private static final int PORTAL_TRANSIT_COOLDOWN_TICKS = 40;

    // Drained by tickPendingRegrind once destination chunks load and min-wait elapses (lets client's Clone fire before grind re-sync, avoids noPhysics race).
    private static final Map<UUID, PendingRegrind> PENDING_REGRIND = new ConcurrentHashMap<>();
    private static final int PENDING_REGRIND_MIN_WAIT_TICKS = 5;
    // Hard timeout — releases physics flags so player isn't permanently frozen if chunk gate never opens.
    private static final int PENDING_REGRIND_TIMEOUT_TICKS = 100;

    // ─── Grind particle tuning ────────────────────────────────────────────────
    // <25% speed → no particles; 25–50% → crit only; 50%+ → crit + spark.
    // Cadence (interval) and count both scale with speed so a slow grind sheds a few sparks
    // at long intervals while a top-speed grind emits a continuous stream.
    private static final double GRIND_PARTICLE_MIN_SPEED_RATIO = 0.25;
    private static final double GRIND_PARTICLE_SPARK_SPEED_RATIO = 0.50;
    // Each spark launches opposite the travel tangent, scaled so faster grinds throw sparks
    // farther. The lateral fan + small Y jitter keep the stream from collapsing into a line.
    private static final double SPARK_BASE_HORIZONTAL_SPEED = 0.15;
    private static final double SPARK_SPEED_BOOST_PER_RATIO = 0.35;
    private static final double SPARK_UPWARD_KICK           = 0.20;
    private static final double SPARK_UPWARD_JITTER         = 0.08;
    private static final double SPARK_FAN_SPREAD_RADIANS    = Math.PI / 4.0;

    /**
     * Deferred re-grind record. {@code preserved} is non-null for graph-hop transits where the destination edge is pre-chosen.
     * {@code anchorPos} pins sp.position every tick so the post-teleport LocalPlayer can't drag the server's position downward before the nearest-rail scan runs.
     */
    private record PendingRegrind(
            double carryVelocity,
            int ticksWaited,
            @Nullable GrindState preserved,
            Vec3 anchorPos) {}

    /**
     * Wire-encoded by ordinal in RailGrindDebugSyncPayload. Adding values is wire-compatible (out-of-range clamps to UNKNOWN); reordering/removing is NOT — append only.
     */
    public enum StopReason {
        USER_DISMOUNT("canceled by user"),
        END_OF_TRACK("end of track"),
        STUCK("stuck ticks too high"),
        MAX_DRIFT("drift too high"),
        DAMAGE("incoming damage"),
        BOOTS_SWAP("boots changed"),
        TELEPORT_REQUEST("teleport request"),
        CHAIN_MOUNTED("mounted chain conveyor"),
        CHAIN_HANDOFF("chain conveyor handoff"),
        AUTO_SPIN("auto-spin attack"),
        SUBLEVEL_REMOVED("sublevel removed"),
        EXTREME_SLOPE("slope too extreme"),
        TRAIN_OVERLAP("intersecting train"),
        SESSION_BOUNDARY("session boundary"),
        BLOCKED("blocked by obstacle"),
        EXTERNAL_TELEPORT("external teleport"),
        TRACK_REMOVED("track destroyed"),
        UNKNOWN("unknown");

        public final String displayName;
        StopReason(String displayName) { this.displayName = displayName; }

        /** Bounds-safe wire decode; out-of-range ordinals clamp to UNKNOWN. */
        public static StopReason fromOrdinal(int ord) {
            StopReason[] values = values();
            return (ord >= 0 && ord < values.length) ? values[ord] : UNKNOWN;
        }
    }

    /** Snapshot of the last cancel for the debug HUD. ticksSinceGraceEnded: -1 = grace still active, 0 = first post-grace tick. */
    public record LastDropHudState(
            StopReason reason,
            int ticksSinceGraceEnded) {}

    private static final Map<UUID, LastDropHudState> LAST_DROP_HUD_STATE = new ConcurrentHashMap<>();

    private RailGrindHandler() {}

    private static final class GrindState {
        final TrackGraph graph;
        TrackNode fromNode;
        TrackNode toNode;
        TrackEdge edge;
        double position;
        double currentSpeed;
        // NaN-seeded so tick 1 matches legacy behavior; smoothing engages from tick 2.
        double smoothedTarget = Double.NaN;
        // 1.0 on straights, CURVE_FACTOR on turns. NaN-seeded; ticked in tick() before computeTargetSpeed so the read-only debug-HUD call doesn't double-step it.
        double smoothedCurveFactor = Double.NaN;
        int stuckTicks;
        int extremeSlopeTicks;
        int totalTicks;
        // Seeded by railgrinding() to START_GRACE_TICKS + (latency_ms / 50). Suppresses MAX_DRIFT/stuck (NOT train-overlap) and defers noPhysics=true. Not seeded by ctor; reattach paths use reattachGraceTicks instead.
        int startGraceTicks;
        // Cross-dim only. Seeded by tickPendingRegrind to REATTACH_GRACE_BASE_TICKS + latency extra; collapsed to REATTACH_GRACE_AFTER_ACK_TICKS by releaseReattachGrace on client ack. While > 0, applyTickMotion ships serverAuthoritative=true target, pins sp.position, and suppresses MAX_DRIFT/stuck. Motion advance is gated separately by frozenAtReattachStart.
        int reattachGraceTicks;
        // While true, gs.position is held static. Cleared by ack (releaseReattachGrace collapses grace tail) or grace timeout. Set only in tickPendingRegrind.
        boolean frozenAtReattachStart;
        Vec3 prevPos;
        // Previous tick's outgoing target world-position. Null on first tick / after graph-hop / pending-regrind reseed. Drives the velocity-hint shipped with RailGrindTargetPayload.
        Vec3 prevTarget;
        double experiencedSlope;  // motion.y / motion.length() from last tick (sin of pitch); +up / -down
        // Signed curve direction at gs.position: +1 right, -1 left, 0 straight. Debug-HUD only for now.
        double experiencedCurve;
        double lateralSign;  // +1 or -1, fixed for the grind; picked at init from prePos.
        // Distance from spline centerline to rail bar. Refreshed by advanceJunction so a player crossing between gauges tracks the correct bar.
        double lateralOffset = LATERAL_OFFSET;
        // Vertical hover above the spline. Standard/narrow/wide share Y_OFFSET; monorail uses Y_OFFSET_MONORAIL. Refreshed by advanceJunction.
        double yOffset = Y_OFFSET;
        // Current edge's track type, refreshed by advanceJunction. Drives the gauge gate that treats gauge transitions as end-of-track, matching Steam'n'Rails' MixinCarriage#railways$isIncompatible. Null bypasses the gate.
        @Nullable TrackMaterial.TrackType railTrackType;
        int steerSign;       // -1 left, 0 none, +1 right. Edge-synced from SteerInputPayload; advanceJunction reads as targetDot.
        byte accelInputMode = GrindAccelInputPayload.VANILLA;  // VANILLA polls shift; OVERRIDE_OFF/ON decouple accel from shift so override-key works without sneak input.
        boolean collidingWithTrain;
        // SubLevelHandle wraps a plain Object via cached reflection, so this field is safe to declare without Sable installed; only set inside Mods.SABLE.runIfInstalled branches.
        @Nullable SableSubLevels.SubLevelHandle subLevel;
        // Debug-HUD counter. -1 while either grace is active; 0 on the tick both end; +1/tick thereafter.
        int ticksSinceGraceEnded = -1;
        // Seeded at reattach-grace end. While > 0: STUCK short-circuited, gs.position capped at POST_REATTACH_DRIFT_CAP ahead of sp.position projected on edge. Bounds drift below MAX_DRIFT structurally; reactive snapGsToPlayer remains as safety net for degenerate tangents.
        int postReattachKickSuppressTicks = 0;

        GrindState(TrackGraph graph, TrackNode fromNode, TrackNode toNode, TrackEdge edge, double position) {
            this.graph = graph;
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.edge = edge;
            this.position = position;
            this.currentSpeed = CRUISE_SPEED * Config.CRUISE_GRIND_SPEED.get();
            this.lateralSign = 1.0;
            // reattachGraceTicks intentionally not seeded here; cross-dim reattach paths set it explicitly.
        }
    }

    /** Edge-triggered: on actual change, broadcasts RailGrindLeanSyncPayload to tracking observers so they can drive the lean visual. */
    public static void setSteerInput(Player player, int steerSign) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;
        int clamped = Math.max(-1, Math.min(1, steerSign));
        if (clamped == gs.steerSign) return;
        gs.steerSign = clamped;
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                sp, new RailGrindLeanSyncPayload(sp.getUUID(), (byte) clamped));
        }
    }

    /** Coerces unknown modes to VANILLA so a malicious/stale client can't leave the state undefined. */
    public static void setAccelInputMode(Player player, byte mode) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;
        if (mode == GrindAccelInputPayload.VANILLA
                || mode == GrindAccelInputPayload.OVERRIDE_OFF
                || mode == GrindAccelInputPayload.OVERRIDE_ON) {
            gs.accelInputMode = mode;
        } else {
            gs.accelInputMode = GrindAccelInputPayload.VANILLA;
        }
    }

    /** Reattach grace = BASE + latency_ms * MULT / 50; negative latency clamps to base. */
    private static int latencyScaledReattachGrace(Player player) {
        int latencyMs = 0;
        if (player instanceof ServerPlayer sp && sp.connection != null) {
            int reported = sp.connection.latency();
            if (reported > 0) latencyMs = reported;
        }
        return REATTACH_GRACE_BASE_TICKS + (latencyMs * REATTACH_GRACE_LATENCY_MULT) / 50;
    }

    /** Post-reattach kick-suppress duration = BASE + latency_ms * MULT / 50; bridges the MovePlayer C2S round-trip gap. */
    private static int latencyScaledPostReattachKickSuppress(Player player) {
        int latencyMs = 0;
        if (player instanceof ServerPlayer sp && sp.connection != null) {
            int reported = sp.connection.latency();
            if (reported > 0) latencyMs = reported;
        }
        return POST_REATTACH_KICK_SUPPRESS_BASE_TICKS + (latencyMs * POST_REATTACH_KICK_SUPPRESS_LATENCY_MULT) / 50;
    }

    /** Client ack: destination dim is loaded and ready for motion. Idempotent; can only lower the counter, so a malicious ack can't extend grace. */
    public static void releaseReattachGrace(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;
        gs.frozenAtReattachStart = false;
        if (gs.reattachGraceTicks > REATTACH_GRACE_AFTER_ACK_TICKS) {
            gs.reattachGraceTicks = REATTACH_GRACE_AFTER_ACK_TICKS;
        }
    }

    /** Canonical "mid-transit" gate consulted by damage suppression, post-transit cooldown union, and train-overlap kick. */
    public static boolean isInReattachGrace(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs != null && gs.reattachGraceTicks > 0;
    }

    /** True while gs.startGraceTicks > 0. Train-overlap deliberately does NOT consult this — carriage-crush is a real condition, not a latency artifact. */
    public static boolean isInStartGrace(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs != null && gs.startGraceTicks > 0;
    }

    /** OVERRIDE_* mode wins over shift state so the override key works independently of sneak input. */
    private static boolean isAcceleratingForGrind(Player player, GrindState gs) {
        return switch (gs.accelInputMode) {
            case GrindAccelInputPayload.OVERRIDE_ON -> true;
            case GrindAccelInputPayload.OVERRIDE_OFF -> false;
            default -> player.isShiftKeyDown();
        };
    }

    public static boolean railgrinding(Player player, BlockPos trackPos, Vec3 prePos, double entryVelocity) {
        Level level = player.level();
        BlockState state = level.getBlockState(trackPos);
        if (!(state.getBlock() instanceof ITrackBlock track)) return false;
        if (!isGrindableMaterial(track.getMaterial())) return false;

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

    /** Parent-world overload; delegates with subLevel=null. */
    public static boolean railgrinding(Player player, TrackGraphLocation loc, Vec3 prePos, double entryVelocity) {
        return railgrinding(player, loc, prePos, entryVelocity, null);
    }

    /** Sublevel-aware grind init. When subLevel is non-null, loc references local-frame spline data and worldPos routes positions through the sublevel pose. */
    public static boolean railgrinding(Player player, TrackGraphLocation loc, Vec3 prePos, double entryVelocity,
                                       @Nullable SableSubLevels.SubLevelHandle subLevel) {
        if (isPlayerOnRailGrindCooldown(player)) return false;
        if (isPlayerCrushedByTrain(player)) return false;
        // Riding any vehicle is mutually exclusive with grinding: noPhysics body fights the vehicle's pose update.
        if (player.isPassenger()) return false;

        TrackGraph graph = loc.graph;
        Couple<TrackNode> nodes = loc.edge.map(graph::locateNode);
        TrackNode first = nodes.getFirst();
        TrackNode second = nodes.getSecond();
        if (first == null || second == null) return false;

        TrackEdge forwardEdge = graph.getConnectionsFrom(first).get(second);
        if (forwardEdge == null) return false;

        // Canonical material gate: bezier and nearest-rail callers can hand in a TrackGraphLocation built without proximity-scan filtering.
        if (!isGrindableMaterial(forwardEdge.getTrackMaterial())) return false;

        Vec3 chord = second.getLocation().getLocation().subtract(first.getLocation().getLocation());
        // Rotate the chord into world space; without this, a rotated sublevel inverts the sign and the player grinds backward.
        Vec3 chordForFacing = subLevel == null ? chord : subLevel.rotateNormalToWorld(chord);
        boolean forward = player.getLookAngle().dot(chordForFacing) >= 0;

        TrackEdge edge;
        if (forward) {
            edge = forwardEdge;
        } else {
            edge = graph.getConnectionsFrom(second).get(first);
            if (edge == null) {
                // Graph has no reverse edge; fall back to forward.
                edge = forwardEdge;
                forward = true;
            }
        }

        TrackNode fromNode = forward ? first : second;
        TrackNode toNode = forward ? second : first;
        double position = forward ? loc.position : edge.getLength() - loc.position;

        GrindState gs = new GrindState(graph, fromNode, toNode, edge, position);
        // Bind sublevel handle before any worldPos() call so init positions route through the pose transform.
        gs.subLevel = subLevel;
        gs.currentSpeed *= ModEffects.sonicWindMultiplier(player);
        // Capped at MAX_STEP (not TOP_SPEED) so the entry boost stays visible and decays smoothly via tick()'s accel/decel.
        gs.currentSpeed = Math.min(gs.currentSpeed + entryVelocity, MAX_STEP);

        // Pick the rail bar via right-of-travel dot product; locked for the grind so the player doesn't snap bars on tangent rotation.
        double edgeLenForSpawn = edge.getLength();
        double tSpawn = edgeLenForSpawn <= 0 ? 0 : Math.min(1.0, Math.max(0.0, position / edgeLenForSpawn));
        Vec3 splineSpawn = edge.getPosition(graph, tSpawn);
        Vec3 dirSpawn = sampleTangent(graph, edge, tSpawn);
        Vec3 spawnChord = toNode.getLocation().getLocation().subtract(fromNode.getLocation().getLocation());
        if (dirSpawn.x * spawnChord.x + dirSpawn.z * spawnChord.z < 0) dirSpawn = dirSpawn.scale(-1);
        double horizLenSpawn = Math.sqrt(dirSpawn.x * dirSpawn.x + dirSpawn.z * dirSpawn.z);
        if (horizLenSpawn > 1e-6) {
            Vec3 prePosForSide = subLevel == null ? prePos : subLevel.toLocal(prePos);
            double rxSpawn = -dirSpawn.z / horizLenSpawn;
            double rzSpawn =  dirSpawn.x / horizLenSpawn;
            double sideDot = rxSpawn * (prePosForSide.x - splineSpawn.x) + rzSpawn * (prePosForSide.z - splineSpawn.z);
            gs.lateralSign = sideDot >= 0 ? +1.0 : -1.0;
        }

        // Multi-candidate probe (splineSpawn first, then midpoint and endpoints) so gauge detection holds at 1-block rails / chain endpoints.
        TrackMaterial.TrackType seedType = resolveTrackTypeFromCandidates(
                player.level(), graph, edge, splineSpawn, fromNode, toNode, subLevel);
        gs.lateralOffset = lateralOffsetForType(seedType);
        gs.yOffset = yOffsetForType(seedType);
        // Use TrackEdge#getTrackMaterial directly (same API Steam'n'Rails uses for compat checks).
        gs.railTrackType = trackTypeOf(edge);

        ACTIVE.put(player.getUUID(), gs);
        // Cross-dim reattach paths clear this back to 0 after return so they ride exclusively on reattachGraceTicks.
        int latencyTicks = 0;
        if (player instanceof ServerPlayer sp) {
            latencyTicks = sp.connection.latency() / 50;
        }
        gs.startGraceTicks = START_GRACE_TICKS + latencyTicks;
        // Creative flight + grind state collide and leave the player stuck flying after stop.
        if (player.getAbilities().flying) {
            player.getAbilities().flying = false;
            if (player instanceof ServerPlayer sp) sp.onUpdateAbilities();
        }
        // Elytra glide fights the per-tick snap; only call stopFallFlying when actually fall-flying to avoid spamming the shared-flag packet.
        if (player.isFallFlying()) player.stopFallFlying();
        player.setNoGravity(true);
        // noPhysics intentionally not set here: vanilla Player.tick() resets it each tick anyway, and the mixin only re-asserts on slope > threshold (0 at init). Server keeps collision active through grace, bounding drift.

        Vec3 spawn = worldPos(gs).add(0, gs.yOffset, 0);
        player.setPos(spawn.x, spawn.y, spawn.z);
        if (player instanceof ServerPlayer sp) {
            sp.connection.teleport(spawn.x, spawn.y, spawn.z, sp.getYRot(), sp.getXRot());
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;

        syncPose(player, true);
        // Seed the client's EMA target; without this the first client tick has nothing to chase. Velocity=ZERO is fine — client seeds smoothedTarget from position alone on first packet.
        sendTargetToPlayer(player, spawn, Vec3.ZERO, 0.0, false);
        return true;
    }

    /** Every call site must pass a specific reason for the "Last cancel:" debug HUD line. Drains both ACTIVE and PENDING_REGRIND so dismount aborts cross-dim re-grinds too. */
    public static void stop(Player player, StopReason reason) {
        GrindState gsForSnapshot = ACTIVE.get(player.getUUID());
        int ticksSinceGraceEnded = gsForSnapshot != null ? gsForSnapshot.ticksSinceGraceEnded : -1;
        LAST_DROP_HUD_STATE.put(player.getUUID(), new LastDropHudState(reason, ticksSinceGraceEnded));
        boolean wasActive = ACTIVE.remove(player.getUUID()) != null;
        boolean wasPending = PENDING_REGRIND.remove(player.getUUID()) != null;
        if (!wasActive && !wasPending) return;
        player.setNoGravity(false);
        player.noPhysics = false;
        FALL_DAMAGE_IMMUNITY_TIME.put(player.getUUID(), player.level().getGameTime() + FALL_IMMUNITY_TICKS);
        START_COOLDOWN_REMAINING.put(player.getUUID(), START_COOLDOWN_TICKS);
        syncPose(player, false);
    }

    @Nullable
    public static LastDropHudState getLastDropHudState(Player player) {
        return LAST_DROP_HUD_STATE.get(player.getUUID());
    }

    /** Union of PORTAL_TRANSIT_COOLDOWN and reattach-grace; folds both into a single "mid-transit" gate so grace ticks 41-60 can't be re-fired into. */
    public static boolean isOnPostPortalTransitCooldown(Player player) {
        if (isInReattachGrace(player)) return true;
        Integer remaining = PORTAL_TRANSIT_COOLDOWN.get(player.getUUID());
        return remaining != null && remaining > 0;
    }

    /** Seeds the post-transit cooldown to its full duration. Called from each transit path. */
    public static void seedPortalTransitCooldown(Player player) {
        PORTAL_TRANSIT_COOLDOWN.put(player.getUUID(), PORTAL_TRANSIT_COOLDOWN_TICKS);
    }

    /** preserved=null → deferred start uses nearest-rail (paths 1/2/3); non-null → re-attach to the exact edge (path 4). */
    public static void enqueuePending(ServerPlayer sp, double carryVelocity, @Nullable GrindState preserved) {
        seedPortalTransitCooldown(sp);
        // false→true diff flushes the SynchedEntityData entry to the new client; a plain setNoGravity(true) is dropped as clean when the field was already true on the source side.
        sp.setNoGravity(false);
        sp.setNoGravity(true);
        sp.noPhysics = true;
        sp.fallDistance = 0.0F;
        sp.setDeltaMovement(Vec3.ZERO);
        Vec3 anchor = sp.position();
        PENDING_REGRIND.put(sp.getUUID(), new PendingRegrind(carryVelocity, 0, preserved, anchor));
    }

    /** Drains the pending queue once min-wait and chunk-loaded gates pass; releases flags on timeout to avoid freezing the player mid-air. */
    public static void tickPendingRegrind(Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        PendingRegrind pending = PENDING_REGRIND.get(sp.getUUID());
        if (pending == null) return;

        int waited = pending.ticksWaited() + 1;

        if (waited >= PENDING_REGRIND_TIMEOUT_TICKS) {
            PENDING_REGRIND.remove(sp.getUUID());
            cleanupAbandonedPending(sp);
            return;
        }

        // Re-anchor every tick so a vanilla-gravity LocalPlayer (stale noGravity until SynchedEntityData syncs) can't drag sp.position downward.
        sp.setPos(pending.anchorPos().x, pending.anchorPos().y, pending.anchorPos().z);
        sp.setDeltaMovement(Vec3.ZERO);
        sp.fallDistance = 0.0F;
        if (!sp.isNoGravity()) sp.setNoGravity(true);
        sp.noPhysics = true;

        boolean minWaitReached = waited >= PENDING_REGRIND_MIN_WAIT_TICKS;
        boolean chunkReady = sp.level().isLoaded(sp.blockPosition());

        if (!(minWaitReached && chunkReady)) {
            PENDING_REGRIND.put(sp.getUUID(),
                    new PendingRegrind(pending.carryVelocity(), waited, pending.preserved(),
                            pending.anchorPos()));
            return;
        }

        PENDING_REGRIND.remove(sp.getUUID());

        // Graph-hop path: re-attach the preserved GrindState directly (advanceJunction already chose the destination edge).
        if (pending.preserved() != null) {
            GrindState gs = pending.preserved();
            Vec3 spawn = worldPos(gs).add(0, gs.yOffset, 0);
            ACTIVE.put(sp.getUUID(), gs);
            sp.setNoGravity(true);
            sp.noPhysics = true;
            sp.fallDistance = 0.0F;
            sp.setDeltaMovement(Vec3.ZERO);
            gs.prevPos = null;
            gs.stuckTicks = 0;
            // NaN-reseed: destination edge has unrelated slope/curve/velocity profile from source.
            gs.smoothedTarget = Double.NaN;
            gs.smoothedCurveFactor = Double.NaN;
            gs.experiencedCurve = 0.0;
            gs.prevTarget = null;
            gs.reattachGraceTicks = latencyScaledReattachGrace(sp);
            gs.totalTicks = 0;
            // Freeze motion until client ack; preserved gs.currentSpeed is held intact for resume.
            gs.frozenAtReattachStart = true;
            sp.connection.teleport(spawn.x, spawn.y, spawn.z, sp.getYRot(), sp.getXRot());
            markNextStartSilent(sp.getUUID());
            syncPose(sp, true);
            // Preserved experiencedSlope keeps noPhysics-bypass live for the first tick. Velocity=ZERO since destination tangent is unrelated; serverAuthoritative=true so client hard-snaps.
            sendTargetToPlayer(sp, spawn, Vec3.ZERO, gs.experiencedSlope, true);
            return;
        }

        // Nearest-rail re-grind. Empty scan → cleanup with fall-immunity so the landing arc doesn't kill at speed.
        RailHit hit = findNearestRailLocation(sp.level(), sp.position(), PORTAL_REGRIND_SEARCH_RANGE);
        if (hit == null) {
            cleanupAbandonedPending(sp);
            return;
        }
        markNextStartSilent(sp.getUUID());
        railgrinding(sp, hit.loc(), sp.position(), pending.carryVelocity(), hit.subLevel());
        // Arm the trigger-based grace on the new state; railgrinding() doesn't seed this (normal starts want instant motion).
        GrindState newGs = ACTIVE.get(sp.getUUID());
        if (newGs != null) {
            newGs.reattachGraceTicks = latencyScaledReattachGrace(sp);
            // Single-source reattach suppression through reattachGraceTicks; clear the start grace to avoid duplicate.
            newGs.startGraceTicks = 0;
            newGs.frozenAtReattachStart = true;
            // Resume at pre-cross-dim speed, clamped to MAX_STEP — spline-chase math can't track larger per-tick steps without drifting off the bezier ([[max_step_alignment_ceiling]]).
            newGs.currentSpeed = Math.min(pending.carryVelocity(), MAX_STEP);
        }
    }

    /** Releases physics flags, seeds fall-immunity, syncs grinding=false. */
    private static void cleanupAbandonedPending(ServerPlayer sp) {
        sp.setNoGravity(false);
        sp.noPhysics = false;
        FALL_DAMAGE_IMMUNITY_TIME.put(sp.getUUID(), sp.level().getGameTime() + FALL_IMMUNITY_TICKS);
        syncPose(sp, false);
    }

    /** Decrements PORTAL_TRANSIT_COOLDOWN and evicts at zero. */
    public static void tickPortalTransitCooldown(Player player) {
        Integer remaining = PORTAL_TRANSIT_COOLDOWN.get(player.getUUID());
        if (remaining == null) return;
        int next = remaining - 1;
        if (next <= 0) PORTAL_TRANSIT_COOLDOWN.remove(player.getUUID());
        else PORTAL_TRANSIT_COOLDOWN.put(player.getUUID(), next);
    }

    /** Instant-port via Create's PortalTrackProvider (the rail-to-rail mapping). Returns true on transit (caller early-returns); false if no supported portal block here. */
    private static boolean tryPortalTransit(ServerPlayer sp, GrindState oldState, ServerLevel level) {
        if (isOnPostPortalTransitCooldown(sp)) return false;
        BlockPos here = sp.blockPosition();
        BlockState portalState = level.getBlockState(here);
        if (!PortalTrackProvider.isSupportedPortal(portalState)) return false;

        // Entry face = nearest horizontal cardinal of the rail tangent (zero Y; portal blocks are horizontal-axis-aligned).
        double edgeLen = oldState.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, oldState.position / edgeLen));
        Vec3 tangent = sampleTangent(oldState.graph, oldState.edge, t);
        Vec3 chord = oldState.toNode.getLocation().getLocation()
                .subtract(oldState.fromNode.getLocation().getLocation());
        if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);
        Direction entryDir = Direction.getNearest(tangent.x, 0, tangent.z);

        PortalTrackProvider.Exit exit = PortalTrackProvider.getOtherSide(
                level, new BlockFace(here, entryDir));
        if (exit == null) return false;

        // Drop without seeding START_COOLDOWN so the post-portal re-grind isn't gated by the manual-jump-off debounce.
        double carryVelocity = oldState.currentSpeed;
        ACTIVE.remove(sp.getUUID());

        // Land one block past the exit face so the player isn't clipped inside the portal block and re-teleported next tick.
        BlockFace exitFace = exit.face();
        BlockPos exitTargetPos = exitFace.getConnectedPos();
        Vec3 targetPos = Vec3.atCenterOf(exitTargetPos);
        float yaw = exitFace.getFace().toYRot();
        sp.teleportTo(exit.level(), targetPos.x, targetPos.y, targetPos.z, yaw, sp.getXRot());
        sp.setPortalCooldown(PORTAL_REGRIND_COOLDOWN_TICKS);

        finishCrossDimRegrind(sp, carryVelocity);
        return true;
    }

    /** Defers re-grind through enqueuePending so the new LocalPlayer's Clone handler runs before our sync (else noPhysics gets cleared after we set it). */
    private static void finishCrossDimRegrind(ServerPlayer sp, double carryVelocity) {
        enqueuePending(sp, carryVelocity, null);
    }

    /** Vanilla portal flow / any cross-dim teleport. Drops old gs (TrackGraph lives in old dim) and carries currentSpeed forward as entry velocity. */
    public static void handleDimensionChange(ServerPlayer sp) {
        GrindState gs = ACTIVE.remove(sp.getUUID());
        if (gs == null) return;
        finishCrossDimRegrind(sp, gs.currentSpeed);
    }

    /** Inter-dim graph hop. Re-uses gs on the new dim (graph already chose the exit edge — a free-form proximity scan could pick the wrong rail). */
    private static void teleportThroughGraphHop(ServerPlayer sp, GrindState gs, ResourceKey<Level> targetDim) {
        ServerLevel newLevel = sp.server.getLevel(targetDim);
        if (newLevel == null) {
            ACTIVE.remove(sp.getUUID());
            sp.setNoGravity(false);
            sp.noPhysics = false;
            syncPose(sp, false);
            return;
        }
        // Re-resolve against newLevel: advanceJunction ran against the source dim's level, leaving lateral/y offsets on the LATERAL_OFFSET/Y_OFFSET fallback.
        TrackMaterial.TrackType destType = resolveTrackTypeFromCandidates(
                newLevel, gs.graph, gs.edge, null, gs.fromNode, gs.toNode, gs.subLevel);
        gs.lateralOffset = lateralOffsetForType(destType);
        gs.yOffset = yOffsetForType(destType);

        Vec3 targetPos = worldPos(gs).add(0, gs.yOffset, 0);

        // Clear ACTIVE before teleport so PlayerChangedDimensionEvent skips its own re-grind path.
        ACTIVE.remove(sp.getUUID());
        sp.teleportTo(newLevel, targetPos.x, targetPos.y, targetPos.z, sp.getYRot(), sp.getXRot());
        sp.setPortalCooldown(PORTAL_REGRIND_COOLDOWN_TICKS);

        enqueuePending(sp, gs.currentSpeed, gs);
    }

    /** Adjacency scan for the "1-block gap between rail end and portal block" layout. Returns true on transit. */
    private static boolean tryPortalTransitFromNode(ServerPlayer sp, GrindState gs, ServerLevel level) {
        if (isOnPostPortalTransitCooldown(sp)) return false;
        Vec3 nodeVec = gs.toNode.getLocation().getLocation();
        BlockPos nodeBlock = BlockPos.containing(nodeVec);
        // Prefer the travel direction; fall back to other cardinals for misaligned setups.
        Vec3 chord = gs.toNode.getLocation().getLocation()
                .subtract(gs.fromNode.getLocation().getLocation());
        Direction preferredDir = Direction.getNearest(chord.x, 0, chord.z);
        Direction[] order = orderedDirs(preferredDir);
        for (Direction dir : order) {
            BlockPos check = nodeBlock.relative(dir);
            BlockState state = level.getBlockState(check);
            if (!PortalTrackProvider.isSupportedPortal(state)) continue;
            PortalTrackProvider.Exit exit = PortalTrackProvider.getOtherSide(
                    level, new BlockFace(check, dir));
            if (exit == null) continue;

            double carryVelocity = gs.currentSpeed;
            ACTIVE.remove(sp.getUUID());
            BlockFace exitFace = exit.face();
            Vec3 targetPos = Vec3.atCenterOf(exitFace.getConnectedPos());
            float yaw = exitFace.getFace().toYRot();
            sp.teleportTo(exit.level(), targetPos.x, targetPos.y, targetPos.z, yaw, sp.getXRot());
            sp.setPortalCooldown(PORTAL_REGRIND_COOLDOWN_TICKS);
            finishCrossDimRegrind(sp, carryVelocity);
            return true;
        }
        return false;
    }

    /** Horizontal cardinals with preferred first, else stable order; tryPortalTransitFromNode favors the rail's travel direction. */
    private static Direction[] orderedDirs(Direction preferred) {
        Direction[] horizontals = new Direction[] {
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
        if (preferred == null || !preferred.getAxis().isHorizontal()) {
            return horizontals;
        }
        Direction[] out = new Direction[4];
        out[0] = preferred;
        int i = 1;
        for (Direction d : horizontals) {
            if (d != preferred) out[i++] = d;
        }
        return out;
    }

    /** Cancels post-dismount fall damage; entries self-evict past expiry. */
    public static boolean hasFallImmunity(Player player) {
        Long time = FALL_DAMAGE_IMMUNITY_TIME.get(player.getUUID());
        if (time == null) return false;
        if (player.level().getGameTime() >= time) {
            FALL_DAMAGE_IMMUNITY_TIME.remove(player.getUUID());
            return false;
        }
        return true;
    }

    /** Debug HUD accessor; gameplay code uses hasFallImmunity. */
    public static int getFallImmunityRemaining(Player player) {
        Long time = FALL_DAMAGE_IMMUNITY_TIME.get(player.getUUID());
        if (time == null) return 0;
        long remaining = time - player.level().getGameTime();
        return remaining > 0 ? (int) remaining : 0;
    }

    /** Gates Networking.handleTeleport and railgrinding(); set by stop(), decremented by tickCooldown(). */
    public static boolean isPlayerOnRailGrindCooldown(Player player) {
        Integer remaining = START_COOLDOWN_REMAINING.get(player.getUUID());
        return remaining != null && remaining > 0;
    }

    /** Debug HUD accessor; gameplay code uses isPlayerOnRailGrindCooldown. */
    public static int getStartCooldownRemaining(Player player) {
        Integer remaining = START_COOLDOWN_REMAINING.get(player.getUUID());
        return remaining == null ? 0 : remaining;
    }

    /** Decrements START_COOLDOWN_REMAINING and evicts at zero. */
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

    /** Increments while bbox overlaps any CarriageContraptionEntity, hard-resets on clear. Runs for every player (the crush gate blocks non-grinders too). Reattach grace suppresses the kick; start grace does NOT (carriage-spawn is a real condition). */
    public static void tickTrainOverlap(Player player) {
        boolean overlapping = !player.level().getEntitiesOfClass(
                CarriageContraptionEntity.class, player.getBoundingBox()).isEmpty();

        if (overlapping) {
            int next = TRAIN_OVERLAP_TICKS.getOrDefault(player.getUUID(), 0) + 1;
            TRAIN_OVERLAP_TICKS.put(player.getUUID(), next);
            if (next >= TRAIN_OVERLAP_KICK_TICKS && isGrinding(player)
                    && !isInReattachGrace(player)) {
                stop(player, StopReason.TRAIN_OVERLAP);
            }
        } else {
            TRAIN_OVERLAP_TICKS.remove(player.getUUID());
        }

        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs != null) gs.collidingWithTrain = overlapping;
    }

    /** True after TRAIN_OVERLAP_KICK_TICKS consecutive overlap ticks. Read by grind-init paths to refuse new grinds. */
    public static boolean isPlayerCrushedByTrain(Player player) {
        Integer ticks = TRAIN_OVERLAP_TICKS.get(player.getUUID());
        return ticks != null && ticks >= TRAIN_OVERLAP_KICK_TICKS;
    }

    /** Debug HUD accessor; uncapped so "still stuck after kick" stays visible. */
    public static int getTrainOverlapTicks(Player player) {
        return TRAIN_OVERLAP_TICKS.getOrDefault(player.getUUID(), 0);
    }

    /** Nearest-rail scan result. subLevel non-null for Sable rails; SubLevelHandle carries no Sable types directly so this record loads without Sable installed. */
    public record RailHit(TrackGraphLocation loc, @Nullable SableSubLevels.SubLevelHandle subLevel) {}

    public static RailHit findNearestRailLocation(Level level, Vec3 origin, double maxDist) {
        // Single-element arrays carry best-so-far through scanLevelForRails. Distances are world-space so parent + sublevel candidates compare directly.
        TrackGraphLocation[] bestLoc = { null };
        SableSubLevels.SubLevelHandle[] bestSub = { null };
        // Outer cap admits wide-gauge bonus; per-candidate cap inside the loop gates non-wide to baseDistSq.
        double maxDistOuter = maxDist + WIDE_GAUGE_START_RADIUS_BONUS;
        double[] bestDistSq = { maxDistOuter * maxDistOuter };
        double baseDistSq = maxDist * maxDist;

        scanLevelForRails(level, origin, origin, null, bestLoc, bestSub, bestDistSq, baseDistSq);

        // Sablesublevel sweep gated on Mods.SABLE so Class.forName lookups never fire when Sable's absent.
        Mods.SABLE.executeIfInstalled(() -> () -> {
            for (SableSubLevels.SubLevelHandle handle : SableSubLevels.sublevelsNear(level, origin, maxDistOuter)) {
                Level slLevel = handle.getLevel();
                if (slLevel == null) continue;
                Vec3 originLocal = handle.toLocal(origin);
                scanLevelForRails(slLevel, originLocal, origin, handle, bestLoc, bestSub, bestDistSq, baseDistSq);
            }
        });

        return bestLoc[0] == null ? null : new RailHit(bestLoc[0], bestSub[0]);
    }

    /** Parent-world-only scan; right-click teleport uses this so the post-teleport scan can't pick a sublevel rail (Sable chunk-cache disconnect risk). */
    public static @Nullable TrackGraphLocation findNearestRailInLevel(Level level, Vec3 origin, double maxDist) {
        TrackGraphLocation[] bestLoc = { null };
        SableSubLevels.SubLevelHandle[] bestSub = { null };
        double maxDistOuter = maxDist + WIDE_GAUGE_START_RADIUS_BONUS;
        double[] bestDistSq = { maxDistOuter * maxDistOuter };
        double baseDistSq = maxDist * maxDist;
        scanLevelForRails(level, origin, origin, null, bestLoc, bestSub, bestDistSq, baseDistSq);
        return bestLoc[0];
    }

    /** originInLevel = origin in level's frame; handle non-null for sublevels (transforms candidates to world for distance check). Block-cube range overscans by 1 to absorb sublevel rotation. */
    private static void scanLevelForRails(
            Level level, Vec3 originInLevel, Vec3 originWorld,
            @Nullable SableSubLevels.SubLevelHandle handle,
            TrackGraphLocation[] bestLoc,
            SableSubLevels.SubLevelHandle[] bestSub,
            double[] bestDistSq,
            double baseDistSq) {
        BlockPos center = BlockPos.containing(originInLevel);
        int blockRange = (int) Math.ceil(Math.sqrt(bestDistSq[0])) + 1;

        // Plain ITrackBlock scan; matches the right-click empty-hand resolution path.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -blockRange; dx <= blockRange; dx++) {
            for (int dy = -blockRange; dy <= blockRange; dy++) {
                for (int dz = -blockRange; dz <= blockRange; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (!(state.getBlock() instanceof ITrackBlock track)) continue;
                    TrackMaterial material = track.getMaterial();
                    if (!isGrindableMaterial(material)) continue;
                    TrackMaterial.TrackType trackType = material.trackType;
                    Vec3 cursorCenter = cursor.getCenter();
                    Vec3 cursorWorld = handle == null ? cursorCenter : handle.toWorld(cursorCenter);
                    double d2 = cursorWorld.distanceToSqr(originWorld);
                    // Wide gauge admitted to outer cap; everything else to baseDistSq.
                    double candidateCap = isWideGaugeTrackType(trackType) ? bestDistSq[0] : Math.min(baseDistSq, bestDistSq[0]);
                    if (d2 < candidateCap) {
                        BlockPos pos = cursor.immutable();
                        TrackGraphLocation loc = resolvePlainTrack(level, pos, state, track);
                        if (loc != null) {
                            bestDistSq[0] = d2;
                            bestLoc[0] = loc;
                            bestSub[0] = handle;
                        }
                    }
                }
            }
        }

        // Bezier scan over 3×3 chunks for TrackBlockEntity; matches the right-click empty-hand bezier path.
        int chunkX = SectionPos.blockToSectionCoord(center.getX());
        int chunkZ = SectionPos.blockToSectionCoord(center.getZ());
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                ChunkAccess chunk = level.getChunk(chunkX + cx, chunkZ + cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockPos bePos : chunk.getBlockEntitiesPos()) {
                    BlockEntity be = level.getBlockEntity(bePos);
                    if (!(be instanceof TrackBlockEntity tbe)) continue;
                    // Both endpoints of a BezierConnection share material, so the endpoint TBE stands in. Without this gate, phantom variants' curves bypass the plain-block scan's rejection.
                    if (!(level.getBlockState(bePos).getBlock() instanceof ITrackBlock endTrack)) continue;
                    TrackMaterial endMaterial = endTrack.getMaterial();
                    if (!isGrindableMaterial(endMaterial)) continue;
                    boolean wideGauge = isWideGaugeTrackType(endMaterial.trackType);
                    double candidateCapBezier = wideGauge ? bestDistSq[0] : Math.min(baseDistSq, bestDistSq[0]);
                    for (Map.Entry<BlockPos, BezierConnection> entry : tbe.getConnections().entrySet()) {
                        BezierConnection conn = entry.getValue();
                        int segCount = conn.getSegmentCount();
                        for (int seg = 0; seg < segCount; seg++) {
                            float t = conn.getSegmentT(seg);
                            Vec3 p = conn.getPosition(t);
                            Vec3 pWorld = handle == null ? p : handle.toWorld(p);
                            double d2 = pWorld.distanceToSqr(originWorld);
                            if (d2 >= candidateCapBezier) continue;
                            BezierTrackPointLocation btpl = new BezierTrackPointLocation(entry.getKey(), seg);
                            TrackGraphLocation loc = TrackGraphHelper.getBezierGraphLocationAt(
                                    level, bePos, Direction.AxisDirection.POSITIVE, btpl);
                            if (loc == null) loc = TrackGraphHelper.getBezierGraphLocationAt(
                                    level, bePos, Direction.AxisDirection.NEGATIVE, btpl);
                            if (loc != null) {
                                bestDistSq[0] = d2;
                                bestLoc[0] = loc;
                                bestSub[0] = handle;
                                // Tighten the per-type cap so later segments of this curve don't accept a worse hit.
                                candidateCapBezier = wideGauge ? bestDistSq[0] : Math.min(baseDistSq, bestDistSq[0]);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Same lookup loop as the BlockPos railgrinding overload: try every axis × axis-direction until getGraphLocationAt resolves. */
    private static TrackGraphLocation resolvePlainTrack(Level level, BlockPos pos, BlockState state, ITrackBlock track) {
        for (Vec3 axis : track.getTrackAxes(level, pos, state)) {
            for (Direction.AxisDirection dir : Direction.AxisDirection.values()) {
                TrackGraphLocation loc = TrackGraphHelper.getGraphLocationAt(level, pos, dir, axis);
                if (loc != null) return loc;
            }
        }
        return null;
    }

    /** Tangent-aligned launch + vertical kick scaled by speed and charge ratio. Charge is additive on top of speed-based formula. */
    public static void stopWithLaunch(Player player, int chargeTicks, StopReason reason) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) {
            stop(player, reason);
            return;
        }

        // Snapshot before stop() removes the state.
        double speed = gs.currentSpeed;
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 tangent = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        // Chord-flip first (still in spline-local), then rotate to world — launch drives world velocity.
        if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);
        tangent = rotateTangentToWorld(gs, tangent);

        double chargeRatio = computeChargeRatio(chargeTicks);
        double speedMult  = Config.RAIL_JUMP_MOMENTUM.get();
        // Sonic Wind boosts the charge half only (momentum side is untouched).
        double chargeMult = Config.RAIL_JUMP_CHARGE.get() * ModEffects.sonicWindMultiplier(player);
        // LAUNCH_VERTICAL_BASE is the unscaled floor that guarantees an upward kick at any speed.
        double horizMag = speed * LAUNCH_HORIZONTAL_MULT * speedMult
                * (1.0 + chargeRatio * LAUNCH_CHARGE_HORIZONTAL_BONUS_MULT * chargeMult);
        double vertBoost = (LAUNCH_VERTICAL_BASE + speed * LAUNCH_VERTICAL_SCALE * speedMult)
                * (1.0 + chargeRatio * LAUNCH_CHARGE_VERTICAL_BONUS_MULT * chargeMult);
        Vec3 launch = new Vec3(
            tangent.x * horizMag,
            tangent.y * horizMag + vertBoost,
            tangent.z * horizMag
        );

        // Inherit sublevel velocity so jumping off a moving ship carries momentum; zero for parent-world.
        launch = launch.add(sublevelVelocityAt(gs, player.position()));

        stop(player, reason);
        player.setDeltaMovement(launch);
        player.hurtMarked = true;  // forces a velocity packet so the client doesn't predict the launch away
        player.fallDistance = 0.0F;

        // Auto-deploy elytra must run after stop() so LivingEntityFallFlyingMixin's isGrinding gate is cleared.
        if (Config.AUTO_DEPLOY_ELYTRA.get()) {
            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.canElytraFly(player)) {
                player.startFallFlying();
            }
        }
    }

    /** Hold-ticks → 0..1 ratio over the charge window; clamped so malicious client values can't exceed the cap. Shared with the client overlay. */
    public static double computeChargeRatio(int chargeTicks) {
        int range = JUMP_TRICK_CHARGE_INPUT_TIME_MAX - JUMP_TRICK_CHARGE_INPUT_TIME_MIN;
        if (range <= 0) return 0.0;
        int clamped = Math.max(JUMP_TRICK_CHARGE_INPUT_TIME_MIN,
                Math.min(JUMP_TRICK_CHARGE_INPUT_TIME_MAX, chargeTicks));
        return (clamped - JUMP_TRICK_CHARGE_INPUT_TIME_MIN) / (double) range;
    }

    public static boolean isGrinding(Player player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    /** Server-authoritative grind speed in blocks/tick, or 0 if the player isn't grinding. */
    public static double getCurrentSpeed(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs == null ? 0.0 : gs.currentSpeed;
    }

    /** sin(pitch) from last tick. Server reads authoritative gs; client reads the local mirror (remote grinders see local value, see clientLocalSlope). */
    public static double getExperiencedSlope(Player player) {
        if (player.level().isClientSide()) {
            return clientLocalSlope;
        }
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs == null ? 0.0 : gs.experiencedSlope;
    }

    /** Debug-only world-space frame: spline origin, travel-aligned tangent, current snap target. */
    public record GrindFrame(Vec3 origin, Vec3 tangent, Vec3 snapTarget) {}

    public static GrindFrame getGrindFrame(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return null;
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 pos = gs.edge.getPosition(gs.graph, t);
        Vec3 dir = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (dir.x * chord.x + dir.z * chord.z < 0) dir = dir.scale(-1);
        pos = localToWorld(gs, pos);
        dir = rotateTangentToWorld(gs, dir);
        Vec3 snap = worldPos(gs).add(0, gs.yOffset, 0);
        return new GrindFrame(pos, dir, snap);
    }

    /** Debug-only per-tick snapshot. driftMargin: MAX_DRIFT − distance; NaN while suppressed by grace. */
    public record GrindDebugInfo(
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
        double driftMargin,
        int reattachGraceTicks,
        int ticksSinceGraceEnded
    ) {}

    public static GrindDebugInfo getGrindDebugInfo(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return null;
        // Mirror tick()'s fluid scaling so HUD numbers match what's driving motion this tick.
        double fluidMult = computeFluidMultiplier(player);
        Vec3 target = worldPos(gs).add(0, gs.yOffset, 0);
        double currentDrift = player.position().subtract(target).length();
        double threshold = computeDynamicMaxDrift(player, gs);
        double margin = (gs.reattachGraceTicks > 0 || gs.startGraceTicks > 0)
                ? Double.NaN
                : threshold - currentDrift;
        return new GrindDebugInfo(
            gs.currentSpeed,
            computeTargetSpeed(gs, player) * fluidMult,
            computeAcceleration(gs, player) * fluidMult,
            topSpeed(),
            gs.experiencedSlope,
            gs.experiencedCurve,
            gs.position,
            gs.edge.getLength(),
            gs.stuckTicks,
            gs.totalTicks,
            gs.lateralSign,
            gs.edge.isTurn(),
            isAcceleratingForGrind(player, gs),
            gs.collidingWithTrain,
            margin,
            gs.reattachGraceTicks,
            gs.ticksSinceGraceEnded
        );
    }

    /** Centered finite-difference of getPosition(t). TrackEdge.getDirectionAt returns the chord on beziers, which freezes any derived rotation across the curve ([[debug_cube_curve_freeze]]). */
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

    /** +1 right / -1 left / 0 straight. 2D cross of tangent samples at t ± epsilon, gained, clamped to ±1. */
    private static double computeRawExperiencedCurve(GrindState gs) {
        if (!gs.edge.isTurn()) return 0.0;
        double edgeLen = gs.edge.getLength();
        if (edgeLen <= 1e-6) return 0.0;
        double t = Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        double t0 = Math.max(0.0, t - CURVE_SAMPLE_EPSILON);
        double t1 = Math.min(1.0, t + CURVE_SAMPLE_EPSILON);
        if (t1 - t0 < 1e-6) return 0.0;
        Vec3 prev = sampleTangent(gs.graph, gs.edge, t0);
        Vec3 next = sampleTangent(gs.graph, gs.edge, t1);
        // Chord-flip both samples in lockstep so the cross is invariant to edge parameterization direction.
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        double chordDot = prev.x * chord.x + prev.z * chord.z;
        if (chordDot < 0) {
            prev = prev.scale(-1);
            next = next.scale(-1);
        }
        double pl = Math.sqrt(prev.x * prev.x + prev.z * prev.z);
        double nl = Math.sqrt(next.x * next.x + next.z * next.z);
        if (pl < 1e-6 || nl < 1e-6) return 0.0;
        double px = prev.x / pl, pz = prev.z / pl;
        double nx = next.x / nl, nz = next.z / nl;
        // 2D cross in xz; sin of rotation from prev to next.
        double cross = px * nz - pz * nx;
        double scaled = cross * CURVE_SIGNAL_GAIN;
        return Math.max(-1.0, Math.min(1.0, scaled));
    }

    public static void tick(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;

        // Chain-conveyor handoff via hangingPlayers map ([[create_chain_conveyor_riding]]); fast-path is ClientInputHandler's payload. Skip during reattach grace — stale source-dim entries can false-fire ([[cross_dim_grace_defeated_by_stale_state]]).
        if (!isInReattachGrace(player)
                && ServerChainConveyorHandler.hangingPlayers.containsKey(player.getUUID())) {
            stop(player, StopReason.CHAIN_HANDOFF);
            return;
        }

        // Auto-spin (riptide) fights setDeltaMovement. Use the entity flag, not item check ([[feedback_detect_via_entity_flags]]). Skip during reattach grace — the flag can survive cross-dim and false-positive there ([[cross_dim_grace_defeated_by_stale_state]]).
        if (!isInReattachGrace(player) && player.isAutoSpinAttack()) {
            stop(player, StopReason.AUTO_SPIN);
            return;
        }

        // Sublevel disposal guard: orphaned subLevel would keep the player frozen mid-air on a stale pose.
        if (gs.subLevel != null && (gs.subLevel.isRemoved() || gs.subLevel.getLevel() == null)) {
            stop(player, StopReason.SUBLEVEL_REMOVED);
            return;
        }

        // Track-removal guard. Skipped during reattach grace where a brief mid-handoff graph mismatch is normal.
        if (!isInReattachGrace(player) && !isCurrentEdgePresent(gs)) {
            stop(player, StopReason.TRACK_REMOVED);
            return;
        }

        // Instant cross-dim teleport on Create-supported portals (skips vanilla's 4s wait).
        if (player.level() instanceof ServerLevel sl && player instanceof ServerPlayer sp
                && tryPortalTransit(sp, gs, sl)) {
            return;
        }

        // On the tick startup grace hits 0, flip noPhysics so the slope-gated mixin re-assertion takes over.
        if (gs.startGraceTicks > 0) {
            gs.startGraceTicks--;
            if (gs.startGraceTicks == 0) {
                player.noPhysics = true;
            }
        }

        gs.totalTicks++;

        if (player.level() instanceof ServerLevel sl) {
            double speedRatio = Math.min(2.0, gs.currentSpeed / topSpeed());
            if (speedRatio >= GRIND_PARTICLE_MIN_SPEED_RATIO) {
                int interval = Math.max(1, (int) Math.round(1.0 / speedRatio));
                if (gs.totalTicks % interval == 0) {
                    spawnGrindParticles(sl, gs, player, speedRatio);
                }
            }
        }

        // Defensive re-assert: any momentary noPhysics=false on slopes clips the player into the rail support.
        if (!player.noPhysics)     player.noPhysics = true;
        if (!player.isNoGravity()) player.setNoGravity(true);
        if (player.isFallFlying()) player.stopFallFlying();
        // onGround=true from before grind triggers vanilla step-up/sneak/fall handling that nudges Y even under noPhysics.
        player.setOnGround(false);

        // Δy / Δdistance from last tick; same displacement length doubles as absVelocity for stuck detection.
        Vec3 currentPos = player.position();
        double absVelocity = 0.0;
        if (gs.prevPos != null) {
            Vec3 motion = currentPos.subtract(gs.prevPos);
            absVelocity = motion.length();
            if (absVelocity > 1e-4) gs.experiencedSlope = motion.y / absVelocity;
        }
        gs.prevPos = currentPos;

        // EMA-smooth even when raw is 0 so turn→straight fades rather than snaps.
        double rawCurve = computeRawExperiencedCurve(gs);
        gs.experiencedCurve += (rawCurve - gs.experiencedCurve) * CURVE_SMOOTH_RATE;

        // Sable-only extreme-slope guard. Persistence-required: 10 consecutive ticks above threshold filters out entry-velocity spikes, sublevel-translation glitches, FP noise. Suppressed during grace windows where those transients are expected.
        if (Mods.SABLE.isLoaded() && Math.abs(gs.experiencedSlope) > EXTREME_SLOPE_THRESHOLD
                && gs.startGraceTicks <= 0 && !isInReattachGrace(player)) {
            if (++gs.extremeSlopeTicks >= EXTREME_SLOPE_DROP_TICKS) {
                stop(player, StopReason.EXTREME_SLOPE);
                return;
            }
        } else {
            gs.extremeSlopeTicks = 0;
        }

        // While frozen, skip position/speed advance so the player visibly holds at the entry point.
        if (gs.frozenAtReattachStart) {
            applyTickMotion(player, gs, absVelocity);
            return;
        }

        // Tick the smoothed curve factor here (not inside computeTargetSpeed) so the debug HUD's read-only call doesn't double-step it.
        double rawCurveFactor = gs.edge.isTurn() ? CURVE_FACTOR : 1.0;
        if (Double.isNaN(gs.smoothedCurveFactor)) {
            gs.smoothedCurveFactor = rawCurveFactor;
        } else {
            double rate = (rawCurveFactor < gs.smoothedCurveFactor)
                    ? CURVE_FACTOR_ENTER_RATE
                    : CURVE_FACTOR_EXIT_RATE;
            gs.smoothedCurveFactor += (rawCurveFactor - gs.smoothedCurveFactor) * rate;
        }

        double fluidMult = computeFluidMultiplier(player);
        double targetSpeed = computeTargetSpeed(gs, player) * fluidMult;
        double accel = computeAcceleration(gs, player) * fluidMult;

        // Tick-1 seed from raw target avoids startup lag spike; subsequent ticks low-pass.
        if (Double.isNaN(gs.smoothedTarget)) {
            gs.smoothedTarget = targetSpeed;
        } else {
            gs.smoothedTarget += (targetSpeed - gs.smoothedTarget) * TARGET_SMOOTH_RATE;
        }

        // Hard cap on fluid entry; without the snap, decel takes ~75 ticks. Resync filter so the ease doesn't undo the snap next tick.
        if (fluidMult < 1.0 && gs.currentSpeed > targetSpeed) {
            gs.currentSpeed = targetSpeed;
            gs.smoothedTarget = targetSpeed;
        }

        // Exponential ease toward target, per-tick step capped at accel. Asymptotic instead of Math.min/max clamp (which produced the "snap-at-target" feel).
        double diff = gs.smoothedTarget - gs.currentSpeed;
        double step = diff * SPEED_EASE_RATE;
        if (step > accel) step = accel;
        else if (step < -accel) step = -accel;
        gs.currentSpeed += step;

        if (gs.currentSpeed <= 1e-6) {
            applyTickMotion(player, gs, absVelocity);
            return;
        }

        // Inner/outer rail arclength compensation: divide by railBarSpeedFactor so world speed matches currentSpeed regardless of bar side ([[rail_grind_bar_arclength_compensation]]).
        double remaining = gs.currentSpeed / railBarSpeedFactor(gs);
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
                    // No graph continuation; scan adjacent blocks for a portal (handles the 1-block gap between rail-end and portal).
                    if (player.level() instanceof ServerLevel sl2 && player instanceof ServerPlayer sp2
                            && tryPortalTransitFromNode(sp2, gs, sl2)) {
                        return;
                    }
                    stop(player, StopReason.END_OF_TRACK);
                    return;
                }
                // Cross-dim graph hop. No post-transit cooldown gate — graph-traversal hops aren't ping-pong vulnerable.
                if (player instanceof ServerPlayer spDim
                        && !gs.fromNode.getLocation().getDimension().equals(spDim.level().dimension())) {
                    teleportThroughGraphHop(spDim, gs, gs.fromNode.getLocation().getDimension());
                    return;
                }
            }
        }

        applyTickMotion(player, gs, absVelocity);
    }

    private static double computeTargetSpeed(GrindState gs, Player player) {
        double slope = gs.experiencedSlope;
        boolean crouchAccelerating = isAcceleratingForGrind(player, gs);
        // Sonic Wind covers sneak top + no-sneak cruise only; descending-coast skips.
        double sonicMult = ModEffects.sonicWindMultiplier(player);
        double base;
        if (crouchAccelerating) {
            base = topSpeed() * sonicMult;
            double factor = slope < 0 ? DOWNHILL_FACTOR : UPHILL_FACTOR;
            base *= Math.max(0.0, 1.0 - slope * factor);
        } else if (slope < 0) {
            double steepness = Math.min(1.0, -slope);
            base = topSpeed() * (DOWNHILL_CRUISE_MIN_FRACTION
                    + (DOWNHILL_CRUISE_MAX_FRACTION - DOWNHILL_CRUISE_MIN_FRACTION) * steepness);
        } else {
            base = CRUISE_SPEED * Config.CRUISE_GRIND_SPEED.get() * sonicMult * Math.max(0.0, 1.0 - slope * UPHILL_FACTOR);
        }

        // Fall back to the raw curve factor if smoother hasn't seeded yet (debug HUD read on first frame).
        double curveFactor = Double.isNaN(gs.smoothedCurveFactor)
                ? (gs.edge.isTurn() ? CURVE_FACTOR : 1.0)
                : gs.smoothedCurveFactor;
        base *= curveFactor;

        return Math.max(MIN_SPEED, base);
    }

    private static double computeAcceleration(GrindState gs, Player player) {
        // Sonic Wind only multiplies the sneak-accel branch (per user request).
        double base = isAcceleratingForGrind(player, gs)
                ? ACCELERATION * Config.SNEAK_ACCELERATION.get() * ModEffects.sonicWindMultiplier(player)
                : ACCELERATION;
        double slope = gs.experiencedSlope;
        if (slope < 0) base *= 1.0 + (-slope) * (DOWNHILL_ACCEL_BOOST - 1.0) * Config.DOWNWARD_MOMENTUM_GAIN.get();
        return base;
    }

    /** Speed/accel scale in [factor..1.0] for any FluidType the player touches; slowest wins. Depth strider lerps toward FLOOR, clamped at level 3. */
    private static double computeFluidMultiplier(Player player) {
        if (!player.isInFluidType()) return 1.0;

        FluidType waterType = NeoForgeMod.WATER_TYPE.value();
        FluidType lavaType = NeoForgeMod.LAVA_TYPE.value();
        double slowest = 1.0;
        for (FluidType type : NeoForgeRegistries.FLUID_TYPES) {
            if (!player.isInFluidType(type)) continue;
            double factor;
            if (type == waterType) {
                factor = WATER_FLUID_FACTOR;
            } else if (type == lavaType) {
                factor = LAVA_FLUID_FACTOR;
            } else {
                factor = OTHER_FLUID_FACTOR;
            }
            if (factor < slowest) slowest = factor;
        }

        if (slowest >= 1.0) return 1.0;

        int dsLevel = Math.min(DEPTH_STRIDER_FLUID_FULL_LEVEL, getDepthStriderLevel(player));
        double dsRatio = dsLevel / (double) DEPTH_STRIDER_FLUID_FULL_LEVEL;
        return slowest + (DEPTH_STRIDER_FLUID_FLOOR - slowest) * dsRatio;
    }

    /** Highest depth strider level across equipped armor. 1.21+ requires registry-holder lookup (Enchantments.DEPTH_STRIDER is a ResourceKey). */
    private static int getDepthStriderLevel(Player player) {
        Holder<Enchantment> ench = player.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.DEPTH_STRIDER);
        return EnchantmentHelper.getEnchantmentLevel(ench, player);
    }

    /** Anti-cheat tripwire since the primary obstacle detector moved client-side. Kept as a function for signature stability and easy re-introduction of latency scaling. */
    private static double computeDynamicMaxDrift(Player player, GrindState gs) {
        return MAX_DRIFT;
    }

    /** Used by ServerMovePacketMixin to suppress handleMovePlayer during cross-dim handoff (server owns position via per-tick anchors). */
    public static boolean shouldRejectMoveDuringCrossDim(Player player) {
        if (PENDING_REGRIND.containsKey(player.getUUID())) return true;
        return isInReattachGrace(player);
    }

    /** Soft-snap that advances gs.position by projecting (player − target) onto the tangent, then setPos onto the rail bar. Linear approximation; iterative arclength projection overkill for a recovery path. */
    private static void snapGsToPlayer(Player player, GrindState gs) {
        Vec3 currentTarget = worldPos(gs).add(0, gs.yOffset, 0);
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 tangent = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);
        tangent = rotateTangentToWorld(gs, tangent);
        double tangentLen = tangent.length();
        if (tangentLen < 1e-6) {
            // Degenerate edge — fall back to a plain hard-snap, no projection.
            player.setPos(currentTarget.x, currentTarget.y, currentTarget.z);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            return;
        }
        Vec3 unitTangent = tangent.scale(1.0 / tangentLen);
        double projectionLength = player.position().subtract(currentTarget).dot(unitTangent);
        gs.position = Math.max(0.0, Math.min(edgeLen, gs.position + projectionLength));
        Vec3 newTarget = worldPos(gs).add(0, gs.yOffset, 0);
        player.setPos(newTarget.x, newTarget.y, newTarget.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
    }

    private static void applyTickMotion(Player player, GrindState gs, double absVelocity) {
        Vec3 target = worldPos(gs).add(0, gs.yOffset, 0);

        // Diagnostic counter: bumped at top so a stop() snapshot in the same tick reflects the post-grace tick number.
        if (gs.startGraceTicks == 0 && gs.reattachGraceTicks == 0) {
            gs.ticksSinceGraceEnded = (gs.ticksSinceGraceEnded < 0) ? 0 : gs.ticksSinceGraceEnded + 1;
        }

        // Snapshot + decrement at top so drift cap, MAX_DRIFT, and stuck all see the same boolean.
        boolean inPostReattachKickSuppress = gs.postReattachKickSuppressTicks > 0;
        if (inPostReattachKickSuppress) gs.postReattachKickSuppressTicks--;

        // Proactive forward-only drift cap during kick-suppress. Rubber-bands gs.position to ≤ POST_REATTACH_DRIFT_CAP ahead of sp.position projected on the edge. No setPos — that would break the client's predict-correct loop.
        if (inPostReattachKickSuppress && gs.startGraceTicks == 0 && gs.reattachGraceTicks == 0) {
            double edgeLen = gs.edge.getLength();
            double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
            Vec3 capTangent = sampleTangent(gs.graph, gs.edge, t);
            Vec3 capChord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
            if (capTangent.x * capChord.x + capTangent.z * capChord.z < 0) capTangent = capTangent.scale(-1);
            capTangent = rotateTangentToWorld(gs, capTangent);
            double capTangentLen = capTangent.length();
            if (capTangentLen >= 1e-6) {
                Vec3 unitCapTangent = capTangent.scale(1.0 / capTangentLen);
                double capProjection = player.position().subtract(target).dot(unitCapTangent);
                // capProjection < 0 = sp behind target. When lag exceeds cap, roll gs.position back.
                if (capProjection < -POST_REATTACH_DRIFT_CAP) {
                    gs.position = Math.max(0.0, Math.min(edgeLen,
                            gs.position + capProjection + POST_REATTACH_DRIFT_CAP));
                    target = worldPos(gs).add(0, gs.yOffset, 0);
                }
            }
        }

        // Catastrophic-desync bailout. Suppressed during both grace windows (drift would be transient there). Reactive snap branch below is now a safety net for the degenerate-tangent path through the proactive cap.
        Vec3 playerPos = player.position();
        double dynamicMaxDrift = computeDynamicMaxDrift(player, gs);
        if (gs.reattachGraceTicks == 0 && gs.startGraceTicks == 0
                && playerPos.subtract(target).lengthSqr() > dynamicMaxDrift * dynamicMaxDrift) {
            if (inPostReattachKickSuppress) {
                snapGsToPlayer(player, gs);
                target = worldPos(gs).add(0, gs.yOffset, 0);
                // Ship serverAuthoritative=true with velocity=ZERO so the client hard-snaps; a non-zero velocity hint would equal the snap distance and ping-pong.
                gs.prevTarget = target;
                sendTargetToPlayer(player, target, Vec3.ZERO, gs.experiencedSlope, true);
                player.fallDistance = 0.0F;
                return;
            } else {
                stop(player, StopReason.MAX_DRIFT);
                return;
            }
        }

        // Velocity hint = target − prevTarget. First tick ships ZERO (client treats as seed). Slope rides along for PlayerNoPhysicsTickMixin.
        Vec3 velocity = (gs.prevTarget == null) ? Vec3.ZERO : target.subtract(gs.prevTarget);
        boolean inReattachGrace = gs.reattachGraceTicks > 0;
        sendTargetToPlayer(player, target, velocity, gs.experiencedSlope, inReattachGrace);
        gs.prevTarget = target;
        player.fallDistance = 0.0F;

        // Server-authoritative anchor during reattach grace; client hard-snaps to same value via the payload flag so the next MovePlayer matches and no drift accrues.
        if (inReattachGrace) {
            player.setPos(target.x, target.y, target.z);
            player.setDeltaMovement(Vec3.ZERO);
            gs.reattachGraceTicks--;
            if (gs.reattachGraceTicks == 0) {
                if (gs.frozenAtReattachStart) {
                    // Timeout failsafe: ack never arrived. Force-unfreeze and re-arm a short transition tail so motion ramps up under hard-snap, not predict-correct from standstill.
                    gs.frozenAtReattachStart = false;
                    gs.reattachGraceTicks = REATTACH_GRACE_AFTER_ACK_TICKS;
                } else {
                    // Post-grace: totalTicks=0 gives STUCK_GRACE a fresh window; portal cooldown reseed prevents re-fire on a snap landing inside a portal frame; postReattachKickSuppress covers the MP handoff gap.
                    gs.totalTicks = 0;
                    seedPortalTransitCooldown(player);
                    gs.postReattachKickSuppressTicks = latencyScaledPostReattachKickSuppress(player);
                }
            }
        }

        // Velocity-based stuck detection. Four suppression gates: startGrace, reattachGrace, STUCK_GRACE_TICKS post-reattach window, and the latency-scaled inPostReattachKickSuppress that bridges the MP MovePlayer round-trip gap after grace ends.
        if (gs.startGraceTicks > 0
                || gs.reattachGraceTicks > 0
                || gs.totalTicks <= STUCK_GRACE_TICKS
                || inPostReattachKickSuppress) {
            gs.stuckTicks = 0;
        } else if (absVelocity < STUCK_VELOCITY_THRESHOLD) {
            gs.stuckTicks++;
            if (gs.stuckTicks >= STUCK_DROP_TICKS) {
                stop(player, StopReason.STUCK);
            }
        } else {
            gs.stuckTicks = 0;
        }
    }

    /** velocity = currentTarget − previousTarget (use ZERO on first tick / after reseed). serverAuthoritative=true makes the client hard-snap during reattach grace. */
    private static void sendTargetToPlayer(Player player, Vec3 target, Vec3 velocity, double slope,
                                           boolean serverAuthoritative) {
        if (player instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new RailGrindTargetPayload(
                target.x, target.y, target.z,
                velocity.x, velocity.y, velocity.z,
                slope,
                serverAuthoritative));
        }
    }

    /** Null-aware sublevel pose helpers; pass-through when gs.subLevel is null. */
    private static Vec3 localToWorld(GrindState gs, Vec3 local) {
        return gs.subLevel == null ? local : gs.subLevel.toWorld(local);
    }

    private static Vec3 rotateTangentToWorld(GrindState gs, Vec3 localUnit) {
        return gs.subLevel == null ? localUnit : gs.subLevel.rotateNormalToWorld(localUnit);
    }

    private static Vec3 sublevelVelocityAt(GrindState gs, Vec3 worldPos) {
        return gs.subLevel == null ? Vec3.ZERO : gs.subLevel.sublevelVelocityAt(worldPos);
    }

    /** Local-frame rail-bar position at t. Skips the sublevel pose since rigid transforms preserve relative arclength. */
    private static Vec3 localBarPos(GrindState gs, double t) {
        Vec3 pos = gs.edge.getPosition(gs.graph, t);
        Vec3 dir = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (dir.x * chord.x + dir.z * chord.z < 0) dir = dir.scale(-1);
        double horizLen = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (horizLen < 1e-6) return pos;
        double off = gs.lateralOffset * gs.lateralSign;
        double rx = -dir.z / horizLen * off;
        double rz =  dir.x / horizLen * off;
        return pos.add(rx, 0, rz);
    }

    /** Bar arclength / centerline arclength at current t. Outer rail > 1, inner rail < 1; tick() divides per-tick budget by this so both bars yield equal world speed. */
    private static double railBarSpeedFactor(GrindState gs) {
        double edgeLen = gs.edge.getLength();
        if (edgeLen < 1e-6) return 1.0;
        double t = Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        final double eps = 0.005;
        double t0 = Math.max(0.0, t - eps);
        double t1 = Math.min(1.0, t + eps);
        if (t1 - t0 < 1e-9) return 1.0;

        Vec3 c0 = gs.edge.getPosition(gs.graph, t0);
        Vec3 c1 = gs.edge.getPosition(gs.graph, t1);
        double cLen = c1.subtract(c0).length();
        if (cLen < 1e-9) return 1.0;

        Vec3 b0 = localBarPos(gs, t0);
        Vec3 b1 = localBarPos(gs, t1);
        double bLen = b1.subtract(b0).length();

        return bLen / cLen;
    }

    /** Gauge-only gate. Use isGrindableMaterial when a TrackMaterial is available; this stays for diagnostic paths with only a TrackType. */
    public static boolean isGrindableTrackType(TrackMaterial.TrackType type) {
        if (type == TrackMaterial.TrackType.STANDARD) return true;
        if (type == null || type.id == null) return false;
        return type.id.equals(RAILWAYS_NARROW_GAUGE)
                || type.id.equals(RAILWAYS_WIDE_GAUGE)
                || type.id.equals(RAILWAYS_MONORAIL);
    }

    /** Gauge gate + phantom-id substring filter ([[phantom_rail_asymmetry]]): narrow/wide phantom variants overwrite the base UNIVERSAL TrackType, so a separate id check is required. */
    public static boolean isGrindableMaterial(@Nullable TrackMaterial material) {
        if (material == null) return false;
        ResourceLocation id = material.id;
        if (id != null && id.getPath().contains("phantom")) return false;
        return isGrindableTrackType(material.trackType);
    }

    /** Wide-gauge gets WIDE_GAUGE_START_RADIUS_BONUS extra reach in proximity scans. */
    private static boolean isWideGaugeTrackType(TrackMaterial.TrackType type) {
        return type != null && type.id != null && type.id.equals(RAILWAYS_WIDE_GAUGE);
    }

    /** Per-gauge bar offset; unknown types fall back to LATERAL_OFFSET. */
    private static double lateralOffsetForType(TrackMaterial.TrackType type) {
        if (type == null || type.id == null) return LATERAL_OFFSET;
        if (type.id.equals(RAILWAYS_NARROW_GAUGE)) return LATERAL_OFFSET_NARROW;
        if (type.id.equals(RAILWAYS_WIDE_GAUGE)) return LATERAL_OFFSET_WIDE;
        if (type.id.equals(RAILWAYS_MONORAIL)) return LATERAL_OFFSET_MONORAIL;
        return LATERAL_OFFSET;
    }

    /** Per-gauge hover height; only monorail overrides Y_OFFSET (full-block beam). */
    private static double yOffsetForType(TrackMaterial.TrackType type) {
        if (type == null || type.id == null) return Y_OFFSET;
        if (type.id.equals(RAILWAYS_MONORAIL)) return Y_OFFSET_MONORAIL;
        return Y_OFFSET;
    }

    /** Returns null (not STANDARD) when no rail is found so callers can keep trying other candidates before falling back to default. */
    private static @Nullable TrackMaterial.TrackType resolveTrackTypeNear(Level level, Vec3 probe) {
        BlockPos bp = BlockPos.containing(probe);
        BlockState s = level.getBlockState(bp);
        if (s.getBlock() instanceof ITrackBlock t) return t.getMaterial().trackType;
        BlockState below = level.getBlockState(bp.below());
        if (below.getBlock() instanceof ITrackBlock t) return t.getMaterial().trackType;
        // Edge-endpoint fallback: 1-block rails can have BlockPos.containing land on air diagonally beyond the rail; scan 3×3 at node Y and one below.
        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) continue;  // already probed
                    BlockState near = level.getBlockState(bp.offset(dx, dy, dz));
                    if (near.getBlock() instanceof ITrackBlock t) return t.getMaterial().trackType;
                }
            }
        }
        return null;
    }

    /** Samples hint → edge midpoint → both endpoints; null bubbles up to the default-offset fallback. */
    private static @Nullable TrackMaterial.TrackType resolveTrackTypeFromCandidates(
            Level level, TrackGraph graph, TrackEdge edge,
            @Nullable Vec3 hint, TrackNode fromNode, TrackNode toNode,
            @Nullable SableSubLevels.SubLevelHandle subLevel) {
        Level sampleLevel = level;
        if (subLevel != null) {
            Level sl = subLevel.getLevel();
            if (sl != null) sampleLevel = sl;
        }
        Vec3[] candidates = new Vec3[] {
                hint,
                edge == null ? null : edge.getPosition(graph, 0.5),
                fromNode == null ? null : fromNode.getLocation().getLocation(),
                toNode == null ? null : toNode.getLocation().getLocation(),
        };
        for (Vec3 c : candidates) {
            if (c == null) continue;
            TrackMaterial.TrackType type = resolveTrackTypeNear(sampleLevel, c);
            if (type != null) return type;
        }
        return null;
    }

    private static Vec3 worldPos(GrindState gs) {
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 pos = gs.edge.getPosition(gs.graph, t);

        // Use sampleTangent (bezier-aware) so the perpendicular rotates correctly through curves; getDirectionAt would lock to chord. Lateral offset stays in local frame so sublevel rotation is preserved.
        Vec3 dir = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (dir.x * chord.x + dir.z * chord.z < 0) dir = dir.scale(-1);
        double horizLen = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        Vec3 local = pos;
        if (horizLen >= 1e-6) {
            double off = gs.lateralOffset * gs.lateralSign;
            double rx = -dir.z / horizLen * off;
            double rz =  dir.x / horizLen * off;
            local = pos.add(rx, 0, rz);
        }
        return localToWorld(gs, local);
    }

    /** Same algorithm as Create's TravellingPoint.steer + .travel filter ([[create_steer_algorithm]]). Filter by canTravelTo, then pick the survivor whose lateral projection is closest to steerSign. */
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
            // Forward-continuity filter (cos 29° threshold).
            if (!gs.edge.canTravelTo(candidate)) continue;
            // Gauge gate; continue (not return) so a multi-way junction can still pick a same-gauge branch.
            if (!gaugeCompatibleForGrind(gs.railTrackType, trackTypeOf(candidate))) continue;
            // Rail-variety gate (catches narrow→narrow_phantom which the gauge gate alone passes).
            if (!isGrindableMaterial(candidate.getTrackMaterial())) continue;
            Vec3 candidateDir = candidate.getDirection(true);
            double dot = cross.dot(candidateDir);
            double diff = Math.abs(targetDot - dot);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestNeighbor = neighbor;
                bestEdge = candidate;
            }
        }

        if (bestEdge == null) return false;

        gs.fromNode = atNode;
        gs.toNode = bestNeighbor;
        gs.edge = bestEdge;
        gs.position = 0;
        // Re-resolve gauge in case we crossed onto a different gauge or a UNIVERSAL wildcard.
        TrackMaterial.TrackType crossType = resolveTrackTypeFromCandidates(
                player.level(), gs.graph, bestEdge, null, atNode, bestNeighbor, gs.subLevel);
        gs.lateralOffset = lateralOffsetForType(crossType);
        gs.yOffset = yOffsetForType(crossType);
        gs.railTrackType = trackTypeOf(bestEdge);
        return true;
    }

    /** Identity check (not null-only): a graph rebuild with a fresh edge instance still counts as removal so the player re-grinds rather than ghost-riding. */
    private static boolean isCurrentEdgePresent(GrindState gs) {
        if (gs.graph == null || gs.fromNode == null || gs.toNode == null || gs.edge == null) return false;
        Map<TrackNode, TrackEdge> conns = gs.graph.getConnectionsFrom(gs.fromNode);
        if (conns == null) return false;
        return conns.get(gs.toNode) == gs.edge;
    }

    /** Null-safe; null type means "unknown" and the gauge gate stays permissive. */
    @Nullable
    private static TrackMaterial.TrackType trackTypeOf(@Nullable TrackEdge edge) {
        if (edge == null) return null;
        TrackMaterial mat = edge.getTrackMaterial();
        return mat == null ? null : mat.trackType;
    }

    /** Matching ids compatible; UNIVERSAL is a wildcard; nulls permissive. Same rule as Steam'n'Rails compat checks. */
    private static boolean gaugeCompatibleForGrind(@Nullable TrackMaterial.TrackType current,
                                                   @Nullable TrackMaterial.TrackType candidate) {
        if (current == null || candidate == null) return true;
        if (current == candidate) return true;
        ResourceLocation curId = current.id;
        ResourceLocation candId = candidate.id;
        if (curId == null || candId == null) return true;
        if (curId.equals(RAILWAYS_UNIVERSAL) || candId.equals(RAILWAYS_UNIVERSAL)) return true;
        return curId.equals(candId);
    }

    private static void syncPose(Player player, boolean grinding) {
        if (player.level().isClientSide) {
            BalancingPoseTracker.setBalancing(player.getUUID(), grinding);
        } else if (player instanceof ServerPlayer serverPlayer) {
            // SILENT_NEXT_START is consumed only on grinding=true so a dismount can't strip a marker meant for the next real start.
            boolean silent = grinding && SILENT_NEXT_START.remove(player.getUUID());
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                serverPlayer, new RailGrindSyncPayload(player.getUUID(), grinding, silent));
        }
    }

    /** Seeds a fresh observer with current grind + lean state ([[rail_grind_tracking_start_resync]]); silent=true since tracking-start is never a fresh grind. */
    public static void syncStateToObserver(ServerPlayer observer, ServerPlayer target) {
        PacketDistributor.sendToPlayer(observer,
            new RailGrindSyncPayload(target.getUUID(), isGrinding(target), true));
        GrindState gs = ACTIVE.get(target.getUUID());
        if (gs != null && gs.steerSign != 0) {
            PacketDistributor.sendToPlayer(observer,
                new RailGrindLeanSyncPayload(target.getUUID(), (byte) gs.steerSign));
        }
    }

    // Set by portal re-grind paths just before a grinding=true sync; consumed exactly once.
    private static final java.util.Set<UUID> SILENT_NEXT_START = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void markNextStartSilent(UUID uuid) {
        SILENT_NEXT_START.add(uuid);
    }

    /** Caller gates on Config.SYNC_DEBUG_TO_CLIENTS. Always sends so always-on overlap/cooldown HUD lines stay current for non-grinders. */
    public static void broadcastDebugSnapshot(ServerPlayer player) {
        GrindDebugInfo info = getGrindDebugInfo(player);
        GrindFrame frame = getGrindFrame(player);
        int overlap = getTrainOverlapTicks(player);
        int fallImmunity = getFallImmunityRemaining(player);
        int startCooldown = getStartCooldownRemaining(player);
        LastDropHudState lastDrop = getLastDropHudState(player);
        boolean hasLastDrop = lastDrop != null;
        int lastDropReasonOrd = hasLastDrop ? lastDrop.reason().ordinal() : StopReason.UNKNOWN.ordinal();
        int lastDropTicksSinceGraceEnded = hasLastDrop ? lastDrop.ticksSinceGraceEnded() : -1;

        RailGrindDebugSyncPayload payload;
        if (info != null && frame != null) {
            payload = new RailGrindDebugSyncPayload(
                    true,
                    info.currentSpeed(), info.targetSpeed(), info.acceleration(), info.topSpeed(),
                    info.experiencedSlope(), info.experiencedCurve(), info.position(), info.edgeLength(),
                    info.stuckTicks(), info.totalTicks(),
                    info.lateralSign(), info.edgeIsTurn(), info.crouchAccelerating(), info.collidingWithTrain(),
                    frame.origin().x, frame.origin().y, frame.origin().z,
                    frame.tangent().x, frame.tangent().y, frame.tangent().z,
                    frame.snapTarget().x, frame.snapTarget().y, frame.snapTarget().z,
                    overlap, fallImmunity, startCooldown,
                    info.driftMargin(),
                    info.reattachGraceTicks(),
                    info.ticksSinceGraceEnded(),
                    hasLastDrop, lastDropReasonOrd, lastDropTicksSinceGraceEnded);
        } else {
            payload = new RailGrindDebugSyncPayload(
                    false,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, false,
                    0, 0, 0, 0, 0, 0, 0, 0, 0,
                    overlap, fallImmunity, startCooldown,
                    Double.NaN,
                    0,
                    -1,
                    hasLastDrop, lastDropReasonOrd, lastDropTicksSinceGraceEnded);
        }
        PacketDistributor.sendToPlayer(player, payload);
    }

    /**
     * Crit + spark mix behind the player's feet for one particle tick. Resolves the travel
     * tangent in world space (sublevel-aware), then dispatches to {@link #spawnSparks} for the
     * spark tier when {@code speedRatio} crosses {@link #GRIND_PARTICLE_SPARK_SPEED_RATIO}.
     */
    private static void spawnGrindParticles(ServerLevel sl, GrindState gs, Player player, double speedRatio) {
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 tangent = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);
        tangent = rotateTangentToWorld(gs, tangent);

        Vec3 feet = player.position();
        final double BACK_OFFSET = 0.4;
        double sx = feet.x - tangent.x * BACK_OFFSET;
        double sy = feet.y + 0.05 - tangent.y * BACK_OFFSET;
        double sz = feet.z - tangent.z * BACK_OFFSET;

        int count = 1 + (int) Math.round(speedRatio);
        double spread = 0.10 + speedRatio * 0.12;
        sl.sendParticles(ParticleTypes.CRIT, sx, sy, sz, count, spread, 0.05, spread, 0.0);

        if (speedRatio >= GRIND_PARTICLE_SPARK_SPEED_RATIO) {
            spawnSparks(sl, sx, sy, sz, tangent, speedRatio, count);
        }
    }

    /** Sends each spark with count=0 so directed velocity survives the network hop (count>0 would randomize Gaussian-style). Yaw fan + Y jitter prevent the stream collapsing to a line. */
    private static void spawnSparks(ServerLevel sl, double sx, double sy, double sz,
                                    Vec3 tangent, double speedRatio, int count) {
        // Project onto horizontal so the fan stays level on slopes.
        double horizLen = Math.sqrt(tangent.x * tangent.x + tangent.z * tangent.z);
        if (horizLen < 1e-6) return;
        double tx = tangent.x / horizLen;
        double tz = tangent.z / horizLen;
        double horizSpeed = SPARK_BASE_HORIZONTAL_SPEED + SPARK_SPEED_BOOST_PER_RATIO * speedRatio;

        var rng = sl.getRandom();
        for (int i = 0; i < count; i++) {
            double yaw = (rng.nextDouble() - 0.5) * SPARK_FAN_SPREAD_RADIANS;
            double cos = Math.cos(yaw), sin = Math.sin(yaw);
            double dirX = -tx * cos + tz * sin;
            double dirZ = -tz * cos - tx * sin;
            double vx = dirX * horizSpeed;
            double vz = dirZ * horizSpeed;
            // Arc height also scales with speedRatio so a fast grind throws sparks higher
            // before gravity wins, matching the wider distance + larger sprite at the same tier.
            double vy = (SPARK_UPWARD_KICK + rng.nextDouble() * SPARK_UPWARD_JITTER) * speedRatio;
            // count=0: dx/dy/dz become the velocity vector, speed is the scalar multiplier.
            sl.sendParticles(ModParticles.SPARK.get(), sx, sy, sz, 0, vx, vy, vz, 1.0);
        }
    }
}
