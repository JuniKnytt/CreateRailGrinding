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
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.BezierConnection;
import com.simibubi.create.content.trains.track.BezierTrackPointLocation;
import com.simibubi.create.content.trains.track.ITrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.math.BlockFace;
import net.juniknytt.createrailgrinding.Config;
import net.juniknytt.createrailgrinding.RailGrind;
import net.juniknytt.createrailgrinding.advancement.ModTriggers;
import net.juniknytt.createrailgrinding.client.BalancingPoseTracker;
import net.juniknytt.createrailgrinding.effect.ModEffects;
import net.juniknytt.createrailgrinding.network.GrindAccelInputPayload;
import net.juniknytt.createrailgrinding.network.GrindParticleBurstPayload;
import net.juniknytt.createrailgrinding.network.RailGrindAccelSyncPayload;
import net.juniknytt.createrailgrinding.network.RailGrindDebugSyncPayload;
import net.juniknytt.createrailgrinding.network.RailGrindLeanSyncPayload;
import net.juniknytt.createrailgrinding.network.RailGrindSyncPayload;
import net.juniknytt.createrailgrinding.network.RailGrindTargetPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
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
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.fluids.FluidType;
import net.juniknytt.createrailgrinding.network.ModNetworking;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RailGrindHandler {
    private static final Map<UUID, GrindState> ACTIVE = new ConcurrentHashMap<>();

    public static volatile double clientLocalSlope = 0.0;
    private static final Map<UUID, Long> FALL_DAMAGE_IMMUNITY_TIME = new ConcurrentHashMap<>();
    private static final int FALL_IMMUNITY_TICKS = 25;
    private static final Map<UUID, Integer> START_COOLDOWN_REMAINING = new ConcurrentHashMap<>();
    private static final int START_COOLDOWN_TICKS = 10;

    private static final Map<UUID, Integer> TRAIN_OVERLAP_TICKS = new ConcurrentHashMap<>();
    private static final int TRAIN_OVERLAP_KICK_TICKS = 5;

    public static double topSpeed() {
        return Config.SERVER_SPEC.isLoaded()
                ? Config.TOP_GRIND_SPEED.get()
                : Config.TOP_GRIND_SPEED.getDefault();
    }
    private static final double CRUISE_SPEED = 0.20;
    private static final double ACCELERATION = 0.005;

    private static final double SPEED_EASE_RATE = 0.08;

    private static final double TARGET_SMOOTH_RATE = 0.18;
    private static final double DOWNHILL_FACTOR = 0.9;
    private static final double UPHILL_FACTOR = 1.5;
    private static final double DOWNHILL_CRUISE_MIN_FRACTION = 0.75;
    private static final double DOWNHILL_CRUISE_MAX_FRACTION = 1.00;
    private static final double DOWNHILL_ACCEL_BOOST = 5.0;
    private static final double CURVE_FACTOR = 0.75;

    private static final double CURVE_FACTOR_ENTER_RATE = 0.05;
    private static final double CURVE_FACTOR_EXIT_RATE  = 0.02;

    private static final double CURVE_SAMPLE_EPSILON = 0.05;

    private static final double CURVE_SIGNAL_GAIN = 5.0;
    private static final double CURVE_SMOOTH_RATE = 0.15;
    private static final double MIN_SPEED = 0.10;

    private static final double WATER_FLUID_FACTOR = 0.5;
    private static final double LAVA_FLUID_FACTOR = 0.25;
    private static final double OTHER_FLUID_FACTOR = 0.5;

    private static final double DEPTH_STRIDER_FLUID_FLOOR = 0.8;
    private static final int DEPTH_STRIDER_FLUID_FULL_LEVEL = 3;
    private static final double Y_OFFSET = 0.5;

    private static final double LATERAL_OFFSET = 1.0;

    private static final ResourceLocation RAILWAYS_NARROW_GAUGE = new ResourceLocation("railways", "narrow_gauge");
    private static final ResourceLocation RAILWAYS_WIDE_GAUGE   = new ResourceLocation("railways", "wide_gauge");
    private static final ResourceLocation RAILWAYS_MONORAIL     = new ResourceLocation("railways", "monorail");

    private static final ResourceLocation RAILWAYS_UNIVERSAL    = new ResourceLocation("railways", "universal");
    private static final double LATERAL_OFFSET_NARROW = LATERAL_OFFSET - 0.5;
    private static final double LATERAL_OFFSET_WIDE   = LATERAL_OFFSET + 0.5;

    private static final double LATERAL_OFFSET_MONORAIL = 0.0;

    private static final double Y_OFFSET_MONORAIL = 1.1;

    private static final double WIDE_GAUGE_START_RADIUS_BONUS = 0.5;

    private static final double MAX_STEP = 2.0;

    public static final double SNAP_BOX_HALF_W = 0.15;
    public static final double SNAP_BOX_HALF_H = 0.15;
    public static final double SNAP_BOX_HALF_L = 0.40;

    private static final double MAX_DRIFT = 20.0;

    public static final double NO_PHYSICS_SLOPE_THRESHOLD = 0.25;

    private static final double STUCK_VELOCITY_THRESHOLD = 0.05;

    private static final int STUCK_DROP_TICKS = 30;
    private static final int STUCK_GRACE_TICKS = 3;

    private static final int START_GRACE_TICKS = 10;

    private static final int REATTACH_GRACE_BASE_TICKS = 100;
    private static final int REATTACH_GRACE_AFTER_ACK_TICKS = 2;

    private static final int POST_REATTACH_KICK_SUPPRESS_BASE_TICKS = 60;
    private static final int POST_REATTACH_KICK_SUPPRESS_LATENCY_MULT = 10;

    private static final double POST_REATTACH_DRIFT_CAP = 5.0;

    private static final int REATTACH_GRACE_LATENCY_MULT = 30;
    private static final double LAUNCH_HORIZONTAL_MULT = 2.0;
    private static final double LAUNCH_VERTICAL_BASE = 0.42;
    private static final double LAUNCH_VERTICAL_SCALE = 0.6;

    public  static final int JUMP_TRICK_CHARGE_INPUT_TIME_MIN = 0;
    public  static final int JUMP_TRICK_CHARGE_INPUT_TIME_MAX = 20;

    private static final double LAUNCH_CHARGE_HORIZONTAL_BONUS_MULT = 1.0;
    private static final double LAUNCH_CHARGE_VERTICAL_BONUS_MULT   = 1.0;

    private static final double PORTAL_REGRIND_SEARCH_RANGE = 8.0;

    private static final int PORTAL_REGRIND_COOLDOWN_TICKS = 80;

    private static final Map<UUID, Integer> PORTAL_TRANSIT_COOLDOWN = new ConcurrentHashMap<>();
    private static final int PORTAL_TRANSIT_COOLDOWN_TICKS = 40;

    private static final Map<UUID, PendingRegrind> PENDING_REGRIND = new ConcurrentHashMap<>();
    private static final int PENDING_REGRIND_MIN_WAIT_TICKS = 5;

    private static final int PENDING_REGRIND_TIMEOUT_TICKS = 100;

    private static final double GRIND_PARTICLE_MIN_SPEED_RATIO = 0.25;

    private record PendingRegrind(
            double carryVelocity,
            int ticksWaited,
            @Nullable GrindState preserved,
            Vec3 anchorPos) {}

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
        TRAIN_OVERLAP("intersecting train"),
        SESSION_BOUNDARY("session boundary"),
        BLOCKED("blocked by obstacle"),
        EXTERNAL_TELEPORT("external teleport"),
        TRACK_REMOVED("track destroyed"),
        SPECTATOR_MODE("spectator mode"),
        UNKNOWN("unknown");

        public final String displayName;
        StopReason(String displayName) { this.displayName = displayName; }

        public static StopReason fromOrdinal(int ord) {
            StopReason[] values = values();
            return (ord >= 0 && ord < values.length) ? values[ord] : UNKNOWN;
        }
    }

    public record LastDropHudState(
            StopReason reason,
            int ticksSinceGraceEnded) {}

    private static final Map<UUID, LastDropHudState> LAST_DROP_HUD_STATE = new ConcurrentHashMap<>();

    private RailGrindHandler() {}

    private static final float FALL_NEGATE_SAFE_DISTANCE = 3.0F;
    private static final int FALL_NEGATE_DAMAGE_THRESHOLD = 20;
    private static final int FALL_NEGATE_GRANT_TICKS = 10;

    private static final class GrindState {
        final TrackGraph graph;
        TrackNode fromNode;
        TrackNode toNode;
        TrackEdge edge;
        double position;
        double currentSpeed;

        double smoothedTarget = Double.NaN;

        double smoothedCurveFactor = Double.NaN;
        int stuckTicks;
        int totalTicks;

        int startGraceTicks;

        int reattachGraceTicks;

        boolean frozenAtReattachStart;
        Vec3 prevPos;

        Vec3 prevTarget;
        double experiencedSlope;

        double experiencedCurve;
        double lateralSign;

        double lateralOffset = LATERAL_OFFSET;

        double yOffset = Y_OFFSET;

        @Nullable TrackMaterial.TrackType railTrackType;
        int steerSign;
        byte accelInputMode = GrindAccelInputPayload.VANILLA;
        boolean lastBroadcastAccel;
        boolean collidingWithTrain;

        int ticksSinceGraceEnded = -1;

        int postReattachKickSuppressTicks = 0;

        boolean fallNegateCandidate;

        GrindState(TrackGraph graph, TrackNode fromNode, TrackNode toNode, TrackEdge edge, double position) {
            this.graph = graph;
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.edge = edge;
            this.position = position;
            this.currentSpeed = CRUISE_SPEED * Config.CRUISE_GRIND_SPEED.get();
            this.lateralSign = 1.0;

        }
    }

    public static void setSteerInput(Player player, int steerSign) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;
        int clamped = Math.max(-1, Math.min(1, steerSign));
        if (clamped == gs.steerSign) return;
        gs.steerSign = clamped;
        if (player instanceof ServerPlayer sp) {
            ModNetworking.toTrackingAndSelf(
                sp, new RailGrindLeanSyncPayload(sp.getUUID(), (byte) clamped));
        }
    }

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

        boolean nowAccel = isAcceleratingForGrind(player, gs);
        if (nowAccel != gs.lastBroadcastAccel && player instanceof ServerPlayer sp) {
            gs.lastBroadcastAccel = nowAccel;
            ModNetworking.toTrackingAndSelf(
                sp, new RailGrindAccelSyncPayload(sp.getUUID(), nowAccel));
        }
    }

    private static int latencyScaledReattachGrace(Player player) {
        int latencyMs = 0;
        if (player instanceof ServerPlayer sp && sp.connection != null) {
            int reported = sp.latency;
            if (reported > 0) latencyMs = reported;
        }
        return REATTACH_GRACE_BASE_TICKS + (latencyMs * REATTACH_GRACE_LATENCY_MULT) / 50;
    }

    private static int latencyScaledPostReattachKickSuppress(Player player) {
        int latencyMs = 0;
        if (player instanceof ServerPlayer sp && sp.connection != null) {
            int reported = sp.latency;
            if (reported > 0) latencyMs = reported;
        }
        return POST_REATTACH_KICK_SUPPRESS_BASE_TICKS + (latencyMs * POST_REATTACH_KICK_SUPPRESS_LATENCY_MULT) / 50;
    }

    public static void releaseReattachGrace(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;
        gs.frozenAtReattachStart = false;
        if (gs.reattachGraceTicks > REATTACH_GRACE_AFTER_ACK_TICKS) {
            gs.reattachGraceTicks = REATTACH_GRACE_AFTER_ACK_TICKS;
        }
    }

    public static boolean isInReattachGrace(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs != null && gs.reattachGraceTicks > 0;
    }

    public static boolean isInStartGrace(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs != null && gs.startGraceTicks > 0;
    }

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

    public static boolean railgrinding(Player player, TrackGraphLocation loc, Vec3 prePos, double entryVelocity) {
        if (isPlayerOnRailGrindCooldown(player)) return false;
        if (isPlayerCrushedByTrain(player)) return false;

        if (player.isPassenger()) return false;
        if (player.isSpectator()) return false;

        TrackGraph graph = loc.graph;
        Couple<TrackNode> nodes = loc.edge.map(graph::locateNode);
        TrackNode first = nodes.getFirst();
        TrackNode second = nodes.getSecond();
        if (first == null || second == null) return false;

        TrackEdge forwardEdge = graph.getConnectionsFrom(first).get(second);
        if (forwardEdge == null) return false;

        if (!isGrindableMaterial(forwardEdge.getTrackMaterial())) return false;

        Vec3 chord = second.getLocation().getLocation().subtract(first.getLocation().getLocation());

        boolean forward = player.getLookAngle().dot(chord) >= 0;

        TrackEdge edge;
        if (forward) {
            edge = forwardEdge;
        } else {
            edge = graph.getConnectionsFrom(second).get(first);
            if (edge == null) {

                edge = forwardEdge;
                forward = true;
            }
        }

        TrackNode fromNode = forward ? first : second;
        TrackNode toNode = forward ? second : first;
        double position = forward ? loc.position : edge.getLength() - loc.position;

        GrindState gs = new GrindState(graph, fromNode, toNode, edge, position);

        gs.currentSpeed *= ModEffects.sonicWindMultiplier(player);

        gs.currentSpeed = Math.min(gs.currentSpeed + entryVelocity, MAX_STEP);

        double edgeLenForSpawn = edge.getLength();
        double tSpawn = edgeLenForSpawn <= 0 ? 0 : Math.min(1.0, Math.max(0.0, position / edgeLenForSpawn));
        Vec3 splineSpawn = edge.getPosition(graph, tSpawn);
        Vec3 dirSpawn = sampleTangent(graph, edge, tSpawn);
        Vec3 spawnChord = toNode.getLocation().getLocation().subtract(fromNode.getLocation().getLocation());
        if (dirSpawn.x * spawnChord.x + dirSpawn.z * spawnChord.z < 0) dirSpawn = dirSpawn.scale(-1);
        double horizLenSpawn = Math.sqrt(dirSpawn.x * dirSpawn.x + dirSpawn.z * dirSpawn.z);
        if (horizLenSpawn > 1e-6) {
            Vec3 prePosForSide = prePos;
            double rxSpawn = -dirSpawn.z / horizLenSpawn;
            double rzSpawn =  dirSpawn.x / horizLenSpawn;
            double sideDot = rxSpawn * (prePosForSide.x - splineSpawn.x) + rzSpawn * (prePosForSide.z - splineSpawn.z);
            gs.lateralSign = sideDot >= 0 ? +1.0 : -1.0;
        }

        TrackMaterial.TrackType seedType = resolveTrackTypeFromCandidates(
                player.level(), graph, edge, splineSpawn, fromNode, toNode);
        gs.lateralOffset = lateralOffsetForType(seedType);
        gs.yOffset = yOffsetForType(seedType);

        gs.railTrackType = trackTypeOf(edge);

        ACTIVE.put(player.getUUID(), gs);

        int latencyTicks = 0;
        if (player instanceof ServerPlayer sp) {
            latencyTicks = sp.latency / 50;
        }
        gs.startGraceTicks = START_GRACE_TICKS + latencyTicks;

        gs.fallNegateCandidate = !(player.getAbilities().instabuild
                || player.isFallFlying()
                || player.isAutoSpinAttack())
                && Math.ceil(player.fallDistance - FALL_NEGATE_SAFE_DISTANCE) >= FALL_NEGATE_DAMAGE_THRESHOLD;

        if (player.getAbilities().flying) {
            player.getAbilities().flying = false;
            if (player instanceof ServerPlayer sp) sp.onUpdateAbilities();
        }

        if (player.isFallFlying()) player.stopFallFlying();
        player.setNoGravity(true);

        Vec3 spawn = worldPos(gs).add(0, gs.yOffset, 0);
        player.setPos(spawn.x, spawn.y, spawn.z);
        if (player instanceof ServerPlayer sp) {
            sp.connection.teleport(spawn.x, spawn.y, spawn.z, sp.getYRot(), sp.getXRot());
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;

        syncPose(player, true);

        sendTargetToPlayer(player, spawn, Vec3.ZERO, 0.0, false);

        if (player instanceof ServerPlayer sp) {
            ModTriggers.RAIL_GRIND.trigger(sp);
        }
        return true;
    }

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

    public static boolean swapGrindToNewLocation(Player player, TrackGraphLocation newLoc, Vec3 teleportTarget) {
        GrindState oldGs = ACTIVE.get(player.getUUID());
        if (oldGs == null) return false;
        double preservedSpeed = oldGs.currentSpeed;
        Vec3 prePos = player.position();

        stop(player, StopReason.TELEPORT_REQUEST);
        START_COOLDOWN_REMAINING.remove(player.getUUID());
        player.teleportTo(teleportTarget.x, teleportTarget.y, teleportTarget.z);
        boolean started = railgrinding(player, newLoc, prePos, 0.0);
        if (started) {
            GrindState newGs = ACTIVE.get(player.getUUID());
            if (newGs != null) newGs.currentSpeed = Math.min(preservedSpeed, MAX_STEP);
        }
        return started;
    }

    @Nullable
    public static LastDropHudState getLastDropHudState(Player player) {
        return LAST_DROP_HUD_STATE.get(player.getUUID());
    }

    public static boolean isOnPostPortalTransitCooldown(Player player) {
        if (isInReattachGrace(player)) return true;
        Integer remaining = PORTAL_TRANSIT_COOLDOWN.get(player.getUUID());
        return remaining != null && remaining > 0;
    }

    public static void seedPortalTransitCooldown(Player player) {
        PORTAL_TRANSIT_COOLDOWN.put(player.getUUID(), PORTAL_TRANSIT_COOLDOWN_TICKS);
    }

    public static void enqueuePending(ServerPlayer sp, double carryVelocity, @Nullable GrindState preserved) {
        seedPortalTransitCooldown(sp);

        sp.setNoGravity(false);
        sp.setNoGravity(true);
        sp.noPhysics = true;
        sp.fallDistance = 0.0F;
        sp.setDeltaMovement(Vec3.ZERO);
        Vec3 anchor = sp.position();
        PENDING_REGRIND.put(sp.getUUID(), new PendingRegrind(carryVelocity, 0, preserved, anchor));
    }

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

            gs.smoothedTarget = Double.NaN;
            gs.smoothedCurveFactor = Double.NaN;
            gs.experiencedCurve = 0.0;
            gs.prevTarget = null;
            gs.reattachGraceTicks = latencyScaledReattachGrace(sp);
            gs.totalTicks = 0;

            gs.frozenAtReattachStart = true;
            sp.connection.teleport(spawn.x, spawn.y, spawn.z, sp.getYRot(), sp.getXRot());
            markNextStartSilent(sp.getUUID());
            syncPose(sp, true);

            sendTargetToPlayer(sp, spawn, Vec3.ZERO, gs.experiencedSlope, true);
            return;
        }

        RailHit hit = findNearestRailLocation(sp.level(), sp.position(), PORTAL_REGRIND_SEARCH_RANGE);
        if (hit == null) {
            cleanupAbandonedPending(sp);
            return;
        }
        markNextStartSilent(sp.getUUID());
        railgrinding(sp, hit.loc(), sp.position(), pending.carryVelocity());

        GrindState newGs = ACTIVE.get(sp.getUUID());
        if (newGs != null) {
            newGs.reattachGraceTicks = latencyScaledReattachGrace(sp);

            newGs.startGraceTicks = 0;
            newGs.frozenAtReattachStart = true;

            newGs.currentSpeed = Math.min(pending.carryVelocity(), MAX_STEP);
        }
    }

    private static void cleanupAbandonedPending(ServerPlayer sp) {
        sp.setNoGravity(false);
        sp.noPhysics = false;
        FALL_DAMAGE_IMMUNITY_TIME.put(sp.getUUID(), sp.level().getGameTime() + FALL_IMMUNITY_TICKS);
        syncPose(sp, false);
    }

    public static void tickPortalTransitCooldown(Player player) {
        Integer remaining = PORTAL_TRANSIT_COOLDOWN.get(player.getUUID());
        if (remaining == null) return;
        int next = remaining - 1;
        if (next <= 0) PORTAL_TRANSIT_COOLDOWN.remove(player.getUUID());
        else PORTAL_TRANSIT_COOLDOWN.put(player.getUUID(), next);
    }

    private static boolean tryPortalTransit(ServerPlayer sp, GrindState oldState, ServerLevel level) {
        if (isOnPostPortalTransitCooldown(sp)) return false;
        BlockPos here = sp.blockPosition();
        BlockState portalState = level.getBlockState(here);
        if (!PortalTrackProvider.isSupportedPortal(portalState)) return false;

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

        double carryVelocity = oldState.currentSpeed;
        ACTIVE.remove(sp.getUUID());

        BlockFace exitFace = exit.face();
        BlockPos exitTargetPos = exitFace.getConnectedPos();
        Vec3 targetPos = Vec3.atCenterOf(exitTargetPos);
        float yaw = exitFace.getFace().toYRot();
        sp.teleportTo(exit.level(), targetPos.x, targetPos.y, targetPos.z, yaw, sp.getXRot());
        sp.setPortalCooldown(PORTAL_REGRIND_COOLDOWN_TICKS);

        finishCrossDimRegrind(sp, carryVelocity);
        return true;
    }

    private static void finishCrossDimRegrind(ServerPlayer sp, double carryVelocity) {
        enqueuePending(sp, carryVelocity, null);
    }

    public static void handleDimensionChange(ServerPlayer sp) {
        GrindState gs = ACTIVE.remove(sp.getUUID());
        if (gs == null) return;
        finishCrossDimRegrind(sp, gs.currentSpeed);
    }

    private static void teleportThroughGraphHop(ServerPlayer sp, GrindState gs, ResourceKey<Level> targetDim) {
        ServerLevel newLevel = sp.server.getLevel(targetDim);
        if (newLevel == null) {
            ACTIVE.remove(sp.getUUID());
            sp.setNoGravity(false);
            sp.noPhysics = false;
            syncPose(sp, false);
            return;
        }

        TrackMaterial.TrackType destType = resolveTrackTypeFromCandidates(
                newLevel, gs.graph, gs.edge, null, gs.fromNode, gs.toNode);
        gs.lateralOffset = lateralOffsetForType(destType);
        gs.yOffset = yOffsetForType(destType);

        Vec3 targetPos = worldPos(gs).add(0, gs.yOffset, 0);

        ACTIVE.remove(sp.getUUID());
        sp.teleportTo(newLevel, targetPos.x, targetPos.y, targetPos.z, sp.getYRot(), sp.getXRot());
        sp.setPortalCooldown(PORTAL_REGRIND_COOLDOWN_TICKS);

        enqueuePending(sp, gs.currentSpeed, gs);
    }

    private static boolean tryPortalTransitFromNode(ServerPlayer sp, GrindState gs, ServerLevel level) {
        if (isOnPostPortalTransitCooldown(sp)) return false;
        Vec3 nodeVec = gs.toNode.getLocation().getLocation();
        BlockPos nodeBlock = BlockPos.containing(nodeVec);

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

    public static boolean hasFallImmunity(Player player) {
        Long time = FALL_DAMAGE_IMMUNITY_TIME.get(player.getUUID());
        if (time == null) return false;
        if (player.level().getGameTime() >= time) {
            FALL_DAMAGE_IMMUNITY_TIME.remove(player.getUUID());
            return false;
        }
        return true;
    }

    public static int getFallImmunityRemaining(Player player) {
        Long time = FALL_DAMAGE_IMMUNITY_TIME.get(player.getUUID());
        if (time == null) return 0;
        long remaining = time - player.level().getGameTime();
        return remaining > 0 ? (int) remaining : 0;
    }

    public static boolean isPlayerOnRailGrindCooldown(Player player) {
        Integer remaining = START_COOLDOWN_REMAINING.get(player.getUUID());
        return remaining != null && remaining > 0;
    }

    public static int getStartCooldownRemaining(Player player) {
        Integer remaining = START_COOLDOWN_REMAINING.get(player.getUUID());
        return remaining == null ? 0 : remaining;
    }

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

    public static boolean isPlayerCrushedByTrain(Player player) {
        Integer ticks = TRAIN_OVERLAP_TICKS.get(player.getUUID());
        return ticks != null && ticks >= TRAIN_OVERLAP_KICK_TICKS;
    }

    public static int getTrainOverlapTicks(Player player) {
        return TRAIN_OVERLAP_TICKS.getOrDefault(player.getUUID(), 0);
    }

    private static final double GRAPH_SCAN_SAMPLES_PER_BLOCK = 4.0;
    private static final int GRAPH_SCAN_MIN_SAMPLES = 8;
    private static final int GRAPH_SCAN_MAX_RAIL_LENGTH = 32;
    private static final int GRAPH_SCAN_MAX_SAMPLES =
            Math.max(GRAPH_SCAN_MIN_SAMPLES, (int) Math.ceil(GRAPH_SCAN_MAX_RAIL_LENGTH * GRAPH_SCAN_SAMPLES_PER_BLOCK));

    public record RailHit(TrackGraphLocation loc) {}

    public static RailHit findNearestRailLocation(Level level, Vec3 origin, double maxDist) {

        TrackGraphLocation[] bestLoc = { null };

        double maxDistOuter = maxDist + WIDE_GAUGE_START_RADIUS_BONUS;
        double[] bestDistSq = { maxDistOuter * maxDistOuter };
        double baseDistSq = maxDist * maxDist;

        forEachGrindScanLevel(level, origin, (lvl, originInLevel, originWorld) ->
                scanLevelForRails(lvl, originInLevel, originWorld, bestLoc, bestDistSq, baseDistSq));

        if (bestLoc[0] == null) {
            forEachGrindScanLevel(level, origin, (lvl, originInLevel, originWorld) ->
                    scanGraphsForRails(lvl, originInLevel, originWorld, bestLoc, bestDistSq, baseDistSq));
        }

        return bestLoc[0] == null ? null : new RailHit(bestLoc[0]);
    }

    public static @Nullable TrackGraphLocation findNearestRailInLevel(Level level, Vec3 origin, double maxDist) {
        TrackGraphLocation[] bestLoc = { null };
        double maxDistOuter = maxDist + WIDE_GAUGE_START_RADIUS_BONUS;
        double[] bestDistSq = { maxDistOuter * maxDistOuter };
        double baseDistSq = maxDist * maxDist;
        scanLevelForRails(level, origin, origin, bestLoc, bestDistSq, baseDistSq);
        if (bestLoc[0] == null) {
            scanGraphsForRails(level, origin, origin, bestLoc, bestDistSq, baseDistSq);
        }
        return bestLoc[0];
    }

    @FunctionalInterface
    private interface GrindLevelScanner {
        void scan(Level level, Vec3 originInLevel, Vec3 originWorld);
    }

    private static void forEachGrindScanLevel(Level level, Vec3 origin, GrindLevelScanner scanner) {
        scanner.scan(level, origin, origin);
    }

    private static void scanLevelForRails(
            Level level, Vec3 originInLevel, Vec3 originWorld,
            TrackGraphLocation[] bestLoc,
            double[] bestDistSq,
            double baseDistSq) {
        BlockPos center = BlockPos.containing(originInLevel);
        int blockRange = (int) Math.ceil(Math.sqrt(bestDistSq[0])) + 1;

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
                    Vec3 cursorWorld = cursorCenter;
                    double d2 = cursorWorld.distanceToSqr(originWorld);

                    double candidateCap = isWideGaugeTrackType(trackType) ? bestDistSq[0] : Math.min(baseDistSq, bestDistSq[0]);
                    if (d2 < candidateCap) {
                        BlockPos pos = cursor.immutable();
                        TrackGraphLocation loc = resolvePlainTrack(level, pos, state, track);
                        if (loc != null) {
                            bestDistSq[0] = d2;
                            bestLoc[0] = loc;
                        }
                    }
                }
            }
        }

        int chunkX = SectionPos.blockToSectionCoord(center.getX());
        int chunkZ = SectionPos.blockToSectionCoord(center.getZ());
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                ChunkAccess chunk = level.getChunk(chunkX + cx, chunkZ + cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockPos bePos : chunk.getBlockEntitiesPos()) {
                    BlockEntity be = level.getBlockEntity(bePos);
                    if (!(be instanceof TrackBlockEntity tbe)) continue;

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
                            Vec3 pWorld = p;
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

                                candidateCapBezier = wideGauge ? bestDistSq[0] : Math.min(baseDistSq, bestDistSq[0]);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void scanGraphsForRails(
            Level level, Vec3 originInLevel, Vec3 originWorld,
            TrackGraphLocation[] bestLoc,
            double[] bestDistSq,
            double baseDistSq) {

        ResourceKey<Level> dimension = level.dimension();
        Collection<TrackGraph> graphs = Create.RAILWAYS.sided(level).trackNetworks.values();
        int maxSamples = GRAPH_SCAN_MAX_SAMPLES;

        for (TrackGraph graph : graphs) {
            AABB graphBox;
            try {
                graphBox = graph.getBounds(level).box;
            } catch (Exception e) {
                graphBox = null;
            }
            if (graphBox != null && !graphBox.inflate(Math.sqrt(bestDistSq[0])).contains(originInLevel)) continue;

            for (TrackNodeLocation nodeLoc : graph.getNodes()) {
                if (!dimension.equals(nodeLoc.getDimension())) continue;
                TrackNode node = graph.locateNode(nodeLoc);
                if (node == null) continue;
                Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(node);
                if (connections == null) continue;

                for (Map.Entry<TrackNode, TrackEdge> entry : connections.entrySet()) {
                    TrackNode other = entry.getKey();
                    if (node.getNetId() >= other.getNetId()) continue;
                    TrackEdge edge = entry.getValue();
                    if (edge.isInterDimensional()) continue;

                    TrackMaterial material = edge.getTrackMaterial();
                    if (!isGrindableMaterial(material)) continue;

                    double edgeLen = edge.getLength();
                    if (edgeLen <= 1e-6) continue;

                    boolean wideGauge = isWideGaugeTrackType(material.trackType);
                    double candidateCap = wideGauge ? bestDistSq[0] : Math.min(baseDistSq, bestDistSq[0]);

                    BezierConnection conn = edge.isTurn() ? edge.getTurn() : null;
                    AABB edgeBox = conn != null
                            ? conn.getBounds()
                            : new AABB(node.getLocation().getLocation(), other.getLocation().getLocation());
                    if (!edgeBox.inflate(Math.sqrt(candidateCap)).contains(originInLevel)) continue;

                    if (!graphEdgeHasRealBacking(level, node, other, conn != null)) continue;

                    int samples = (int) Math.ceil(edgeLen * GRAPH_SCAN_SAMPLES_PER_BLOCK);
                    samples = Math.max(GRAPH_SCAN_MIN_SAMPLES, Math.min(maxSamples, samples));

                    for (int i = 0; i <= samples; i++) {
                        double t = i / (double) samples;
                        Vec3 p = edge.getPosition(graph, t);
                        Vec3 pWorld = p;
                        double d2 = pWorld.distanceToSqr(originWorld);
                        if (d2 >= candidateCap) continue;

                        TrackGraphLocation loc = new TrackGraphLocation();
                        loc.graph = graph;
                        loc.edge = Couple.create(node.getLocation(), other.getLocation());
                        loc.position = t * edgeLen;

                        bestDistSq[0] = d2;
                        bestLoc[0] = loc;
                        candidateCap = wideGauge ? bestDistSq[0] : Math.min(baseDistSq, bestDistSq[0]);
                    }
                }
            }
        }
    }

    private static final int ENDPOINT_PROBE_DY = 5;

    private static boolean loadedTrackBlockNear(Level level, Vec3 worldPoint) {
        BlockPos base = BlockPos.containing(worldPoint);
        for (int dy = -ENDPOINT_PROBE_DY; dy <= ENDPOINT_PROBE_DY; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = base.offset(dx, dy, dz);
                    if (level.isLoaded(p) && level.getBlockState(p).getBlock() instanceof ITrackBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean graphEdgeHasRealBacking(Level level, TrackNode from, TrackNode to, boolean isTurn) {
        Vec3 a = from.getLocation().getLocation();
        Vec3 b = to.getLocation().getLocation();
        if (loadedTrackBlockNear(level, a) || loadedTrackBlockNear(level, b)) return true;
        return !isTurn && loadedTrackBlockNear(level, a.add(b).scale(0.5));
    }

    @Nullable
    public static Vec3 pickGrindableCurvePointOnRay(Level level, Vec3 eye, Vec3 target, double maxDistSq) {
        ResourceKey<Level> dimension = level.dimension();
        Collection<TrackGraph> graphs = Create.RAILWAYS.sided(level).trackNetworks.values();
        int maxSamples = GRAPH_SCAN_MAX_SAMPLES;
        double tolReach = Math.sqrt(maxDistSq);

        Vec3 best = null;
        double bestEyeDistSq = Double.MAX_VALUE;

        for (TrackGraph graph : graphs) {
            AABB graphBox;
            try {
                graphBox = graph.getBounds(level).box;
            } catch (Exception e) {
                graphBox = null;
            }
            if (graphBox != null) {
                AABB inflated = graphBox.inflate(tolReach + 1.0);
                if (!inflated.contains(eye) && inflated.clip(eye, target).isEmpty()) continue;
            }

            for (TrackNodeLocation nodeLoc : graph.getNodes()) {
                if (!dimension.equals(nodeLoc.getDimension())) continue;
                TrackNode node = graph.locateNode(nodeLoc);
                if (node == null) continue;
                Map<TrackNode, TrackEdge> connections = graph.getConnectionsFrom(node);
                if (connections == null) continue;

                for (Map.Entry<TrackNode, TrackEdge> entry : connections.entrySet()) {
                    TrackNode other = entry.getKey();
                    if (node.getNetId() >= other.getNetId()) continue;
                    TrackEdge edge = entry.getValue();
                    if (edge.isInterDimensional()) continue;
                    if (!isGrindableMaterial(edge.getTrackMaterial())) continue;

                    double edgeLen = edge.getLength();
                    if (edgeLen <= 1e-6) continue;

                    BezierConnection conn = edge.isTurn() ? edge.getTurn() : null;
                    AABB edgeBox = conn != null
                            ? conn.getBounds()
                            : new AABB(node.getLocation().getLocation(), other.getLocation().getLocation());
                    AABB inflatedEdge = edgeBox.inflate(tolReach + 0.5);
                    if (!inflatedEdge.contains(eye) && inflatedEdge.clip(eye, target).isEmpty()) continue;

                    int samples = (int) Math.ceil(edgeLen * GRAPH_SCAN_SAMPLES_PER_BLOCK);
                    samples = Math.max(GRAPH_SCAN_MIN_SAMPLES, Math.min(maxSamples, samples));

                    for (int i = 0; i <= samples; i++) {
                        double t = i / (double) samples;
                        Vec3 p = edge.getPosition(graph, t);
                        if (distancePointToSegmentSq(p, eye, target) > maxDistSq) continue;
                        double eyeDistSq = p.distanceToSqr(eye);
                        if (eyeDistSq < bestEyeDistSq) {
                            bestEyeDistSq = eyeDistSq;
                            best = p;
                        }
                    }
                }
            }
        }
        return best;
    }

    private static double distancePointToSegmentSq(Vec3 p, Vec3 a, Vec3 b) {
        Vec3 ab = b.subtract(a);
        double abLenSq = ab.lengthSqr();
        if (abLenSq < 1.0e-9) return p.distanceToSqr(a);
        double t = Math.max(0.0, Math.min(1.0, p.subtract(a).dot(ab) / abLenSq));
        return p.distanceToSqr(a.add(ab.scale(t)));
    }

    private static TrackGraphLocation resolvePlainTrack(Level level, BlockPos pos, BlockState state, ITrackBlock track) {
        for (Vec3 axis : track.getTrackAxes(level, pos, state)) {
            for (Direction.AxisDirection dir : Direction.AxisDirection.values()) {
                TrackGraphLocation loc = TrackGraphHelper.getGraphLocationAt(level, pos, dir, axis);
                if (loc != null) return loc;
            }
        }
        return null;
    }

    public static void stopWithLaunch(Player player, int chargeTicks, StopReason reason) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) {
            stop(player, reason);
            return;
        }

        double speed = gs.currentSpeed;
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 tangent = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());

        if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);
        tangent = rotateTangentToWorld(gs, tangent);

        double chargeRatio = computeChargeRatio(chargeTicks);
        double speedMult  = Config.RAIL_JUMP_MOMENTUM.get();

        double chargeMult = Config.RAIL_JUMP_CHARGE.get() * ModEffects.sonicWindMultiplier(player);

        double horizMag = speed * LAUNCH_HORIZONTAL_MULT * speedMult
                * (1.0 + chargeRatio * LAUNCH_CHARGE_HORIZONTAL_BONUS_MULT * chargeMult);
        double vertBoost = (LAUNCH_VERTICAL_BASE + speed * LAUNCH_VERTICAL_SCALE * speedMult)
                * (1.0 + chargeRatio * LAUNCH_CHARGE_VERTICAL_BONUS_MULT * chargeMult);
        Vec3 launch = new Vec3(
            tangent.x * horizMag,
            tangent.y * horizMag + vertBoost,
            tangent.z * horizMag
        );

        stop(player, reason);
        player.setDeltaMovement(launch);
        player.hurtMarked = true;
        player.fallDistance = 0.0F;

        if (Config.AUTO_DEPLOY_ELYTRA.get()) {
            ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.canElytraFly(player)) {
                player.startFallFlying();
            }
        }
    }

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

    public static double getCurrentSpeed(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs == null ? 0.0 : gs.currentSpeed;
    }

    public static boolean isOnDifferentTrackGraph(Player player, @Nullable TrackGraph otherGraph) {
        if (otherGraph == null) return false;
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs != null && gs.graph != otherGraph;
    }

    public static double getExperiencedSlope(Player player) {
        if (player.level().isClientSide()) {
            return clientLocalSlope;
        }
        GrindState gs = ACTIVE.get(player.getUUID());
        return gs == null ? 0.0 : gs.experiencedSlope;
    }

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

        double cross = px * nz - pz * nx;
        double scaled = cross * CURVE_SIGNAL_GAIN;
        return Math.max(-1.0, Math.min(1.0, scaled));
    }

    public static void tick(Player player) {
        GrindState gs = ACTIVE.get(player.getUUID());
        if (gs == null) return;

        if (!isInReattachGrace(player)
                && ServerChainConveyorHandler.hangingPlayers.containsKey(player.getUUID())) {
            stop(player, StopReason.CHAIN_HANDOFF);
            return;
        }

        if (!isInReattachGrace(player) && player.isAutoSpinAttack()) {
            stop(player, StopReason.AUTO_SPIN);
            return;
        }

        if (player.isSpectator()) {
            stop(player, StopReason.SPECTATOR_MODE);
            return;
        }

        if (!isInReattachGrace(player) && !isCurrentEdgePresent(gs)) {
            stop(player, StopReason.TRACK_REMOVED);
            return;
        }

        if (player.level() instanceof ServerLevel sl && player instanceof ServerPlayer sp
                && tryPortalTransit(sp, gs, sl)) {
            return;
        }

        if (gs.startGraceTicks > 0) {
            gs.startGraceTicks--;
            if (gs.startGraceTicks == 0) {
                player.noPhysics = true;
            }
        }

        gs.totalTicks++;

        if (gs.fallNegateCandidate && gs.totalTicks >= FALL_NEGATE_GRANT_TICKS
                && player instanceof ServerPlayer sp) {
            gs.fallNegateCandidate = false;
            ModTriggers.FALLDAMAGE_NEGATE.trigger(sp);
        }

        if (player.level() instanceof ServerLevel sl) {
            double speedRatio = Math.min(2.0, gs.currentSpeed / topSpeed());
            if (speedRatio >= GRIND_PARTICLE_MIN_SPEED_RATIO) {
                int interval = Math.max(1, (int) Math.round(1.0 / speedRatio));
                if (gs.totalTicks % interval == 0) {
                    spawnGrindParticles(sl, gs, player, speedRatio);
                }
            }
        }

        if (!player.noPhysics)     player.noPhysics = true;
        if (!player.isNoGravity()) player.setNoGravity(true);
        if (player.isFallFlying()) player.stopFallFlying();

        player.setOnGround(false);

        Vec3 currentPos = player.position();
        double absVelocity = 0.0;
        if (gs.prevPos != null) {
            Vec3 motion = currentPos.subtract(gs.prevPos);
            absVelocity = motion.length();
            if (absVelocity > 1e-4) gs.experiencedSlope = motion.y / absVelocity;
        }
        gs.prevPos = currentPos;

        double rawCurve = computeRawExperiencedCurve(gs);
        gs.experiencedCurve += (rawCurve - gs.experiencedCurve) * CURVE_SMOOTH_RATE;

        if (gs.frozenAtReattachStart) {
            applyTickMotion(player, gs, absVelocity);
            return;
        }

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

        if (Double.isNaN(gs.smoothedTarget)) {
            gs.smoothedTarget = targetSpeed;
        } else {
            gs.smoothedTarget += (targetSpeed - gs.smoothedTarget) * TARGET_SMOOTH_RATE;
        }

        if (fluidMult < 1.0 && gs.currentSpeed > targetSpeed) {
            gs.currentSpeed = targetSpeed;
            gs.smoothedTarget = targetSpeed;
        }

        double diff = gs.smoothedTarget - gs.currentSpeed;
        double step = diff * SPEED_EASE_RATE;
        if (step > accel) step = accel;
        else if (step < -accel) step = -accel;
        gs.currentSpeed += step;

        if (gs.currentSpeed <= 1e-6) {
            applyTickMotion(player, gs, absVelocity);
            return;
        }

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

                    if (player.level() instanceof ServerLevel sl2 && player instanceof ServerPlayer sp2
                            && tryPortalTransitFromNode(sp2, gs, sl2)) {
                        return;
                    }
                    stop(player, StopReason.END_OF_TRACK);
                    return;
                }

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

        double curveFactor = Double.isNaN(gs.smoothedCurveFactor)
                ? (gs.edge.isTurn() ? CURVE_FACTOR : 1.0)
                : gs.smoothedCurveFactor;
        base *= curveFactor;

        return Math.max(MIN_SPEED, base);
    }

    private static double computeAcceleration(GrindState gs, Player player) {

        double base = isAcceleratingForGrind(player, gs)
                ? ACCELERATION * Config.CROUCH_ACCELERATION.get() * ModEffects.sonicWindMultiplier(player)
                : ACCELERATION;
        double slope = gs.experiencedSlope;
        if (slope < 0) base *= 1.0 + (-slope) * (DOWNHILL_ACCEL_BOOST - 1.0) * Config.DOWNWARD_MOMENTUM_GAIN.get();
        return base;
    }

    private static double computeFluidMultiplier(Player player) {
        if (!player.isInFluidType()) return 1.0;

        FluidType waterType = ForgeMod.WATER_TYPE.get();
        FluidType lavaType = ForgeMod.LAVA_TYPE.get();
        double slowest = 1.0;
        for (FluidType type : ForgeRegistries.FLUID_TYPES) {
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

    private static int getDepthStriderLevel(Player player) {
        return EnchantmentHelper.getEnchantmentLevel(Enchantments.DEPTH_STRIDER, player);
    }

    private static double computeDynamicMaxDrift(Player player, GrindState gs) {
        return MAX_DRIFT;
    }

    public static boolean shouldRejectMoveDuringCrossDim(Player player) {
        if (PENDING_REGRIND.containsKey(player.getUUID())) return true;
        return isInReattachGrace(player);
    }

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

        if (gs.startGraceTicks == 0 && gs.reattachGraceTicks == 0) {
            gs.ticksSinceGraceEnded = (gs.ticksSinceGraceEnded < 0) ? 0 : gs.ticksSinceGraceEnded + 1;
        }

        boolean inPostReattachKickSuppress = gs.postReattachKickSuppressTicks > 0;
        if (inPostReattachKickSuppress) gs.postReattachKickSuppressTicks--;

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

                if (capProjection < -POST_REATTACH_DRIFT_CAP) {
                    gs.position = Math.max(0.0, Math.min(edgeLen,
                            gs.position + capProjection + POST_REATTACH_DRIFT_CAP));
                    target = worldPos(gs).add(0, gs.yOffset, 0);
                }
            }
        }

        Vec3 playerPos = player.position();
        double dynamicMaxDrift = computeDynamicMaxDrift(player, gs);
        if (gs.reattachGraceTicks == 0 && gs.startGraceTicks == 0
                && playerPos.subtract(target).lengthSqr() > dynamicMaxDrift * dynamicMaxDrift) {
            if (inPostReattachKickSuppress) {
                snapGsToPlayer(player, gs);
                target = worldPos(gs).add(0, gs.yOffset, 0);

                gs.prevTarget = target;
                sendTargetToPlayer(player, target, Vec3.ZERO, gs.experiencedSlope, true);
                player.fallDistance = 0.0F;
                return;
            } else {
                stop(player, StopReason.MAX_DRIFT);
                return;
            }
        }

        Vec3 velocity = (gs.prevTarget == null) ? Vec3.ZERO : target.subtract(gs.prevTarget);
        boolean inReattachGrace = gs.reattachGraceTicks > 0;
        sendTargetToPlayer(player, target, velocity, gs.experiencedSlope, inReattachGrace);
        gs.prevTarget = target;
        player.fallDistance = 0.0F;

        if (inReattachGrace) {
            player.setPos(target.x, target.y, target.z);
            player.setDeltaMovement(Vec3.ZERO);
            gs.reattachGraceTicks--;
            if (gs.reattachGraceTicks == 0) {
                if (gs.frozenAtReattachStart) {

                    gs.frozenAtReattachStart = false;
                    gs.reattachGraceTicks = REATTACH_GRACE_AFTER_ACK_TICKS;
                } else {

                    gs.totalTicks = 0;
                    seedPortalTransitCooldown(player);
                    gs.postReattachKickSuppressTicks = latencyScaledPostReattachKickSuppress(player);
                }
            }
        }

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

    private static void sendTargetToPlayer(Player player, Vec3 target, Vec3 velocity, double slope,
                                           boolean serverAuthoritative) {
        if (player instanceof ServerPlayer sp) {
            ModNetworking.toPlayer(sp, new RailGrindTargetPayload(
                target.x, target.y, target.z,
                velocity.x, velocity.y, velocity.z,
                slope,
                serverAuthoritative));
        }
    }

    private static Vec3 localToWorld(GrindState gs, Vec3 local) {
        return local;
    }

    private static Vec3 rotateTangentToWorld(GrindState gs, Vec3 localUnit) {
        return localUnit;
    }

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

    public static boolean isGrindableTrackType(TrackMaterial.TrackType type) {
        if (type == TrackMaterial.TrackType.STANDARD) return true;
        if (type == null || type.id == null) return false;
        return type.id.equals(RAILWAYS_NARROW_GAUGE)
                || type.id.equals(RAILWAYS_WIDE_GAUGE)
                || type.id.equals(RAILWAYS_MONORAIL);
    }

    public static boolean isGrindableMaterial(@Nullable TrackMaterial material) {
        if (material == null) return false;
        ResourceLocation id = material.id;
        if (id != null && id.getPath().contains("phantom")) return false;
        return isGrindableTrackType(material.trackType);
    }

    private static boolean isWideGaugeTrackType(TrackMaterial.TrackType type) {
        return type != null && type.id != null && type.id.equals(RAILWAYS_WIDE_GAUGE);
    }

    private static double lateralOffsetForType(TrackMaterial.TrackType type) {
        if (type == null || type.id == null) return LATERAL_OFFSET;
        if (type.id.equals(RAILWAYS_NARROW_GAUGE)) return LATERAL_OFFSET_NARROW;
        if (type.id.equals(RAILWAYS_WIDE_GAUGE)) return LATERAL_OFFSET_WIDE;
        if (type.id.equals(RAILWAYS_MONORAIL)) return LATERAL_OFFSET_MONORAIL;
        return LATERAL_OFFSET;
    }

    private static double yOffsetForType(TrackMaterial.TrackType type) {
        if (type == null || type.id == null) return Y_OFFSET;
        if (type.id.equals(RAILWAYS_MONORAIL)) return Y_OFFSET_MONORAIL;
        return Y_OFFSET;
    }

    private static @Nullable TrackMaterial.TrackType resolveTrackTypeNear(Level level, Vec3 probe) {
        BlockPos bp = BlockPos.containing(probe);
        BlockState s = level.getBlockState(bp);
        if (s.getBlock() instanceof ITrackBlock t) return t.getMaterial().trackType;
        BlockState below = level.getBlockState(bp.below());
        if (below.getBlock() instanceof ITrackBlock t) return t.getMaterial().trackType;

        for (int dy = 0; dy >= -1; dy--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == -1)) continue;
                    BlockState near = level.getBlockState(bp.offset(dx, dy, dz));
                    if (near.getBlock() instanceof ITrackBlock t) return t.getMaterial().trackType;
                }
            }
        }
        return null;
    }

    private static @Nullable TrackMaterial.TrackType resolveTrackTypeFromCandidates(
            Level level, TrackGraph graph, TrackEdge edge,
            @Nullable Vec3 hint, TrackNode fromNode, TrackNode toNode) {
        Vec3[] candidates = new Vec3[] {
                hint,
                edge == null ? null : edge.getPosition(graph, 0.5),
                fromNode == null ? null : fromNode.getLocation().getLocation(),
                toNode == null ? null : toNode.getLocation().getLocation(),
        };
        for (Vec3 c : candidates) {
            if (c == null) continue;
            TrackMaterial.TrackType type = resolveTrackTypeNear(level, c);
            if (type != null) return type;
        }
        return null;
    }

    private static Vec3 worldPos(GrindState gs) {
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 pos = gs.edge.getPosition(gs.graph, t);

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

            if (!gs.edge.canTravelTo(candidate)) continue;

            if (!gaugeCompatibleForGrind(gs.railTrackType, trackTypeOf(candidate))) continue;

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

        TrackMaterial.TrackType crossType = resolveTrackTypeFromCandidates(
                player.level(), gs.graph, bestEdge, null, atNode, bestNeighbor);
        gs.lateralOffset = lateralOffsetForType(crossType);
        gs.yOffset = yOffsetForType(crossType);
        gs.railTrackType = trackTypeOf(bestEdge);
        return true;
    }

    private static boolean isCurrentEdgePresent(GrindState gs) {
        if (gs.graph == null || gs.fromNode == null || gs.toNode == null || gs.edge == null) return false;
        Map<TrackNode, TrackEdge> conns = gs.graph.getConnectionsFrom(gs.fromNode);
        if (conns == null) return false;
        return conns.get(gs.toNode) == gs.edge;
    }

    @Nullable
    private static TrackMaterial.TrackType trackTypeOf(@Nullable TrackEdge edge) {
        if (edge == null) return null;
        TrackMaterial mat = edge.getTrackMaterial();
        return mat == null ? null : mat.trackType;
    }

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

            boolean silent = grinding && SILENT_NEXT_START.remove(player.getUUID());
            ModNetworking.toTrackingAndSelf(
                serverPlayer, new RailGrindSyncPayload(player.getUUID(), grinding, silent));
        }
    }

    public static void syncStateToObserver(ServerPlayer observer, ServerPlayer target) {
        ModNetworking.toPlayer(observer,
            new RailGrindSyncPayload(target.getUUID(), isGrinding(target), true));
        GrindState gs = ACTIVE.get(target.getUUID());
        if (gs != null) {
            if (gs.steerSign != 0) {
                ModNetworking.toPlayer(observer,
                    new RailGrindLeanSyncPayload(target.getUUID(), (byte) gs.steerSign));
            }
            if (isAcceleratingForGrind(target, gs)) {
                ModNetworking.toPlayer(observer,
                    new RailGrindAccelSyncPayload(target.getUUID(), true));
            }
        }
    }

    private static final java.util.Set<UUID> SILENT_NEXT_START = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void markNextStartSilent(UUID uuid) {
        SILENT_NEXT_START.add(uuid);
    }

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
        ModNetworking.toPlayer(player, payload);
    }

    private static void spawnGrindParticles(ServerLevel sl, GrindState gs, Player player, double speedRatio) {
        double edgeLen = gs.edge.getLength();
        double t = edgeLen <= 0 ? 0 : Math.min(1.0, Math.max(0.0, gs.position / edgeLen));
        Vec3 tangent = sampleTangent(gs.graph, gs.edge, t);
        Vec3 chord = gs.toNode.getLocation().getLocation().subtract(gs.fromNode.getLocation().getLocation());
        if (tangent.x * chord.x + tangent.z * chord.z < 0) tangent = tangent.scale(-1);
        tangent = rotateTangentToWorld(gs, tangent);

        int count = 1 + (int) Math.round(speedRatio);
        GrindParticleBurstPayload payload = new GrindParticleBurstPayload(
            player.getUUID(),
            (float) tangent.x, (float) tangent.y, (float) tangent.z,
            (float) speedRatio,
            (byte) count);
        ModNetworking.toTrackingAndSelf(player, payload);
    }
}
